package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;

import com.overdrive.app.camera.PanoramicCameraGpu;

import java.io.File;

/**
 * GpuSurveillancePipeline - Complete GPU Zero-Copy surveillance system.
 * 
 * Orchestrates all components of the GPU pipeline:
 * - PanoramicCameraGpu: Camera → GPU texture
 * - GpuMosaicRecorder: GPU composition → Encoder
 * - GpuDownscaler: GPU thumbnail → CPU
 * - SurveillanceEngineGpu: Motion detection & AI
 * - AdaptiveBitrateController: Quality optimization
 * 
 * Achieves <10% CPU usage through GPU zero-copy architecture.
 */
public class GpuSurveillancePipeline {
    private static final String TAG = "GpuPipeline";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Components
    // volatile: read from worker threads (IdleShutdown, yield listener,
    // applyBatchedChange holds reconfigLock — different monitor than stop()'s
    // `this`) where the writer's monitor isn't held; volatile makes the
    // null-after-stop and swap-on-reinit visible without tearing.
    private volatile PanoramicCameraGpu camera;
    private volatile GpuMosaicRecorder recorder;  // Single recorder for both modes
    private GpuDownscaler downscaler;
    private SurveillanceEngineGpu sentry;
    private volatile HardwareEventRecorderGpu encoder;  // Single encoder for recording/surveillance
    private AdaptiveBitrateController bitrateController;
    
    // Streaming components (separate encoder - always available)
    private com.overdrive.app.streaming.GpuStreamScaler streamScaler;
    private HardwareEventRecorderGpu streamEncoder;
    private com.overdrive.app.streaming.WebSocketStreamServer wsStreamServer;
    private boolean streamingEnabled = false;
    
    // Telemetry overlay
    private TelemetryDataCollector telemetryCollector;
    private volatile boolean overlayEnabledConfig = false;
    
    // Mode tracking
    private enum Mode {
        IDLE,           // Nothing active
        NORMAL_RECORDING,   // User manually recording
        SURVEILLANCE    // Auto-recording on motion
    }
    // volatile: read from worker threads (IdleShutdown, IPC, GL yield) without
    // taking the monitor in stop()/start().
    private volatile Mode currentMode = Mode.IDLE;

    // External "keep pipeline alive" predicate, e.g. PROXIMITY_GUARD MONITORING
    // where currentMode is IDLE and recorder isn't recording yet, but the
    // ADAS listener is armed and will soon trigger startRecording(). Without
    // this hook the idle-shutdown timer would tear the pipeline down between
    // monitoring and the next radar trigger.
    private volatile java.util.concurrent.Callable<Boolean> keepAlivePredicate;
    
    // Configuration
    private final int cameraWidth;
    private final int cameraHeight;
    private final int encoderWidth;
    private final int encoderHeight;
    private final File eventOutputDir;
    private GpuPipelineConfig config;
    
    // State
    private boolean initialized = false;
    // volatile: idle-shutdown thread reads without taking the monitor.
    private volatile boolean running = false;
    // True while {@link #stop()} is mid-teardown (encoders releasing, EGL
    // tearing down). Concurrent start() must wait until stop completes —
    // otherwise we race the encoder release with init() allocating a new
    // one. Guarded by the same monitor as {@code running}.
    private volatile boolean stopping = false;
    private boolean recordingMode = false;  // true = recording, false = viewing only

    // Serializes runtime reconfig methods (applyFpsChange, applyBitrateChange,
    // applyCodecChange). Without this, two web-UI changes arriving back-to-back
    // can interleave reinitializeEncoder() calls — one observes encoder=null
    // mid-tear-down and silently no-ops, or worse, both threads tear down
    // recorder surfaces concurrently.
    private final Object reconfigLock = new Object();
    
    // Saved init params — needed for re-initialization after stop/start cycle (ACC OFF→ON)
    private android.content.res.AssetManager savedAssetManager;
    private android.content.Context savedContext;
    
    // Deferred recording: stored when startRecording() is called before encoder is ready
    private volatile java.io.File pendingRecordingDir = null;
    private volatile String pendingRecordingPrefix = null;
    
    /**
     * Creates the GPU surveillance pipeline.
     * 
     * @param cameraWidth Camera width (typically 5120)
     * @param cameraHeight Camera height (typically 960)
     * @param eventOutputDir Directory for event recordings
     */
    public GpuSurveillancePipeline(int cameraWidth, int cameraHeight, File eventOutputDir) {
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        // Encoder/mosaic dims are derived from the strip aspect: each tile is
        // (cameraWidth/4) wide x cameraHeight tall, mosaic is 2x2 of tiles, so
        // encoder = (cameraWidth/2) x (cameraHeight*2). Seal 5120x960 → 2560x1920
        // (4:3 quadrants). Tang 5120x720 → 2560x1440 (16:9 quadrants). Without
        // this, Tang content gets stretched 33% vertically into 4:3 mosaic tiles.
        this.encoderWidth = Math.max(1, cameraWidth / 2);
        this.encoderHeight = Math.max(1, cameraHeight * 2);
        this.eventOutputDir = eventOutputDir;
        this.config = new GpuPipelineConfig();
    }
    
    /**
     * Gets the configuration.
     */
    public GpuPipelineConfig getConfig() {
        return config;
    }

    /**
     * Returns the underlying hardware encoder, or null if the pipeline has
     * not been initialized yet. Used by callers that need the active output
     * file path for things like push-notification deep-links.
     */
    public HardwareEventRecorderGpu getEncoder() {
        return recorder != null ? recorder.getEncoder() : null;
    }
    
