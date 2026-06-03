package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Streaming API Handler - manages WebSocket streaming configuration and control.
 * 
 * Endpoints:
 * - POST /api/stream/enable - Enable WebSocket streaming
 * - POST /api/stream/disable - Disable WebSocket streaming
 * - GET /api/stream/status - Get streaming status
 * - GET /api/stream/quality - Get available quality presets
 * - POST /api/stream/quality/{preset} - Set streaming quality
 * - POST /api/stream/view/{mode} - Set view mode (0=Mosaic, 1-4=AVM quadrant, 6=OEM Dashcam)
 * - GET /api/stream/view - Get current view mode
 */
public class StreamingApiHandler {

    private static String streamingQuality = "LOW";  // Default to LOW for better performance

    // Last view-mode the user explicitly picked, persisted across scaler
    // teardown / WS idle-shutdown / reconnect cycles. The scaler's own
    // currentViewMode field is cleared every time disableStreaming nulls
    // the scaler — pipeline.getStreamViewMode() returns -1 in that window.
    // Mobile browsers naturally hit this: backgrounding the tab >15s fires
    // the WS idle-shutdown → next WS open finds savedViewMode==-1 → fresh
    // scaler defaults to view 0, even though the user is still on DVR view.
    // This static survives the teardown so HttpServer's WS-open path can
    // re-apply the correct view (and re-route OEM for view 6).
    // -1 = never set; 0..6 valid mode values.
    private static volatile int lastDesiredViewMode = -1;
    public static int getLastDesiredViewMode() { return lastDesiredViewMode; }
    public static void setLastDesiredViewMode(int mode) {
        if (mode >= 0 && mode <= 6) lastDesiredViewMode = mode;
    }

