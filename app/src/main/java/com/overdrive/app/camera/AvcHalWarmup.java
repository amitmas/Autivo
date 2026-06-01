package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;

/**
 * AVC HAL Warmup — ensures the BYD camera HAL is initialized by com.byd.avc
 * BEFORE our daemon opens the camera.
 *
 * PROBLEM: When ACC turns ON, both our daemon and the native DVR (com.byd.cdr)
 * race to open the panoramic camera. If our daemon opens first, the HAL enters
 * a state where the native DVR can't attach its surface → "no video signal."
 *
 * SOLUTION:
 * 1. Launch com.byd.avc silently (the camera HAL initializer, NOT the DVR)
 * 2. Wait 4 seconds for the HAL to fully initialize in multi-consumer mode
 * 3. THEN open our camera as a secondary consumer
 *
 * Additionally, a 60-second keep-alive watchdog re-pokes com.byd.avc while
 * the pipeline is running, regardless of ACC state. BYD's system can kill
 * the camera app after inactivity, which destabilizes the HAL for all
 * consumers — including during ACC OFF sentry mode when the head unit stays
 * awake (charging, surveillance armed).
 *
 * LIFECYCLE:
 * - start() when pipeline starts (any mode, any ACC state)
 * - stop() when pipeline stops OR daemon shuts down
 *
 * <p><b>DiLink 4 carve-out.</b> On byd_apa firmware (cameraMode=dilink4) the
 * AVC app is the entity that paints the red 'calibration failed' chrome on
 * top of our preview surface. esco actively evicts AVC from the foreground
 * to suppress that overlay. We approximate the same behaviour by simply
 * NOT warming AVC up and NOT poking it on a keep-alive timer when
 * cameraMode=dilink4 — every public entry point ({@link #warmupAndWait()},
 * {@link #startKeepAlive()}) checks {@link #shouldSuppressAvc()} and
 * no-ops on that path. Legacy cars (90% of the fleet) keep full warmup +
 * keep-alive behaviour bit-exact.
 */
public class AvcHalWarmup {