    /**
     * Sets the recording mode (Normal/Sentry).
     */
    public void setRecordingMode(GpuPipelineConfig.RecordingMode mode) {
        config.setRecordingMode(mode);
        
        // Apply to encoder - but DON'T override user's bitrate setting
        // Only change FPS (which requires encoder restart anyway)
        if (encoder != null) {
            // Use the user's configured bitrate, not the mode's default
            int userBitrate = config.getEffectiveBitrate();
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(userBitrate);
            }
            // Note: FPS is set during encoder initialization
            // Dynamic FPS change would require encoder restart
            logger.info(String.format("Recording mode: %s (using user bitrate=%d Mbps, mode default was %d Mbps)",
                    mode, userBitrate / 1_000_000, mode.bitrate / 1_000_000));
        }
    }
    
    /**
     * Sets the streaming quality (HQ/LQ).
     */
    public void setStreamingQuality(GpuPipelineConfig.StreamingQuality quality) {
        config.setStreamingQuality(quality);
        // Quality is saved — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        logger.info(String.format("Streaming quality saved: %s (%dx%d @ %dfps)",
                quality, quality.width, quality.height, quality.fps));
        // GL budget warning: if the stream encoder rate exceeds the
        // recording encoder rate, both run inside the same GL render loop
        // iteration — at 30+30 fps the GL thread may not have headroom.
        // Not a hard error (encoder backpressure / reactive AI-skip will
        // handle it), but worth flagging so the operator knows why
        // performance might dip.
        int recordingFps = encoder != null ? encoder.getFps() : 0;
        if (recordingFps > 0 && quality.fps > recordingFps) {
            logger.warn("Stream fps " + quality.fps
                + " > recording fps " + recordingFps
                + " — GL thread budget may be tight on heavy frames");
        }
    }
    
    /**
     * Applies a bitrate change to the encoder.
     * 
     * Reinitializes encoder immediately to ensure new bitrate is used.
     * 
     * @param bitrate New bitrate in bps
     */
    public void applyBitrateChange(int bitrate) {
        synchronized (reconfigLock) {
            applyBitrateChangeLocked(bitrate);
        }
    }

    private void applyBitrateChangeLocked(int bitrate) {
        // Update config first
        config.setCustomBitrate(bitrate);

        if (encoder == null) {
            logger.info("Bitrate setting saved (encoder not initialized yet): " + (bitrate / 1_000_000) + " Mbps");
            return;
        }

        // Check if bitrate actually changed
        if (encoder.getBitrate() == bitrate) {
            logger.info("Bitrate already set to: " + (bitrate / 1_000_000) + " Mbps");
            return;
        }

        // Bitrate-only change: inline reconfigure via MediaCodec.setParameters.
        // No encoder release, no recording restart, no pre-record loss. The
        // byte-ring pre-record buffer is bitrate-agnostic; the encoder's
        // PARAMETER_KEY_VIDEO_BITRATE is the only state that needs updating.
        // (Full reinit is reserved for codec changes — see applyCodecChange.)
        logger.info("Bitrate change: " + (bitrate / 1_000_000) + " Mbps (inline)");
        try {
            encoder.setBitrate(bitrate);
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(bitrate);
            }
            logger.info("Bitrate change applied: " + (bitrate / 1_000_000) + " Mbps");
            return;
        } catch (Exception e) {
            logger.error("Inline bitrate change failed, falling back to encoder reinit: " + e.getMessage());
        }

        // Fallback path (rare — only if MediaCodec.setParameters threw).
        // Full reinit cycle: stop recording, reinit encoder, restart.
        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

        try {
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for bitrate change (fallback)");
                recorder.stopRecording();
                Thread.sleep(500);
            }

            reinitializeEncoder();

            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(bitrate);
            }

            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new bitrate");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    logger.info("Restarting normal recording with new bitrate");
                    startRecording();
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    logger.info("Restarting deferred recording with new bitrate");
                    startRecording();
                }
            }

            logger.info("Bitrate change applied via fallback reinit: " + (bitrate / 1_000_000) + " Mbps");

        } catch (Exception e) {
            logger.error("Failed to apply bitrate change: " + e.getMessage(), e);
            try {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording || recordingMode || pendingRecordingPrefix != null) {
                    startRecording();
                }
            } catch (Exception e2) {
                logger.error("Failed to recover after bitrate change error", e2);
            }
        }
    }
    
    /**
     * Applies a recording FPS change at runtime. Persists the new fps to
     * UnifiedConfigManager (camera.targetFps), propagates it to the camera
     * (so the HAL clamps emission to that rate), and reinitializes the
     * encoder so KEY_FRAME_RATE matches.
     *
     * Range: 10-30 fps. Values outside this range are clamped — the panoramic
     * HAL on this device tops out at ~26 fps and the V2 motion pipeline is
     * tuned for 10 fps minimum (aiFrameSkip handles the higher rates).
     *
     * If recording is active, it is stopped, the encoder reinitialized, and
     * recording resumes at the new rate. If the requested fps already matches
     * the current value, no-ops.
     */
    public void applyFpsChange(int fps) {
        synchronized (reconfigLock) {
            applyFpsChangeLocked(fps);
        }
    }

    private void applyFpsChangeLocked(int fps) {
        int clamped = Math.max(10, Math.min(30, fps));
        if (clamped != fps) {
            logger.warn("FPS " + fps + " out of range [10..30] — clamped to " + clamped);
        }

        // Persist to config first so reinitializeEncoder picks it up via loadTargetFps().
        try {
            org.json.JSONObject cameraCfg = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraCfg == null) cameraCfg = new org.json.JSONObject();
            cameraCfg.put("targetFps", clamped);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", cameraCfg);
        } catch (Exception e) {
            logger.warn("Failed to persist targetFps: " + e.getMessage());
        }

        // Propagate to camera so the HAL emission rate also tracks the new target.
        if (camera != null) {
            camera.setTargetFps(clamped);
        }

        if (encoder == null) {
            logger.info("FPS setting saved (encoder not initialized yet): " + clamped + " fps");
            return;
        }
        if (encoder.getFps() == clamped) {
            logger.info("FPS already set to: " + clamped + " fps");
            return;
        }

        logger.info("FPS change requested: " + clamped + " fps - reinitializing encoder");

        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        // See applyBitrateChangeLocked for why deferred-recording counts as recording.
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

        try {
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for FPS change");
                recorder.stopRecording();
                Thread.sleep(500);
            }

            // reinitializeEncoder reads loadTargetFps() internally — picks up our persist.
            reinitializeEncoder();

            if (wasRecording) {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    startRecording();
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    // Deferred-recording window — see applyBitrateChangeLocked
                    // for the full reasoning.
                    startRecording();
                }
            }
            logger.info("FPS change applied successfully: " + clamped + " fps");
        } catch (Exception e) {
            logger.error("Failed to apply FPS change: " + e.getMessage(), e);
            try {
                if (wasSurveillance) enableSurveillance();
                else if (wasNormalRecording || recordingMode || pendingRecordingPrefix != null) startRecording();
            } catch (Exception e2) {
                logger.error("Failed to recover after FPS change error", e2);
            }
        }
    }

    /**
     * Applies a codec change. Requires encoder restart.
     *
     * @param codec New video codec
     */
    public void applyCodecChange(GpuPipelineConfig.VideoCodec codec) {
        synchronized (reconfigLock) {
            applyCodecChangeLocked(codec);
        }
    }

    private void applyCodecChangeLocked(GpuPipelineConfig.VideoCodec codec) {
        // Store the new codec setting
        config.setVideoCodec(codec);
        
        // If encoder doesn't exist yet, just save the setting
        if (encoder == null) {
            logger.info("Codec changed to: " + codec.displayName + " - will apply when encoder initializes");
            return;
        }
        
        // Check if codec actually changed
        String currentCodec = encoder.getCodecMimeType();
        String newCodec = config.getCodecMimeType();
        if (currentCodec.equals(newCodec)) {
            logger.info("Codec already set to: " + codec.displayName);
            return;
        }
        
        logger.info("Codec change requested: " + codec.displayName + " - reinitializing encoder");

        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        // See applyBitrateChangeLocked for why deferred-recording counts as recording.
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;
        
        try {
            // Stop current recording first if active
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for codec change");
                recorder.stopRecording();
                // Wait for encoder to finish writing
                Thread.sleep(500);
            }
            
            // Reinitialize encoder with new codec
            reinitializeEncoder();
            
            // Restart recording if it was active
            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new codec");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    logger.info("Restarting normal recording with new codec");
                    startRecording();
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    // Deferred-recording window — see applyBitrateChangeLocked.
                    logger.info("Restarting deferred recording with new codec");
                    startRecording();
                }
            }

            logger.info("Codec change applied successfully: " + codec.displayName);

        } catch (Exception e) {
            logger.error("Failed to apply codec change: " + e.getMessage(), e);
            // Try to recover by restarting what was running
            try {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording || recordingMode || pendingRecordingPrefix != null) {
                    startRecording();
                }
            } catch (Exception e2) {
                logger.error("Failed to recover after codec change error", e2);
            }
        }
    }

    /**
     * Applies multiple encoder reconfig knobs in a single stop / reinit /
     * restart cycle. The web UI's Quality tab Apply sends quality + codec +
     * fps together; calling apply*Change three times in sequence stops the
     * recorder once but each subsequent call observes wasRecording=false
     * (the deferred-start window) and skips its restart, leaving the pipeline
     * with no recording. Coalescing avoids that and also avoids three
     * back-to-back encoder reinits when one suffices.
     *
     * <p>Pass {@code null} for any knob you don't want to change.
     */
    public void applyBatchedChange(
            GpuPipelineConfig.RecordingQuality quality,
            GpuPipelineConfig.VideoCodec codec,
            Integer fps) {
        synchronized (reconfigLock) {
            // Three knobs, three different reconfig costs:
            //   - Bitrate: inline via MediaCodec.setParameters(VIDEO_BITRATE).
            //     Encoder stays alive, recording continues without a gap.
            //     setBitrate() also resizes the pre-record buffer to match.
            //   - FPS: camera HAL emission rate is inline via setCameraFps;
            //     encoder's KEY_FRAME_RATE is metadata for rate control and
            //     can ONLY be set at create time. We deliberately leave the
            //     encoder running with stale KEY_FRAME_RATE — bitrate accuracy
            //     drifts slightly until a natural reinit (codec/quality
            //     change or ACC cycle), but recording keeps producing frames
            //     with zero gap. Mirrors how SurveillanceEngineGpu treats
            //     setCameraTargetFps as a hint, not a teardown trigger.
            //   - Codec: requires full encoder reinit. There is no MediaCodec
            //     API for runtime codec change; KEY_MIME_TYPE is set at
            //     configure() and the encoder must be released and recreated.
            //
            // So we only stop / reinit / restart when codec actually changes.
            // Quality- and FPS-only updates are inline.
            boolean codecChanged = false;
            int newBitrate = -1;

            if (quality != null) {
                config.setRecordingQuality(quality);
                int eff = config.getEffectiveBitrate();
                if (encoder != null && encoder.getBitrate() != eff) {
                    newBitrate = eff;
                }
            }
            if (codec != null) {
                config.setVideoCodec(codec);
                String want = config.getCodecMimeType();
                if (encoder == null || !encoder.getCodecMimeType().equals(want)) {
                    codecChanged = true;
                }
            }
            int clampedFps = -1;
            if (fps != null) {
                clampedFps = Math.max(10, Math.min(30, fps));
                try {
                    org.json.JSONObject cameraCfg = com.overdrive.app.config.UnifiedConfigManager
                        .loadConfig().optJSONObject("camera");
                    if (cameraCfg == null) cameraCfg = new org.json.JSONObject();
                    cameraCfg.put("targetFps", clampedFps);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", cameraCfg);
                } catch (Exception e) {
                    logger.warn("Batched apply: failed to persist targetFps: " + e.getMessage());
                }
                if (camera != null) camera.setTargetFps(clampedFps);
            }

            if (encoder == null) {
                logger.info("Batched apply: encoder not yet initialized — settings persisted, will apply on init");
                return;
            }

            // Inline-update path: bitrate change without codec change. Encoder
            // stays alive, recording continues seamlessly.
            if (!codecChanged) {
                if (newBitrate > 0) {
                    try {
                        encoder.setBitrate(newBitrate);
                        if (bitrateController != null) {
                            bitrateController.setImmediateBitrate(newBitrate);
                        }
                        logger.info("Batched apply: inline bitrate "
                            + (newBitrate / 1_000_000) + " Mbps");
                    } catch (Exception e) {
                        logger.warn("Batched apply: inline bitrate failed: " + e.getMessage());
                    }
                }
                if (clampedFps > 0) {
                    // Resize the encoder's pre-record buffer pool to match.
                    // Doesn't touch MediaCodec — KEY_FRAME_RATE is configure-only
                    // per the Android API. The encoder keeps producing at the
                    // surface's actual delivery rate; rate control recalibrates
                    // over a few seconds.
                    encoder.setTargetFps(clampedFps);
                    logger.info("Batched apply: inline FPS " + clampedFps
                        + " (camera HAL + encoder buffer pool resized; encoder"
                        + " KEY_FRAME_RATE remains configure-time)");
                }
                if (newBitrate <= 0 && clampedFps <= 0) {
                    logger.info("Batched apply: nothing changed");
                }
                return;
            }

            // Codec-change path: full reinit cycle. Stops current recording,
            // releases old encoder, creates new one with the new MIME type
            // (and the latest bitrate/fps from config), restarts recording.
            logger.info("Batched apply: codec changed — reinitializing encoder (quality=" + quality
                + ", codec=" + codec + ", fps=" + (fps == null ? "n/a" : clampedFps) + ")");

            boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
            boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
            boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

            try {
                if (wasRecording && recorder != null && recorder.isRecording()) {
                    recorder.stopRecording();
                    Thread.sleep(500);
                }

                reinitializeEncoder();

                if (bitrateController != null && newBitrate > 0) {
                    bitrateController.setImmediateBitrate(newBitrate);
                }

                if (wasRecording) {
                    if (wasSurveillance) {
                        enableSurveillance();
                    } else if (wasNormalRecording) {
                        startRecording();
                    } else if (recordingMode || pendingRecordingPrefix != null) {
                        // Deferred-recording window — see applyBitrateChangeLocked.
                        startRecording();
                    }
                }
                logger.info("Batched apply: codec reinit complete");
            } catch (Exception e) {
                logger.error("Batched apply failed: " + e.getMessage(), e);
                try {
                    if (wasSurveillance) enableSurveillance();
                    else if (wasNormalRecording || recordingMode || pendingRecordingPrefix != null) startRecording();
                } catch (Exception e2) {
                    logger.error("Batched apply: recovery failed", e2);
                }
            }
        }
    }

    /**
     * Returns true if the encoder is alive and its configured FPS no longer
     * matches the user's selected FPS in unified config. Caller (typically
     * RecordingModeManager at the start of an ACC ON activation) is expected
     * to follow up with a {@link #stop()} so the next {@link #start()} re-runs
     * {@link #init()} and picks up the new FPS through {@link #loadTargetFps()}.
     *
     * Returning false is the no-action case: encoder hasn't been built yet
     * (next start() will pick up config naturally), pipeline isn't running,
     * or FPS is already current.
     */
    public boolean isFpsConfigStale() {
        if (!running || encoder == null) return false;
        return encoder.getFps() != loadTargetFps();
    }

    /**
     * Reads the user-selected camera FPS from unified config.
     * Falls back to 15 if missing or unreadable. Restricted to BYD-supported
     * values {8, 15, 25} via the UI; other values are clamped to 15 by the
     * settings API before being persisted.
     */
    private static int loadTargetFps() {
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                return cameraConfig.optInt("targetFps", 15);
            }
        } catch (Exception ignored) {}
        return 15;
    }

    /**
     * Reinitializes the encoder with current config settings.
     * This is a synchronous operation that waits for completion.
     *
     * SOTA: Properly synchronizes with GL thread to prevent EGL_BAD_SURFACE errors.
     */
    private void reinitializeEncoder() throws Exception {
        logger.info("Reinitializing encoder...");
        
        // SOTA: First, release recorder's encoder surface on GL thread
        // This prevents EGL_BAD_SURFACE errors when the encoder is released
        if (camera != null && camera.getGlHandler() != null && recorder != null) {
            final Object releaseLock = new Object();
            final boolean[] releaseDone = {false};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Release recorder's surface (it will be recreated after new encoder is ready)
                    recorder.releaseEncoderSurface();
                    logger.info("Recorder encoder surface released on GL thread");
                } catch (Exception e) {
                    logger.warn("Error releasing recorder surface: " + e.getMessage());
                } finally {
                    synchronized (releaseLock) {
                        releaseDone[0] = true;
                        releaseLock.notify();
                    }
                }
            });
            
            // Wait for GL thread to release surface (max 1 second)
            synchronized (releaseLock) {
                if (!releaseDone[0]) {
                    releaseLock.wait(1000);
                }
            }
        }
        
        // Now safe to release old encoder
        if (encoder != null) {
            // Wait for any pending writes to complete
            if (encoder.isWritingToFile()) {
                logger.info("Waiting for encoder to finish writing...");
                encoder.flushAndClose();
                Thread.sleep(200);
            }
            encoder.release();
            encoder = null;
        }
        
        // Create new encoder with current config
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        int fps = loadTargetFps();

        logger.info("Creating new encoder: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " @ " + fps + "fps, " + (bitrate / 1_000_000) + " Mbps");

        encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, fps, bitrate, codecMimeType);
        // On encoder reinit (codec change), restore the pre-record window
        // from the source-of-truth config for the ACTIVE recording mode.
        // Without mode-awareness, a codec change while in PROXIMITY_GUARD
        // would silently revert to the sentry/surveillance value, ignoring
        // the proximity tab's slider until the next setMode() cycle.
        //
        // The proximity controller's setPreRecordDuration call (after
        // reinit completes) is the long-term source of truth, but we seed
        // the encoder here with the right value up-front so the byte
        // ring's first allocations / window are correctly sized.
        try {
            int preRecordSec = -1;
            // Prefer proximity's value when the active mode is proximity guard.
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                if (rmm != null
                        && rmm.getCurrentMode() == com.overdrive.app.recording.RecordingModeManager.Mode.PROXIMITY_GUARD) {
                    org.json.JSONObject pgCfg =
                        com.overdrive.app.config.UnifiedConfigManager.getProximityGuard();
                    int v = pgCfg.optInt("preRecordSeconds", -1);
                    if (v > 0) preRecordSec = v;
                }
            } catch (Throwable t) {
                logger.debug("Proximity-mode pre-record lookup failed: " + t.getMessage());
            }
            // Fallback: surveillance config (the historical source).
            if (preRecordSec <= 0) {
                SurveillanceConfigManager cfgMgr = new SurveillanceConfigManager();
                if (cfgMgr.configExists()) {
                    SurveillanceConfig survCfg = cfgMgr.loadConfig();
                    preRecordSec = survCfg.getPreRecordSeconds();
                }
            }
            if (preRecordSec > 0) {
                encoder.setPreRecordDuration(preRecordSec);
            }
        } catch (Exception e) {
            logger.warn("Failed to apply pre-record duration on reinit: " + e.getMessage());
        }
        encoder.init();

        // Wire the StorageManager cleanup gate against the new encoder so
        // post-save / periodic / sidecar cleanup paths defer their delete
        // bursts while we're mid-write. Field-deref lambda (audit P1) so a
        // future reinit that swaps `encoder` is reflected without rebinding —
        // the older `enc::isWritingToFile` form captured the *instance* and
        // would return false on a released encoder, leaving cleanup un-gated
        // during the reinit window.
        try {
            com.overdrive.app.storage.StorageManager.getInstance()
                .setEncoderWritingProbe(() -> {
                    HardwareEventRecorderGpu e = this.encoder;
                    return e != null && e.isWritingToFile();
                });
        } catch (Exception e) {
            logger.warn("Failed to wire encoder writing probe: " + e.getMessage());
        }

        // Reinitialize recorder with new encoder on GL thread
        if (camera != null && camera.getEglCore() != null) {
            final Object initLock = new Object();
            final boolean[] initDone = {false};
            final Exception[] initError = {null};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Recreate recorder if needed
                    if (recorder == null) {
                        recorder = new GpuMosaicRecorder();
                    }
                    recorder.init(camera.getEglCore(), encoder);
                    logger.info("Recorder reinitialized on GL thread");
                } catch (Exception e) {
                    initError[0] = e;
                    logger.error("Failed to reinitialize recorder on GL thread", e);
                } finally {
                    synchronized (initLock) {
                        initDone[0] = true;
                        initLock.notify();
                    }
                }
            });
            
            // Wait for GL thread initialization (max 3 seconds)
            synchronized (initLock) {
                if (!initDone[0]) {
                    initLock.wait(3000);
                }
            }
            
            if (initError[0] != null) {
                throw initError[0];
            }
            
            if (!initDone[0]) {
                throw new RuntimeException("Encoder reinitialization timed out");
            }
        }
        
        // Update bitrate controller
        if (bitrateController != null) {
            bitrateController = new AdaptiveBitrateController(encoder, bitrate);
        }

        // Preserve deferred-recording intent across the encoder swap. If the
        // user had recording active and a chain of apply* calls reinit the
        // encoder more than once (multi-setting POST: quality + codec + fps),
        // the format-available listener registered against the prior encoder
        // dies with it — and `wasRecording = isRecording()` reads false on
        // the second/third apply, so the normal restart path skips. Re-arm
        // the listener here so the new encoder's first frame still triggers
        // checkPendingRecording().
        if (recordingMode || pendingRecordingPrefix != null) {
            encoder.setFormatAvailableListener(() -> {
                new Thread(() -> {
                    try {
                        checkPendingRecording();
                    } catch (Exception e) {
                        logger.warn("Deferred recording start (post-reinit) failed: " + e.getMessage());
                    }
                }, "PendingRecKickoffReinit").start();
            });
        }

        logger.info("Encoder reinitialized successfully: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " @ " + (bitrate / 1_000_000) + " Mbps");
    }
    
    /**
     * Initializes the complete GPU pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        init(savedAssetManager, savedContext);
    }
    
    /**
     * Initializes the complete GPU pipeline with AssetManager for YOLO.
     * 
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager) throws Exception {
        init(assetManager, null);
    }
    
    /**
     * Initializes the complete GPU pipeline with Context for Java TFLite.
     * 
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager, android.content.Context context) throws Exception {
        if (initialized) {
            logger.warn("Already initialized");
            return;
        }
        
        // Save for re-initialization after stop/start cycle
        if (assetManager != null) this.savedAssetManager = assetManager;
        if (context != null) this.savedContext = context;
        
        logger.info("Initializing GPU surveillance pipeline...");
        
        // Ensure output directory exists
        if (!eventOutputDir.exists()) {
            eventOutputDir.mkdirs();
        }
        
        // SOTA: Release any stuck encoder resources before creating new one
        // This helps recover from previous crashes that left encoder in bad state
        if (encoder != null) {
            logger.info("Releasing previous encoder before reinit...");
            try {
                encoder.release();
            } catch (Exception e) {
                logger.warn("Error releasing previous encoder: " + e.getMessage());
            }
            encoder = null;
        }
        
        // 1. Create hardware encoder (shared by normal recording and surveillance)
        // Use config settings for bitrate, codec, and FPS. The encoder's KEY_FRAME_RATE
        // must match the camera's setCameraFps(), otherwise the encoder's PTS pacing
        // diverges from actual frame delivery and recorded video plays back at the
        // wrong speed (faster or slower than realtime).
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        int fps = loadTargetFps();
        logger.info("Creating encoder with config: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " @ " + fps + "fps, " + (bitrate / 1_000_000) + " Mbps");
        encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, fps, bitrate, codecMimeType);

        // Pre-load saved pre-record duration BEFORE encoder.init() so the
        // byte ring is sized correctly on first allocation. Mode-aware:
        // when the persisted mode is PROXIMITY_GUARD, prefer the proximity
        // tab's value so cold boot is symmetric with the codec-reinit
        // path (see reinitializeEncoder at the same point in the file).
        // Without this, cold boot with mode=PROXIMITY_GUARD briefly sizes
        // the ring to surveillance's value before proximityController.start()
        // resizes it; functionally fine (no realloc) but inconsistent.
        SurveillanceConfig preLoadedConfig = null;
        int preRecordSec = -1;
        try {
            // Mode-aware preference: read mode FROM CONFIG (RecordingModeManager
            // isn't yet constructed at this point in init()). UnifiedConfigManager
            // exposes the persisted mode under recording.mode.
            try {
                org.json.JSONObject recCfg =
                    com.overdrive.app.config.UnifiedConfigManager.getRecording();
                String persistedMode = recCfg.optString("mode", "");
                if ("PROXIMITY_GUARD".equals(persistedMode)) {
                    org.json.JSONObject pgCfg =
                        com.overdrive.app.config.UnifiedConfigManager.getProximityGuard();
                    int v = pgCfg.optInt("preRecordSeconds", -1);
                    if (v > 0) preRecordSec = v;
                }
            } catch (Throwable t) {
                logger.debug("Cold-boot mode-aware pre-record lookup failed: " + t.getMessage());
            }
            if (preRecordSec <= 0) {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    preLoadedConfig = configManager.loadConfig();
                    preRecordSec = preLoadedConfig.getPreRecordSeconds();
                }
            }
            if (preRecordSec > 0) {
                encoder.setPreRecordDuration(preRecordSec);
                logger.info("Pre-applied pre-record duration: " + preRecordSec + "s");
            }
        } catch (Exception e) {
            logger.warn("Failed to pre-load config (will retry after init): " + e.getMessage());
        }

        encoder.init();

        // Wire the StorageManager cleanup gate (RC9). Field-deref lambda so
        // reinit-driven encoder swaps don't leave a stale instance ref.
        try {
            com.overdrive.app.storage.StorageManager.getInstance()
                .setEncoderWritingProbe(() -> {
                    HardwareEventRecorderGpu e = this.encoder;
                    return e != null && e.isWritingToFile();
                });
        } catch (Exception e) {
            logger.warn("Failed to wire encoder writing probe: " + e.getMessage());
        }

        // Resolve the camera profile NOW so the recorder, downscaler, foveated
        // cropper, and PanoramicCameraGpu all share consistent per-quadrant
        // strip-X offsets. Profile inference uses the vehicle model + any
        // user-saved override in UnifiedConfigManager.camera.cameraProfile.
        com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
            com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
        float[] quadrantStripOffsetX = resolvedCamera.getQuadrantStripOffsetX();
        float[] quadrantCornerOffsetsXY = resolvedCamera.getQuadrantCornerOffsetsXY();

        // 2. Create GPU mosaic recorder (shared) with profile-driven viewport
        // and per-quadrant offsets. Tang gets 2560x1440 instead of 2560x1920.
        recorder = new GpuMosaicRecorder(quadrantStripOffsetX, encoderWidth, encoderHeight);
        // Note: recorder.init() will be called after EGL context is created by camera

        // Wire up telemetry collector to new recorder if available
        if (telemetryCollector != null) {
            recorder.setTelemetryCollector(telemetryCollector);
        }
        // Apply persisted overlay enabled state to new recorder
        recorder.setOverlayEnabled(overlayEnabledConfig);

        // 3. Create GPU downscaler with profile-driven offsets
        downscaler = new GpuDownscaler(quadrantStripOffsetX);
        // Note: downscaler.init() will be called after EGL context is created by camera

        // 4. Create surveillance engine (uses shared recorder)
        sentry = new SurveillanceEngineGpu();
        sentry.init(eventOutputDir, downscaler, assetManager, context);  // Pass Context for Java TFLite
        sentry.setRecorder(recorder);  // Share recorder with normal recording
        // Per-vehicle camera-tile height for the foveated FOV scaling math
        // in DistanceEstimator. Seal=960, Tang=720. Without this the
        // foveated path uses a Seal-specific 0.66 ratio and reads ~30%
        // long on Tang.
        sentry.setCameraStripHeight(cameraHeight);
        // Per-quadrant vertical FOV from the active camera profile.
        // Without this, the engine uses uniform 110° for all four
        // quadrants — which inflates side-camera distances by ~70%
        // because side mirrors carry tighter optics than the
        // front/rear ultra-wide fisheyes.
        com.overdrive.app.camera.CameraProfile profile = resolvedCamera.getProfile();
        if (profile != null) {
            sentry.setCameraVerticalFovDeg(new float[]{
                    profile.getVerticalFovDeg(0),
                    profile.getVerticalFovDeg(1),
                    profile.getVerticalFovDeg(2),
                    profile.getVerticalFovDeg(3),
            });
        }

        // 4b. Apply saved config (use the pre-loaded one if available so we don't
        // hit disk twice).
        try {
            if (preLoadedConfig == null) {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    preLoadedConfig = configManager.loadConfig();
                }
            }
            if (preLoadedConfig != null) {
                sentry.setConfig(preLoadedConfig);
                logger.info("Loaded saved surveillance config");
            }
        } catch (Exception e) {
            logger.warn("Failed to load saved config, using defaults: " + e.getMessage());
        }
        
        // 5. Create camera (this creates EGL context). Pass the profile's
        // per-quadrant offsets so the foveated cropper + camera-side mosaic
        // math agree with the recorder/downscaler/scaler.
        if (cameraWidth != resolvedCamera.getPanoWidth()
                || cameraHeight != resolvedCamera.getPanoHeight()) {
            logger.warn("Pipeline geometry " + cameraWidth + "x" + cameraHeight
                + " differs from resolved camera profile "
                + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
                + " — restart the daemon to apply the new profile dimensions");
        }
        camera = new PanoramicCameraGpu(cameraWidth, cameraHeight,
            quadrantStripOffsetX, quadrantCornerOffsetsXY);
        camera.setConsumers(recorder, downscaler, sentry);

        // Camera FPS config — must match the encoder FPS used above (loadTargetFps())
        // so that camera frame delivery rate matches the encoder's KEY_FRAME_RATE.
        camera.setTargetFps(fps);
        logger.info("Camera targetFps=" + fps + " (from config)");
        logger.info("Resolved camera profile: " + resolvedCamera.getProfile().getDisplayName()
            + " (panoCam=" + resolvedCamera.getPanoCameraId()
            + ", size=" + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
            + ", surfaceMode=" + resolvedCamera.getPanoSurfaceMode() + ")");

        // Camera selection priority:
        //   1. Validated/manual override saved in UnifiedConfigManager → use as-is.
        //   2. BmmCameraInfo system hint → preferred over profile default if available.
        //   3. Profile default (Seal=1, Tang=2).
        if (resolvedCamera.isValidated() || resolvedCamera.isManualPanoOverride()) {
            logger.info("Using saved panoramic config: id=" + resolvedCamera.getPanoCameraId()
                + ", surfaceMode=" + resolvedCamera.getPanoSurfaceMode()
                + (resolvedCamera.isManualPanoOverride() ? " (manual)" : " (validated)"));
            camera.setCameraId(resolvedCamera.getPanoCameraId());
            camera.setCameraSurfaceMode(resolvedCamera.getPanoSurfaceMode());
            camera.setAutoProbeCameras(false);
            // Skip frame validation for saved configs. Luma heuristic produces
            // false negatives in low-light/uniform scenes.
            camera.setSkipFrameValidation(true);
        } else {
            int discoveredId = com.overdrive.app.camera.AvmCameraHelper.discoverPanoCameraId();
            if (discoveredId >= 0) {
                logger.info("Using BmmCameraInfo panoramic hint: camera ID " + discoveredId);
                camera.setCameraId(discoveredId);
            } else {
                logger.info("Using profile default panoramic camera ID "
                    + resolvedCamera.getPanoCameraId());
                camera.setCameraId(resolvedCamera.getPanoCameraId());
            }
            camera.setCameraSurfaceMode(resolvedCamera.getPanoSurfaceMode());
            camera.setAutoProbeCameras(false);
            camera.setSkipFrameValidation(false);
        }

        // Register probe callback — only used when manual probe is triggered via API
        camera.setCameraProbeCallback((cameraId, surfaceMode) -> {
            logger.info("Probe found working camera: id=" + cameraId + ", surfaceMode=" + surfaceMode);
            try {
                com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                    cameraId,
                    surfaceMode,
                    cameraWidth,
                    cameraHeight,
                    true,
                    false);
                logger.info("Saved camera config for next launch");
            } catch (Exception ex) {
                logger.warn("Failed to save camera config: " + ex.getMessage());
            }
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                checkPendingRecording();
            }, "PendingRecCheck").start();
        });
        
        // esco-parity: when the camera is using the SurfaceTexture path, the
        // BYD HAL emits its final framing into the producer surface directly
        // Layout 3 = DiLink 4 (HAL emits 2x2 natively but in non-canonical
        // arrangement; recorder/stream/cropper rearrange via per-role corner
        // remap below). Layout 0 = legacy 4-strip → 2x2 rearrangement. The
        // camera class returns 3 whenever cameraMode=dilink4 is selected
        // (USE_ESCO_SURFACE_TEXTURE_PATH path), 0 for every other car.
        int layoutMode = camera != null ? camera.getCameraLayoutMode() : 0;
        if (recorder != null) {
            recorder.setCameraLayout(layoutMode);
            // DiLink 4 layout is hardcoded to the only known-good arrangement
            // for that HAL: Front=TL X-flipped, Rear=TR Y-flipped, Left=BL
            // Y-flipped, Right=BR no flip. No user-tunable surface — every
            // DiLink 4 trim seen so far emits this exact mosaic.
            recorder.setProducerCornerMap(
                new float[] { 0.0f, 0.0f },  // Front  → TL
                new float[] { 0.5f, 0.5f },  // Right  → BR
                new float[] { 0.5f, 0.0f },  // Rear   → TR
                new float[] { 0.0f, 0.5f }); // Left   → BL
            recorder.setFlipFlags(
                new float[] { 1.0f, 1.0f },  // Front  X+Y-flip
                new float[] { 0.0f, 1.0f },  // Right  Y-flip
                new float[] { 0.0f, 0.0f },  // Rear   no flip
                new float[] { 0.0f, 0.0f }); // Left   no flip
            // Red-overlay mask (HAL 'calibration failed' chrome suppression).
            // Off by default; user opts in when the car is uncalibrated and
            // the chrome is in the way.
            try {
                org.json.JSONObject camCfg = com.overdrive.app.config
                    .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                if (camCfg != null) {
                    recorder.setRedMaskEnabled(
                        camCfg.optBoolean("dilink4RedMask", false));
                    // APA center inset — esco APACropFilter parity. Default
                    // 240/2560 = 0.09375 trims the chrome-painted seams on
                    // byd_apa firmware. Only applied when cameraLayout=3
                    // because the inset uniform is gated by uApaMode>2.5.
                    if (layoutMode == 3) {
                        recorder.setApaCenterInset(
                            (float) camCfg.optDouble("dilink4ApaCenterInset", 0.09375));
                    }
                }
            } catch (Throwable t) {
                logger.warn("Failed to read dilink4RedMask from config: " + t.getMessage());
            }
        }

        // 6. Create adaptive bitrate controller
        bitrateController = new AdaptiveBitrateController(encoder, 6_000_000);
        
        initialized = true;
        logger.info( "GPU surveillance pipeline initialized");
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @throws Exception if start fails
     */
    public void start() throws Exception {
        start(false);
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @param autoStartRecording If true, automatically starts recording when recorder is ready
     * @throws Exception if start fails
     */
    public void start(boolean autoStartRecording) throws Exception {
        // CRITICAL: Set running flag FIRST to prevent race conditions
        // Multiple threads may call start() concurrently (HTTP + WebSocket)
        synchronized (this) {
            if (running) {
                logger.warn( "Already running");
                return;
            }
            if (stopping) {
                // stop() is mid-teardown (encoders releasing, EGL tearing
                // down). Refuse — caller (cold-start executor) can retry on
                // its next 2-second tick when the lane has settled.
                logger.warn("Refusing start() — pipeline is mid-stop");
                return;
            }
            running = true;  // Set immediately to block concurrent starts
        }
        
        try {
            // Reinitialize if stopped (encoder/recorder were released)
            if (!initialized) {
                init();
            }
            
            logger.info( "Starting GPU pipeline (autoRecord=" + autoStartRecording + ")...");
            
            // Re-resolve camera config before starting — user may have changed
            // camera ID, profile, or role mappings via the app UI since init.
            // Resolver picks profile-default if no probed/manual config exists,
            // so this also covers "user cleared manual override → revert".
            try {
                com.overdrive.app.camera.ResolvedCameraConfig refreshedCamera =
                    com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
                int targetCameraId = refreshedCamera.getPanoCameraId();
                int targetSurfaceMode = refreshedCamera.getPanoSurfaceMode();
                int currentId = camera.getCameraId();
                if (currentId != targetCameraId) {
                    logger.info("Camera config changed since init: " + currentId + " → " + targetCameraId);
                    camera.setCameraId(targetCameraId);
                    camera.setCameraSurfaceMode(targetSurfaceMode);
                    camera.setAutoProbeCameras(false);
                    camera.setSkipFrameValidation(
                        refreshedCamera.isValidated() || refreshedCamera.isManualPanoOverride());
                }
            } catch (Exception e) {
                logger.debug("Camera config re-read failed: " + e.getMessage());
            }
            
            // Start camera (this creates EGL context and initializes downscaler)
            camera.start();
            
            // SOTA: Register yield listener for recording finalization during camera yield.
            // When contention is detected and the camera must yield to the native AVM app,
            // this ensures any active recording is properly finalized (moov atom written)
            // before the camera closes, and recording resumes after re-acquisition.
            camera.setCameraYieldListener(new PanoramicCameraGpu.CameraYieldListener() {
                @Override
                public void onPreYield() {
                    logger.info("Pre-yield: finalizing active recording...");
                    
                    // Stop any active recording to finalize the MP4 file
                    if (recorder != null && recorder.isRecording()) {
                        recorder.stopRecording();
                        logger.info("Pre-yield: recording stopped");
                    }
                    
                    // Flush encoder to ensure all buffered frames are written
                    if (encoder != null && encoder.isWritingToFile()) {
                        encoder.flushAndClose();
                        logger.info("Pre-yield: encoder flushed");
                    }
                }
                
                @Override
                public void onPostReacquire() {
                    logger.info("Post-reacquire: resuming recording and streaming...");
                    
                    // Restore streaming components if streaming was enabled.
                    // yieldCameraInternal and restartCameraAfterError call clearStreamingComponents()
                    // which nulls the camera's local refs. The pipeline still holds the actual objects.
                    if (streamingEnabled && streamScaler != null && streamEncoder != null && camera != null) {
                        camera.setStreamingComponents(streamScaler, streamEncoder);
                        logger.info("Post-reacquire: streaming components restored");
                    }
                    
                    // Resume recording in whatever mode was active before yield
                    if (currentMode == Mode.SURVEILLANCE) {
                        // Sentry mode — re-enable surveillance (it will start recording on motion)
                        if (sentry != null && !sentry.isActive()) {
                            sentry.enable();
                        }
                        logger.info("Post-reacquire: surveillance mode restored");
                    } else if (currentMode == Mode.NORMAL_RECORDING || recordingMode) {
                        // Normal recording mode — restart recording
                        if (recorder != null && !recorder.isRecording()) {
                            recorder.startRecording();
                            logger.info("Post-reacquire: normal recording resumed");
                        }
                    }
                }
            });
            
            // Wait for camera to fully initialize and GL context to be ready
            // Increased timeout to ensure recorder can be initialized
            Thread.sleep(1500);  // Increased from 1000ms to 1500ms
            
            // Set callback to start recording when recorder is ready
            if (autoStartRecording) {
                recordingMode = true;
                camera.setRecorderInitCallback(() -> {
                    logger.info( "Recorder ready - starting recording automatically");
                    recorder.startRecording();
                    currentMode = Mode.NORMAL_RECORDING;
                    
                    // Enable overlay for auto-started recording
                    recorder.setOverlayRecordingModeAllowed(true);
                    if (telemetryCollector != null && recorder.isOverlayEnabled()) {
                        telemetryCollector.setOverlayRecordingActive(true);
                        telemetryCollector.startPolling();
                    }
                });
            } else {
                recordingMode = false;
            }
            
            // Initialize recorder on GL thread (CRITICAL: must be on GL thread!)
            if (camera.getEglCore() != null) {
                camera.initRecorderOnGlThread(recorder, encoder);
                logger.info( "Recorder initialization scheduled on GL thread");
                
                // Wait for recorder to initialize before continuing
                Thread.sleep(500);
            }
            
            // DON'T auto-enable streaming - enable on-demand when client requests
            // Streaming will be enabled via enableStreaming() when HTTP client connects
            // enableStreaming() already auto-starts the pipeline if not running.
            
            // DON'T auto-enable surveillance - let caller decide
            // Surveillance should only be enabled when explicitly requested
            // sentry.enable();  // REMOVED - caller must explicitly enable
            
            logger.info( "GPU pipeline started (streaming on-demand, surveillance NOT auto-enabled)");
        
        } catch (Exception e) {
            // Reset running flag on failure so retry is possible
            synchronized (this) {
                running = false;
            }
            throw e;
        }
    }
    
    /**
     * Stops the GPU pipeline.
     *
     * <p>Synchronized on the same monitor as {@link #start(boolean)} so a
     * concurrent cold-start request (from {@code SurveillanceApiHandler}'s
     * {@code requestColdStartAsync}) can't race in mid-teardown. Without this,
     * stop() can set {@code running=false} early, and a sibling start() can
     * re-enter init() while stop() is still draining encoders / releasing
     * EGL — corrupting both lanes.
     */
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            stopping = true;
        }

        try {
            logger.info( "Stopping GPU pipeline...");

            // Clear any pending deferred recording
            pendingRecordingDir = null;
            pendingRecordingPrefix = null;
        
        // Reset mode so status API reflects that we're not in any active mode
        currentMode = Mode.IDLE;
        
        // Stop recording first to finalize file
        if (recorder != null && recorder.isRecording()) {
            recorder.stopRecording();
        }
        
        // Disable streaming — stream encoder/scaler hold EGL surfaces that will be
        // destroyed when the camera stops. They must be released before camera.stop().
        if (streamingEnabled) {
            disableStreaming();
        }
        
        // Disable surveillance
        if (sentry != null) {
            sentry.disable();
        }
        
        // Stop camera (this releases EGL context and surfaces)
        if (camera != null) {
            camera.stop();
        }
        
        // CRITICAL: Release recorder and encoder since EGL context is gone
        // They must be recreated on next start()
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
            // Mark as not initialized so init() can be called again
            initialized = false;

            logger.info( "GPU pipeline stopped");
        } finally {
            // Clear stopping flag so concurrent start() can proceed.
            synchronized (this) {
                stopping = false;
            }
        }
    }

    /**
     * Releases all resources.
     */
    public void release() {
        stop();
        
        if (bitrateController != null) {
            bitrateController.release();
            bitrateController = null;
        }
        
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        
        if (downscaler != null) {
            downscaler.release();
            downscaler = null;
        }
        
        if (sentry != null) {
            sentry.release();
            sentry = null;
        }
        
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
        initialized = false;
        logger.info( "GPU pipeline released");
    }
    
    /**
     * Starts recording.
     * Stops surveillance if active (mutually exclusive).
     */
    public void startRecording() {
        startRecording(null, "cam");
    }
    
    /**
     * Starts recording with custom output directory and filename prefix.
     * Stops surveillance if active (mutually exclusive).
     * 
     * @param outputDir Custom output directory (null for default recordings dir)
     * @param prefix Filename prefix (e.g., "cam", "proximity", "event")
     */
    public void startRecording(java.io.File outputDir, String prefix) {
        // Stop surveillance if active (mutually exclusive)
        if (currentMode == Mode.SURVEILLANCE) {
            logger.info("Stopping surveillance to start normal recording (mutually exclusive)");
            if (sentry != null) {
                sentry.disable();
            }
        }
        
        // SOTA: Ensure storage is ready (mount SD card if needed) for recordings
        if (outputDir == null) {  // Only check for default recordings dir
            try {
                StorageManager storage = StorageManager.getInstance();
                if (!storage.ensureStorageReady(false)) {
                    logger.warn("Storage not ready for recording, but continuing with fallback");
                }
            } catch (Exception e) {
                logger.warn("Error checking storage readiness: " + e.getMessage());
            }
        }
        
        if (recorder != null) {
            // Check if encoder is ready (has received at least one frame from camera).
            if (recorder.getEncoder() != null && recorder.getEncoder().isFormatAvailable()) {
                recorder.startRecording(outputDir, prefix);
                currentMode = Mode.NORMAL_RECORDING;
                recorder.setOverlayRecordingModeAllowed(true);
                if (telemetryCollector != null) {
                    telemetryCollector.setOverlayRecordingActive(true);
                    telemetryCollector.startPolling();
                }
                logger.info("Normal recording started (dir=" + (outputDir != null ? outputDir.getName() : "default") + ", prefix=" + prefix + ")");
            } else {
                // Encoder not ready yet (camera still warming up). Store the
                // request and register a one-shot listener that fires the
                // moment the encoder publishes its output format. Without
                // this, cold-start CONTINUOUS recording never began until
                // the next ACC OFF/ON cycle, because checkPendingRecording()
                // was previously only called from the camera-probe callback —
                // which is skipped when a validated camera config exists.
                logger.info("Encoder not ready yet — recording will start when camera is ready");
                pendingRecordingDir = outputDir;
                pendingRecordingPrefix = prefix;
                recordingMode = true;
                final HardwareEventRecorderGpu enc = recorder.getEncoder();
                if (enc != null) {
                    enc.setFormatAvailableListener(() -> {
                        // Posted off the encoder thread so we don't block dequeue.
                        new Thread(() -> {
                            try {
                                checkPendingRecording();
                            } catch (Exception e) {
                                logger.warn("Deferred recording start failed: " + e.getMessage());
                            }
                        }, "PendingRecKickoff").start();
                    });
                }
            }
        }
    }
    
    /**
     * Called when the encoder format becomes available (probe complete, first frame encoded).
     * Starts any pending recording that was deferred because the encoder wasn't ready.
     */
    void checkPendingRecording() {
        if (pendingRecordingPrefix == null) return;
        if (recorder == null || recorder.getEncoder() == null) return;
        if (!recorder.getEncoder().isFormatAvailable()) return;
        
        java.io.File dir = pendingRecordingDir;
        String prefix = pendingRecordingPrefix;
        pendingRecordingDir = null;
        pendingRecordingPrefix = null;
        
        logger.info("Encoder now ready — starting deferred recording");
        recorder.startRecording(dir, prefix);
        currentMode = Mode.NORMAL_RECORDING;
        recorder.setOverlayRecordingModeAllowed(true);
        if (telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
        }
        logger.info("Deferred normal recording started (dir=" + 
            (dir != null ? dir.getName() : "default") + ", prefix=" + prefix + ")");
    }
    
    /**
     * Stops recording.
     */
    public void stopRecording() {
        // CRITICAL: Clear any pending (deferred) recording request FIRST.
        // During cold start, startRecording() defers to checkPendingRecording() if the
        // encoder isn't ready yet. If a gear change (D→N/P) triggers stopRecording()
        // before the encoder is ready, the pending request survives and fires later —
        // starting recording in the wrong gear state. Clearing it here prevents that.
        pendingRecordingDir = null;
        pendingRecordingPrefix = null;
        recordingMode = false;
        
        if (recorder != null) {
            recorder.stopRecording();
            
            // Disable overlay compositing when recording stops
            recorder.setOverlayRecordingModeAllowed(false);
            if (telemetryCollector != null) {
                telemetryCollector.setOverlayRecordingActive(false);
                telemetryCollector.stopPolling();
            }
            
            currentMode = Mode.IDLE;
            logger.info( "Normal recording stopped");
        }
    }
    
    /**
     * Enables surveillance mode (motion detection + event recording).
     * Stops normal recording if active (mutually exclusive).
     * SOTA: Ensures SD card is mounted if SD card storage is selected.
     */
    public void enableSurveillance() {
        // Stop normal recording if active (mutually exclusive)
        if (currentMode == Mode.NORMAL_RECORDING) {
            logger.info("Stopping normal recording to enable surveillance (mutually exclusive)");
            if (recorder != null) {
                recorder.stopRecording();
            }
        }
        
        // SOTA: Ensure storage is ready (mount SD card if needed)
        try {
            StorageManager storage = StorageManager.getInstance();
            if (!storage.ensureStorageReady(true)) {
                logger.warn("Storage not ready for surveillance, but continuing with fallback");
            }
            
            // SOTA: Update sentry's event output directory to current surveillance path
            // This handles storage type changes (internal <-> SD card) at runtime
            if (sentry != null) {
                File currentSurveillanceDir = storage.getSurveillanceDir();
                sentry.setEventOutputDir(currentSurveillanceDir);
                logger.info("Surveillance output directory: " + currentSurveillanceDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Error checking storage readiness: " + e.getMessage());
        }
        
        if (sentry != null) {
            sentry.enable();
            currentMode = Mode.SURVEILLANCE;
            logger.info("Surveillance mode enabled (sentry.active=" + sentry.isActive() + ")");
        } else {
            logger.error("Cannot enable surveillance: sentry is null!");
        }
        
        // Disable overlay compositing in surveillance mode
        if (recorder != null) {
            recorder.setOverlayRecordingModeAllowed(false);
        }
        if (telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
        }
    }
    
    /**
     * Disables surveillance mode.
     */
    public void disableSurveillance() {
        if (sentry != null) {
            sentry.disable();
            currentMode = Mode.IDLE;
            logger.info( "Surveillance mode disabled");
        }
    }
    
    /**
     * Called when ACC turns ON - stops surveillance recording.
     * This ensures sentry recordings are properly finalized when car starts.
     * 
     * CRITICAL: Must synchronously close any active recording to prevent file corruption.
     */
    public void onAccOn() {
        logger.info("ACC ON detected - stopping surveillance and finalizing recordings");

        // First, stop any active recording immediately (synchronous)
        if (recorder != null && recorder.isRecording()) {
            logger.info("Stopping active recording before ACC transition");
            recorder.stopRecording();
        }

        // Also flush and close the encoder to ensure file is finalized
        if (encoder != null && encoder.isWritingToFile()) {
            logger.info("Flushing encoder before ACC transition");
            encoder.flushAndClose();
        }

        // Now disable surveillance mode
        if (currentMode == Mode.SURVEILLANCE) {
            disableSurveillance();
        }

        // Also stop normal recording if active
        if (currentMode == Mode.NORMAL_RECORDING) {
            stopRecording();
        }

        // CRITICAL: Reopen camera to let BYD native app get video frames.
        // During ACC OFF the daemon holds the camera exclusively for surveillance.
        // The native camera app starts on ACC ON but can't get frames because we
        // already have the primary slot. Briefly releasing and reopening the camera
        // lets the native app grab it first, then we get added as secondary consumer.
        if (camera != null && running) {
            camera.reopenCamera();
        }

        logger.info("ACC ON transition complete - all recordings finalized, camera reopened");
    }
    
    /**
     * Enables H.264 streaming with separate encoder.
     * 
     * @param streamWidth Stream width (e.g., 1280)
     * @param streamHeight Stream height (e.g., 960)
     * @param streamFps Stream FPS (e.g., 10)
     * @param streamBitrate Stream bitrate (e.g., 2 Mbps)
     */
    public void enableStreaming(int streamWidth, int streamHeight, int streamFps, 
                               int streamBitrate) throws Exception {
        if (streamingEnabled) {
            logger.warn("Streaming already enabled");
            return;
        }
        
        // Auto-start pipeline if not running (e.g., DRIVE_MODE in gear P, user opens stream)
        if (!running) {
            logger.info("Pipeline not running — auto-starting for streaming (view-only)");
            start(false);  // Start without auto-recording
        }
        
        // Verify camera GL thread is ready after start
        if (camera == null || camera.getGlHandler() == null) {
            logger.error("Cannot enable streaming - camera GL thread not ready");
            throw new IllegalStateException("Camera GL thread not initialized");
        }
        
        logger.info(String.format("Enabling H.264 streaming: %dx%d @ %dfps, %d Mbps",
                streamWidth, streamHeight, streamFps, streamBitrate / 1_000_000));
        
        // Create stream encoder
        logger.info("Creating stream encoder...");
        streamEncoder = new HardwareEventRecorderGpu(streamWidth, streamHeight, streamFps, streamBitrate);
        streamEncoder.setUsePreRecordBuffer(false);  // Stream-only, no pre-record needed
        streamEncoder.init();
        logger.info("Stream encoder initialized");
        
        // Create stream scaler
        logger.info("Creating stream scaler...");
        // Stream scaler picks the same per-role offsets used by the
        // recorder so user-mapped role-to-slice mappings affect
        // single-direction streaming too. We pass BOTH 4-strip offsets and
        // 2x2 corners; the shader picks based on uApaMode (cameraLayout).
        com.overdrive.app.camera.ResolvedCameraConfig streamCfg =
            com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
        float[] streamQuadrantStripOffsetX = streamCfg.getQuadrantStripOffsetX();
        streamScaler = new com.overdrive.app.streaming.GpuStreamScaler(
            streamWidth, streamHeight, streamQuadrantStripOffsetX);

        // Match the recorder's layout choice. esco-parity passthrough (3)
        // when SurfaceTexture path is active; legacy 4-cam mosaic (0)
        // otherwise.
        boolean streamUsingEscoPath =
            (camera != null && camera.isUsingEscoSurfaceTexturePath());
        streamScaler.setCameraLayout(streamUsingEscoPath ? 3 : 0);

        // Hardcoded Variant A corner+flip constants on DiLink 4. Mirrors
        // GpuMosaicRecorder so live stream and recording stay aligned. On
        // legacy cars the uniforms are unused (uApaMode != 3 path).
        if (streamUsingEscoPath) {
            streamScaler.setProducerCornerMap(
                new float[] { 0.0f, 0.0f },  // Front  → producer TL
                new float[] { 0.5f, 0.5f },  // Right  → producer BR
                new float[] { 0.5f, 0.0f },  // Rear   → producer TR
                new float[] { 0.0f, 0.5f }); // Left   → producer BL
            streamScaler.setFlipFlags(
                new float[] { 1.0f, 1.0f },  // Front  X+Y-flip
                new float[] { 0.0f, 1.0f },  // Right  Y-flip
                new float[] { 0.0f, 0.0f },  // Rear   no flip
                new float[] { 0.0f, 0.0f }); // Left   no flip
            // Red-overlay suppression follows the recorder. Read the same
            // unified-config flag so the live preview matches the MP4.
            try {
                org.json.JSONObject camCfgStream = com.overdrive.app.config
                    .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                if (camCfgStream != null) {
                    streamScaler.setRedMaskEnabled(
                        camCfgStream.optBoolean("dilink4RedMask", false));
                    streamScaler.setApaCenterInset(
                        (float) camCfgStream.optDouble("dilink4ApaCenterInset", 0.09375));
                }
            } catch (Throwable t) {
                logger.warn("Stream scaler red-mask flag read failed: " + t.getMessage());
            }
        }
        
        // Initialize on GL thread and WAIT for completion
        // This ensures the scaler is ready before we set streaming components
        final Object initLock = new Object();
        final boolean[] initDone = {false};
        final Exception[] initError = {null};
        
        camera.getGlHandler().post(() -> {
            try {
                streamScaler.init(camera.getEglCore(), streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
                initError[0] = e;
            } finally {
                synchronized (initLock) {
                    initDone[0] = true;
                    initLock.notify();
                }
            }
        });
        
        // Wait for GL thread initialization (max 2 seconds)
        synchronized (initLock) {
            if (!initDone[0]) {
                initLock.wait(2000);
            }
        }
        
        if (!initDone[0]) {
            throw new RuntimeException("Stream scaler initialization timed out");
        }
        
        if (initError[0] != null) {
            throw new RuntimeException("Stream scaler initialization failed: " + initError[0].getMessage(), initError[0]);
        }
        
        // Now set components on camera (scaler is guaranteed initialized)
        logger.info("Setting streaming components on camera...");
        camera.setStreamingComponents(streamScaler, streamEncoder);
        
        // Create WebSocket stream server (port 8887)
        // WebSocket has zero buffering delay vs HTTP Chunked (64KB+ buffer)
        logger.info("Starting WebSocket stream server...");
        wsStreamServer = new com.overdrive.app.streaming.WebSocketStreamServer();
        
        // Set idle shutdown callback - auto-stop pipeline when no clients for 15 seconds
        final GpuSurveillancePipeline self = this;
        wsStreamServer.setIdleShutdownCallback(new Runnable() {
            @Override
            public void run() {
                logger.info("WebSocket idle timeout - stopping streaming");
                // Run on separate thread to avoid blocking timer thread
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            self.disableStreaming();
                            // Snapshot every cross-thread field once. Without this, a
                            // concurrent stop() can null `recorder` between the null
                            // check and the isRecording() call, NPEing into the
                            // outer catch and leaving streaming half-released.
                            GpuMosaicRecorder rec = recorder;
                            boolean recordingActive = rec != null && rec.isRecording();
                            Mode mode = currentMode;
                            boolean keepAlive = false;
                            try {
                                java.util.concurrent.Callable<Boolean> hook = keepAlivePredicate;
                                if (hook != null) keepAlive = Boolean.TRUE.equals(hook.call());
                            } catch (Exception e) {
                                logger.warn("keepAlive predicate threw: " + e.getMessage());
                            }
                            // Keep pipeline running if ANY consumer still needs the camera/encoder:
                            //   - SURVEILLANCE: motion-triggered recording
                            //   - NORMAL_RECORDING: continuous / drive-mode recording
                            //   - active recorder: event recording in flight (proximity, manual)
                            //   - pending deferred recording: startRecording() before encoder ready
                            //   - keepAlive hook: PROXIMITY_GUARD MONITORING (radar armed, no
                            //     recording yet) — without this the pipeline would tear
                            //     down between trigger windows and the next event would
                            //     silently no-op against a null recorder.
                            boolean pendingRec = pendingRecordingDir != null;
                            if (mode == Mode.IDLE && !recordingActive && !pendingRec && !keepAlive && running) {
                                logger.info("No recording consumers active - stopping pipeline to save resources");
                                self.stop();
                            } else {
                                logger.info("Pipeline kept alive (mode=" + mode
                                    + ", recording=" + recordingActive
                                    + ", pending=" + pendingRec
                                    + ", keepAlive=" + keepAlive + ")");
                            }
                        } catch (Exception e) {
                            logger.error("Error during idle shutdown", e);
                        }
                    }
                }, "IdleShutdown").start();
            }
        });
        
        wsStreamServer.start();
        logger.info("WebSocket server started, setting stream callback...");
        streamEncoder.setStreamCallback(wsStreamServer);
        
        streamingEnabled = true;
        logger.info("H.264 streaming enabled (WebSocket port 8887)");
    }
    
    /**
     * Disables H.264 streaming and releases stream encoder.
     */
    public void disableStreaming() {
        if (!streamingEnabled) {
            return;
        }
        
        logger.info("Disabling H.264 streaming...");
        streamingEnabled = false;
        
        // CRITICAL: Clear streaming components from camera FIRST
        // This prevents render loop from using released surfaces
        if (camera != null) {
            camera.clearStreamingComponents();
        }
        
        // Clear stream callback
        if (streamEncoder != null) {
            streamEncoder.clearStreamCallback();
        }
        
        // Stop WebSocket server
        if (wsStreamServer != null) {
            wsStreamServer.shutdown();
            wsStreamServer = null;
        }
        
        // Release stream encoder
        if (streamEncoder != null) {
            streamEncoder.release();
            streamEncoder = null;
        }
        
        // Release stream scaler
        if (streamScaler != null) {
            streamScaler.release();
            streamScaler = null;
        }
        
        logger.info("H.264 streaming disabled");
    }
    
    /**
     * Checks if streaming is enabled.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }
    
    /**
     * Gets the stream scaler component.
     */
    public com.overdrive.app.streaming.GpuStreamScaler getStreamScaler() {
        return streamScaler;
    }
    
    /**
     * Gets the stream encoder component.
     */
    public HardwareEventRecorderGpu getStreamEncoder() {
        return streamEncoder;
    }
    
    /**
     * Gets the WebSocket stream server.
     */
    public com.overdrive.app.streaming.WebSocketStreamServer getWebSocketServer() {
        return wsStreamServer;
    }
    
    /**
     * Sets the stream view mode (which camera to show).
     * 
     * @param mode 0=Mosaic (2x2 grid), 1=Front, 2=Right, 3=Rear, 4=Left
     */
    public void setStreamViewMode(int mode) {
        if (streamScaler != null) {
            streamScaler.setViewMode(mode);
            logger.info("Stream view mode changed to " + mode);
        } else {
            logger.warn("Cannot set stream view mode - streaming not enabled");
        }
    }
    
    /**
     * Gets the current stream view mode.
     * 
     * @return 0=Mosaic, 1-4=Single camera, -1 if streaming not enabled
     */
    public int getStreamViewMode() {
        return streamScaler != null ? streamScaler.getViewMode() : -1;
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    /**
     * Checks if in recording mode (vs viewing mode).
     */
    public boolean isRecordingMode() {
        return recordingMode;
    }
    
    /**
     * Checks if initialized.
     * 
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    private static String getVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Checks if running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Register an external predicate that the WebSocket idle-shutdown
     * callback consults before tearing the pipeline down. Returning true
     * keeps the pipeline alive even when no recording is currently in
     * flight — used by PROXIMITY_GUARD MONITORING (radar armed, waiting
     * for trigger).
     *
     * <p>Pass {@code null} to clear. Predicate is called from the
     * IdleShutdown thread; implementation must be thread-safe and
     * non-blocking.
     */
    public void setKeepAlivePredicate(java.util.concurrent.Callable<Boolean> predicate) {
        this.keepAlivePredicate = predicate;
    }

    /**
     * Gets the camera component.
     * 
     * @return PanoramicCameraGpu instance
     */
    public PanoramicCameraGpu getCamera() {
        return camera;
    }
    
    /**
     * Gets the surveillance engine.
     * 
     * @return SurveillanceEngineGpu instance
     */
    public SurveillanceEngineGpu getSentry() {
        return sentry;
    }
    
    /**
     * Gets the adaptive bitrate controller.
     * 
     * @return AdaptiveBitrateController instance
     */
    public AdaptiveBitrateController getBitrateController() {
        return bitrateController;
    }
    
    /**
     * Checks if surveillance mode is active.
     * 
     * @return true if in surveillance mode
     */
    public boolean isSurveillanceMode() {
        return currentMode == Mode.SURVEILLANCE;
    }
    
    /**
     * Checks if normal recording mode is active.
     * 
     * @return true if in normal recording mode
     */
    public boolean isNormalRecordingMode() {
        return currentMode == Mode.NORMAL_RECORDING;
    }
    
    /**
     * Sets the telemetry collector instance for overlay data.
     */
    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryCollector(collector);
        }
    }
    
    /**
     * Enables or disables the telemetry overlay.
     * Starts/stops the telemetry collector based on current recording mode.
     */
    public void setOverlayEnabled(boolean enabled) {
        this.overlayEnabledConfig = enabled;
        if (recorder != null) {
            recorder.setOverlayEnabled(enabled);
        }
        // Start/stop telemetry collector based on overlay state
        if (enabled && currentMode == Mode.NORMAL_RECORDING && telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
        } else if (!enabled && telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
        }
    }
}