    // Cold-start dedup. The first DVR / view-set click on a fresh daemon
    // takes ~4-9s to warm AVC HAL + open AVMCamera + EGL setup. Without
    // dedup, every retry click re-queues the same expensive work onto the
    // HTTP worker pool and floods CameraDaemon's lifecycle lock. The flag
    // is flipped true the moment we spawn the warm-up worker, cleared in
    // the worker's finally; intermediate clicks short-circuit to
    // {success:false, starting:true} so the JS poll loop just waits.
    private static final java.util.concurrent.atomic.AtomicBoolean panoStartInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Kick a pano cold-start asynchronously if the pipeline isn't running
     * yet and no warmup is currently in flight. Returns true iff the
     * pipeline is already warm; false signals "starting" and the caller
     * should respond with starting=true so the client polls.
     */
    private static boolean ensurePanoStartedNonBlocking(GpuSurveillancePipeline pano) {
        if (pano == null) return false;
        if (pano.isRunning()) return true;
        // Spawn a single warm-up worker. Re-entrant clicks see the flag
        // and short-circuit without enqueueing duplicate work.
        if (panoStartInFlight.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    com.overdrive.app.camera.AvcHalWarmup warmup =
                        new com.overdrive.app.camera.AvcHalWarmup();
                    warmup.warmupAndWait();
                    pano.start();
                } catch (Throwable t) {
                    CameraDaemon.log("ensurePanoStartedNonBlocking: " + t.getMessage());
                } finally {
                    panoStartInFlight.set(false);
                }
            }, "PanoColdStart").start();
        }
        return false;
    }
    
    /**
     * Handle streaming API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/stream/enable") && method.equals("POST")) {
            handleEnableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/disable") && method.equals("POST")) {
            handleDisableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/status") && method.equals("GET")) {
            sendStreamStatus(out);
            return true;
        }
        if (path.equals("/api/stream/quality") && method.equals("GET")) {
            sendStreamQualityOptions(out);
            return true;
        }
        if (path.startsWith("/api/stream/quality/") && method.equals("POST")) {
            String quality = path.substring(20).toUpperCase();
            handleSetStreamQuality(out, quality);
            return true;
        }
        if (path.startsWith("/api/stream/view/")) {
            int viewMode = Integer.parseInt(path.substring(17));
            handleStreamViewMode(out, viewMode);
            return true;
        }
        if (path.equals("/api/stream/view") && method.equals("GET")) {
            sendStreamViewMode(out);
            return true;
        }
        return false;
    }
    
    private static void handleEnableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        CameraDaemon.log("handleEnableStreaming: pipeline=" + (pipeline != null) + 
                        ", running=" + (pipeline != null && pipeline.isRunning()));
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_initialized"));
            return;
        }
        
        // Auto-start pipeline if not running. Cold-start runs on a worker
        // thread (warmup + AVMCamera open is ~4-9s) and we return
        // starting=true so the HTTP worker thread isn't blocked. Client
        // re-polls until pipelineRunning flips true.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        
        if (pipeline.isStreamingEnabled()) {
            CameraDaemon.log("handleEnableStreaming: already enabled");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_already_enabled"));
            response.put("wsPort", 8887);
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
            CameraDaemon.log("handleEnableStreaming: quality=" + q.displayName);
            pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            
            CameraDaemon.log("handleEnableStreaming: success");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_enabled"));
            response.put("wsPort", 8887);
            response.put("quality", q.name());
            response.put("resolution", q.width + "x" + q.height);
            response.put("fps", q.fps);
            response.put("bitrate", q.bitrate);
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("handleEnableStreaming: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleDisableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }

        pipeline.disableStreaming();

        // Once the WS pipe goes dark, the OEM-stream "keep warm" reason
        // disappears. Re-evaluate so we tear down OEM if no recording
        // mode is asking for it.
        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.streaming_disabled"));
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("pipelineRunning", pipeline != null && pipeline.isRunning());
        response.put("streamingEnabled", pipeline != null && pipeline.isStreamingEnabled());
        response.put("wsPort", 8887);
        
        if (pipeline != null && pipeline.isStreamingEnabled()) {
            response.put("viewMode", pipeline.getStreamViewMode());
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
            int vm = pipeline.getStreamViewMode();
            response.put("viewName", vm >= 0 && vm < modeNames.length ? modeNames[vm] : "Unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamQualityOptions(OutputStream out) throws Exception {
        CameraDaemon.log("sendStreamQualityOptions: current=" + streamingQuality);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("current", streamingQuality);
        
        JSONArray options = new JSONArray();
        for (GpuPipelineConfig.StreamingQuality q : GpuPipelineConfig.StreamingQuality.values()) {
            JSONObject opt = new JSONObject();
            opt.put("id", q.name());
            opt.put("name", q.displayName);
            opt.put("width", q.width);
            opt.put("height", q.height);
            opt.put("fps", q.fps);
            opt.put("bitrate", q.bitrate);
            opt.put("bitrateKbps", q.bitrate / 1000);
            options.put(opt);
        }
        response.put("options", options);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleSetStreamQuality(OutputStream out, String quality) throws Exception {
        GpuPipelineConfig.StreamingQuality newQuality = GpuPipelineConfig.StreamingQuality.fromString(quality);

        streamingQuality = newQuality.name();
        CameraDaemon.setStreamingQuality(quality);

        // Persist to UnifiedConfigManager (streaming.quality) so the choice
        // survives daemon restart. Mirrors the recording-side flow where
        // QualitySettingsApiHandler.persistSettings is the single canonical
        // writer for both recording and streaming sections — without this
        // call the in-memory `streamingQuality` field is the only record of
        // the user's pick, and a kill/restart silently reverts to whatever
        // the on-disk default seeded (MEDIUM).
        QualitySettingsApiHandler.persistSettings();

        // Save quality preference — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        // The /ws handler applies the quality when the client reconnects.
        CameraDaemon.log("Streaming quality set to: " + newQuality.displayName + " (persisted)");
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("quality", newQuality.name());
        response.put("displayName", newQuality.displayName);
        response.put("width", newQuality.width);
        response.put("height", newQuality.height);
        response.put("fps", newQuality.fps);
        response.put("bitrate", newQuality.bitrate);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleStreamViewMode(OutputStream out, int viewMode) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        
        if (viewMode < 0 || viewMode > 6) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_invalid_view_mode"));
            return;
        }

        // View mode 6 = OEM Dashcam (separate forward sensor pipeline).
        // View 5 stays the legacy raw passthrough (pano strip debug) so
        // existing tooling that pokes /api/stream/view/5 keeps working.
        // Routes the WebSocket stream to OemDashcamPipeline's encoder
        // bitstream instead of the AVM mosaic. The OEM pipeline must be
        // started separately by RecordingModeManager / Settings; we do NOT
        // auto-start it here because (a) it requires a configured
        // oemDashcamCameraId, and (b) on single-AVM-client HALs starting
        // it would yield the pano pipeline.
        if (viewMode == 6) {
            handleOemDashcamView(out);
            return;
        }

        // Idempotency short-circuit: if pipeline is already running,
        // streaming is already enabled, and view is already at the
        // requested mode, return success without re-running any
        // side-effecting work. Repeated identical GETs from the JS
        // poll loop should be cheap.
        if (pipeline.isRunning() && pipeline.isStreamingEnabled()
                && pipeline.getStreamViewMode() == viewMode) {
            // Defensive: refresh the static so a daemon-restart-then-idempotent-hit
            // can't leave lastDesiredViewMode out of sync with the live scaler.
            setLastDesiredViewMode(viewMode);
            String[] modeNamesIdem = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
            JSONObject ok = new JSONObject();
            ok.put("success", true);
            ok.put("viewMode", viewMode);
            ok.put("viewName", viewMode < modeNamesIdem.length ? modeNamesIdem[viewMode] : "Unknown");
            HttpResponse.sendJson(out, ok.toString());
            return;
        }

        // Auto-start pipeline if not running. Cold-start is async — the
        // 4-9s warmup runs on a dedup'd worker thread and we report
        // starting=true so the JS poll loop just waits.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", viewMode);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }

        // Enable streaming first if not enabled. enableStreaming is
        // synchronous (allocates encoder + scaler on the GL thread) and
        // typically returns under 200ms, so we keep it inline.
        if (!pipeline.isStreamingEnabled()) {
            try {
                CameraDaemon.log("Enabling streaming before setting view mode");
                GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, Messages.get("errors.streaming_enable_failed_with_detail", e.getMessage()));
                return;
            }
        }
        
        // Capture the prior view BEFORE we change it so the OEM lifecycle
        // recalc only fires on transitions in/out of view 6. Pre-fix every
        // AVM quadrant click triggered a recalc, which on smart-mode arms
        // would warm the OEM camera unnecessarily for no consumer.
        int prevView = pipeline.getStreamViewMode();
        pipeline.setStreamViewMode(viewMode);
        // Persist across scaler teardown so a future WS reconnect after
        // idle-shutdown can re-apply the user's pick. See lastDesiredViewMode
        // doc above.
        setLastDesiredViewMode(viewMode);

        // If a prior view-6 selection swapped the WS sink to the OEM encoder,
        // restore pano now so view 0..5 actually delivers pano frames again.
        // Direct call — reflection here would have the same R8-rename failure
        // mode as the original routeStreamToOemDashcam bug (the surveillance
        // package members aren't preserved by name, so a getMethod() lookup
        // throws NoSuchMethodException in release builds).
        try {
            pipeline.reattachOwnStreamCallback();
        } catch (Throwable t) {
            CameraDaemon.log("reattachOwnStreamCallback failed: " + t.getMessage());
        }

        // Re-evaluate the OEM lifecycle ONLY on view-6 boundary crossings.
        // Switching from view 0 → view 1 doesn't change OEM's required
        // state (no streaming viewer either way) and used to spuriously
        // boot the pipeline when smart mode was armed.
        if (prevView == 6 || viewMode == 6) {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        }

        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
        CameraDaemon.log("Stream view mode set to: " + (viewMode < modeNames.length ? modeNames[viewMode] : "Unknown"));
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamViewMode(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        int viewMode = (pipeline != null) ? pipeline.getStreamViewMode() : -1;
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode >= 0 && viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Handle a stream view mode = 6 request: route the WebSocket stream to
     * the OEM Dashcam pipeline's encoder bitstream. Returns starting=true
     * while the pano + OEM pipelines come up so the JS poll loop just
     * waits — no blocking on the HTTP worker thread. View 5 stays the
     * legacy raw debug passthrough.
     */
    private static void handleOemDashcamView(OutputStream out) throws Exception {
        GpuSurveillancePipeline pano = CameraDaemon.getGpuPipeline();
        // Async-warm pano if needed; return starting=true while it's coming up.
        if (!ensurePanoStartedNonBlocking(pano)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "pano_starting");
            pending.put("error", "Pipeline starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        // Pano is up but streaming isn't enabled yet. enableStreaming is
        // synchronous + cheap (~100-200ms), so inline is fine.
        if (pano != null && !pano.isStreamingEnabled()) {
            try {
                GpuPipelineConfig.StreamingQuality q =
                    GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pano.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                CameraDaemon.log("handleOemDashcamView: pano.enableStreaming failed: " + e.getMessage());
            }
        }
        // Defensive — if streaming still didn't come up (enableStreaming
        // threw, or a concurrent disable just nulled the scaler), the route
        // call below will fail opaquely. Surface it as starting=true so the
        // client retries with the next poll instead of seeing an
        // unrecoverable "stream routing not yet available".
        if (pano == null || !pano.isStreamingEnabled()) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "stream_starting");
            pending.put("error", "Streaming starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }

        com.overdrive.app.camera.OemDashcamPipeline oem = CameraDaemon.getOemDashcamPipeline();
        // Gate on isRouteReady (camera texture + SurfaceTexture allocated)
        // not just isRunning. The pipeline's running flag flips true at the
        // top of start() before initEglAndEncoder allocates the EXTERNAL_OES
        // texture; a view-6 click arriving in that window would otherwise
        // skip the oem_starting path and fall straight into a
        // routeStreamToOemDashcam() call that finds cameraTextureId=0 and
        // surfaces "OEM Dashcam stream routing not yet available" — which
        // the user can't recover from without retrying.
        if (oem == null || !oem.isRouteReady()) {
            int resolved = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
            if (resolved < 0) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("viewMode", 6);
                err.put("errorCode", "oem_disabled");
                err.put("error", "OEM Dashcam disabled on this vehicle");
                HttpResponse.sendJson(out, err.toString());
                return;
            }
            // resolveOemDashcamId honours the XOR-of-pano default, so on
            // every install (even fresh, no manual override) we get back
            // a candidate id and ATTEMPT a start. Only after the start
            // throws (e.g. validateHalDimsOrReject on hardware with no
            // separate forward sensor) does UCM hold a `lastStartError`.
            // If we see one — and we haven't successfully started this
            // pipeline since — surface it as a real terminal failure so
            // the JS poll loop stops retrying. Without this short-circuit
            // the user sits on an "OEM Dashcam starting…" toast for ~30s
            // until the poll geometric backoff exhausts, and even then
            // gets the original opaque message.
            try {
                org.json.JSONObject oemCfg = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
                if (oemCfg.has("lastStartError") && !oemCfg.isNull("lastStartError")) {
                    String reason = oemCfg.optString("lastStartError", "");
                    long lastAt = oemCfg.optLong("lastStartErrorAt", 0L);
                    long ageMs = System.currentTimeMillis() - lastAt;
                    // Honor the sticky error only while it's recent (60 s) —
                    // beyond that, treat it as stale (transient HAL warmup
                    // failures shouldn't lock out streaming forever; the
                    // user's only recovery is currently an APK reinstall).
                    // Past the TTL, fall through to the normal lifecycle
                    // recalc path so a fresh start can clear it via
                    // startPipeline's lastStartError reset.
                    if (!reason.isEmpty() && lastAt > 0 && ageMs < 60_000L) {
                        JSONObject err = new JSONObject();
                        err.put("success", false);
                        err.put("viewMode", 6);
                        err.put("errorCode", "oem_unsupported");
                        err.put("error", "OEM Dashcam unavailable: " + reason);
                        HttpResponse.sendJson(out, err.toString());
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            // Streaming-only kick — we never flip recordingMode in UCM.
            // applyTriggerLifecycleFromUcm sees isAnyStreamingViewerActive()
            // (view 6 about to be set) and brings the camera + EGL up
            // without flipping recording on. Only schedule a recalc when
            // the pipeline isn't already started — re-kicking an in-flight
            // start() is wasted lifecycle churn.
            try {
                if (pano != null) pano.setStreamViewMode(6);
            } catch (Throwable ignored) {}
            // Persist intent now (not only on the success branch). A WS
            // reconnect during the OEM warmup window would otherwise read
            // lastDesiredViewMode=-1, fall back to scaler-state, and miss
            // the user's pick if a teardown lands in that gap.
            setLastDesiredViewMode(6);
            if (oem == null || !oem.isRunning()) {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            }
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "oem_starting");
            pending.put("error", "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        // Route the existing WebSocket stream sink to the OEM encoder. The
        // pano pipeline keeps running but its stream callback is detached
        // for the duration; switching back to view 0..4 reattaches. The
        // routing returns false when the GPU pipeline hasn't yet exposed
        // the attachExternalStreamCallback hook (Phase-9 plumbing) — in
        // that case we MUST tell the client the switch did not actually
        // take effect, otherwise the UI flips to "OEM Dashcam" while the
        // WS continues to deliver AVM mosaic frames.
        boolean routed = CameraDaemon.routeStreamToOemDashcam();
        JSONObject response = new JSONObject();
        if (!routed) {
            // Re-kick the lifecycle so a missed-edge race between
            // isRouteReady() flipping true and attachExternalStreamCallback's
            // own gates (streamingEnabled / streamScaler / oemTextureId)
            // gets retried on the next poll instead of stranding the user
            // on a permanent toast.
            try {
                if (oem == null || !oem.isRunning()) {
                    com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                }
            } catch (Throwable ignored) {}
            response.put("success", false);
            response.put("viewMode", 6);
            response.put("starting", true);
            response.put("errorCode", "oem_starting");
            response.put("error", "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        // Tell the scaler to switch its sample shader branch to the OEM
        // path (view 6). Without this the scaler keeps rendering the AVM
        // mosaic even though the OEM texture has been bound.
        try {
            pano.setStreamViewMode(6);
        } catch (Throwable t) {
            CameraDaemon.log("setStreamViewMode(6) failed: " + t.getMessage());
        }
        // Persist user's pick across scaler teardown so a WS reconnect
        // after idle-shutdown re-applies view 6 + OEM re-route.
        setLastDesiredViewMode(6);
        response.put("success", true);
        response.put("viewMode", 6);
        response.put("viewName", "OEM Dashcam");
        HttpResponse.sendJson(out, response.toString());
    }
    
    // Static getters/setters for cross-component access
    public static String getStreamingQuality() { return streamingQuality; }
    
    public static void setStreamingQuality(String quality) {
        if (quality == null) return;
        String q = quality.toUpperCase();
        // Mirror the StreamingQuality enum (GpuPipelineConfig). SMOOTH and MAX
        // are recent additions; the cold-start loader (QualitySettingsApiHandler.
        // loadPersistedSettings) calls this with whatever tag is on disk, so if
        // we silently reject MAX here the persisted user pick decays to the
        // hard-coded default after every daemon restart.
        switch (q) {
            case "ULTRA_LOW":
            case "LOW":
            case "MEDIUM":
            case "HIGH":
            case "ULTRA_HIGH":
            case "SMOOTH":
            case "MAX":
            case "LQ":
            case "HQ":
                streamingQuality = q;
                break;
            default:
                // Unknown tag — leave previous value intact.
                break;
        }
    }
}