    private static final String TAG = "AvcHalWarmup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * True when the active camera mode is "dilink4" — in which case we want
     * com.byd.avc OUT of the way, not warmed up. Reads the unified config
     * fresh; cheap (single JSON load) and called only at warmup/keep-alive
     * entry points (not per-frame).
     */
    private static boolean shouldSuppressAvc() {
        try {
            org.json.JSONObject root = com.overdrive.app.config
                .UnifiedConfigManager.loadConfig();
            org.json.JSONObject cam = root != null ? root.optJSONObject("camera") : null;
            if (cam == null) return false;
            String mode = cam.optString("cameraMode", "default");
            return "dilink4".equalsIgnoreCase(mode);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Time to wait after launching com.byd.avc before opening our camera. */
    private static final long HAL_WARMUP_DELAY_MS = 4000;

    /** Interval for keep-alive pokes to prevent system from killing com.byd.avc. */
    private static final long KEEP_ALIVE_INTERVAL_MS = 60_000;

    /** The am start command to silently launch com.byd.avc without bringing it to foreground. */
    private static final String[] AVC_LAUNCH_CMD = new String[]{
        "am", "start",
        "--user", "0",
        "-n", "com.byd.avc/.MainActivity",
        "-f", "0x10020000"  // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION
    };

    private volatile Thread keepAliveThread;
    private volatile boolean active = false;

    public AvcHalWarmup() {
    }

    // ==================== One-Shot Warmup ====================

    /**
     * Launches com.byd.avc and blocks for HAL_WARMUP_DELAY_MS.
     * Call this BEFORE opening the camera on ACC ON transitions.
     *
     * This is a blocking call — run it on a background thread.
     *
     * @return true if warmup completed, false if interrupted
     */
    public boolean warmupAndWait() {
        if (shouldSuppressAvc()) {
            logger.info("Skipping AVC warmup — cameraMode=dilink4 (AVC paints "
                + "the red 'calibration failed' chrome on byd_apa firmware; "
                + "esco evicts it, we just don't wake it up).");
            // AVC may already be running from boot. Force-stop it once so
            // it can't keep painting on top of our preview. Idempotent;
            // if it's not running this is a no-op. UID 2000 (shell) has
            // permission to force-stop user apps.
            forceStopAvc();
            return true;
        }
        logger.info("Warming up camera HAL via com.byd.avc (waiting " +
            HAL_WARMUP_DELAY_MS + "ms)...");

        launchAvc();

        try {
            Thread.sleep(HAL_WARMUP_DELAY_MS);
            logger.info("HAL warmup complete — safe to open camera");
            return true;
        } catch (InterruptedException e) {
            logger.warn("HAL warmup interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Keep-Alive Watchdog ====================

    /**
     * Starts the 60-second keep-alive watchdog.
     * Periodically re-launches com.byd.avc to prevent the system from killing it.
     *
     * Only runs while ACC is ON and pipeline is active.
     * Call this after the pipeline has started successfully.
     */
    public synchronized void startKeepAlive() {
        if (shouldSuppressAvc()) {
            logger.info("Skipping AVC keep-alive — cameraMode=dilink4. "
                + "AVC is the painter we want OFF, not propped up.");
            return;
        }
        if (active) {
            logger.info("Keep-alive already running");
            return;
        }

        active = true;
        keepAliveThread = new Thread(() -> {
            logger.info("AVC keep-alive watchdog started (interval=" +
                KEEP_ALIVE_INTERVAL_MS / 1000 + "s)");

            while (active && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(KEEP_ALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                // Double-check conditions before poking
                if (!active) break;

                // Re-poke com.byd.avc to keep the camera HAL alive.
                // Runs regardless of ACC state — when the head unit stays
                // awake during ACC OFF (charging, sentry mode), the system
                // still reaps com.byd.avc and the HAL goes cold. The owning
                // pipeline (CameraDaemon) calls stopKeepAlive() on shutdown.
                logger.info("Keep-alive: re-launching com.byd.avc (accOn=" +
                    AccMonitor.isAccOn() + ")");
                launchAvc();
            }

            logger.info("AVC keep-alive watchdog stopped");
        }, "AvcKeepAlive");

        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    /**
     * Stops the keep-alive watchdog.
     * Call when pipeline stops, ACC goes OFF, or daemon shuts down.
     */
    public synchronized void stopKeepAlive() {
        if (!active) return;

        active = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
        logger.info("AVC keep-alive stopped");
    }

    /**
     * Whether the keep-alive watchdog is currently running.
     */
    public boolean isActive() {
        return active;
    }

    // ==================== Internal ====================

    /**
     * Silently launches com.byd.avc via am start.
     * Runs as UID 2000 (shell) — has permission to launch activities.
     * Uses FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION to avoid
     * bringing it to the foreground or showing any visual disruption.
     */
    private void launchAvc() {
        try {
            Process process = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("am start com.byd.avc exited with code " + exitCode);
            }
        } catch (Exception e) {
            logger.warn("Failed to launch com.byd.avc: " + e.getMessage());
        }
    }

    /**
     * One-shot force-stop of com.byd.avc. Used on dilink4 to prevent the
     * BYD compositor from painting its red 'calibration failed' chrome on
     * top of our AVM preview. Mirrors the practical effect of esco's
     * UsageStatsManager-driven eviction without needing PACKAGE_USAGE_STATS.
     * Best-effort; logs but does not throw.
     */
    private static final String[] AVC_FORCE_STOP_CMD = new String[]{
        "am", "force-stop", "com.byd.avc"
    };

    /** Shared across the 5 AvcHalWarmup instances created during a cold
     *  start (CameraDaemon, RecordingModeManager, two StreamingApiHandler
     *  one-shots). Each pre-camera-open path calls warmupAndWait, which
     *  on dilink4 falls into forceStopAvc. Without this guard we'd shell
     *  out 3+ times in the first second; harmless but noisy and slow. */
    private static final java.util.concurrent.atomic.AtomicBoolean
        forceStoppedThisProcess = new java.util.concurrent.atomic.AtomicBoolean(false);

    private void forceStopAvc() {
        if (!forceStoppedThisProcess.compareAndSet(false, true)) {
            // Already done in this process — skip.
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(AVC_FORCE_STOP_CMD);
            int rc = p.waitFor();
            if (rc == 0) {
                logger.info("Force-stopped com.byd.avc (dilink4 chrome suppressor).");
            } else {
                logger.warn("am force-stop com.byd.avc exited rc=" + rc);
            }
        } catch (Exception e) {
            logger.warn("Failed to force-stop com.byd.avc: " + e.getMessage());
        }
    }
}
