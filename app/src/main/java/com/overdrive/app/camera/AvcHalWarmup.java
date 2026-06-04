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
 * <p><b>DiLink 4 (June 2026 reversal).</b> Empirically the AVMCamera HAL on
 * byd_apa only delivers mosaic content into the panoramic producer surface
 * when ANOTHER consumer is attached to the same vendor.byd.avm daemon —
 * com.byd.avc is exactly that consumer. Killing AVC made post-ACC-OFF frames
 * go all-zero (Frame 1 size dropped from ~80 KB to ~350 B). We now COOPERATE
 * with AVC on dilink4 like esco does: warm it on entry AND keep-alive ticks
 * keep it propped up. The red "calibration failed" chrome that AVC paints
 * is suppressed cosmetically by the GL red-mask shader (already in place),
 * so we no longer need to evict AVC at all. Legacy cars (90% of fleet)
 * unchanged.
 */
public class AvcHalWarmup {

    private static final String TAG = "AvcHalWarmup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * True when the active camera mode is "dilink4". As of June 2026 we no
     * longer suppress AVC on dilink4 — we cooperate with it. Kept as a
     * boolean predicate because callers may still want to differentiate
     * (e.g. logging tags). Reads the unified config fresh; cheap (single
     * JSON load) and called only at warmup/keep-alive entry points.
     */
    private static boolean isDilink4Mode() {
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
        boolean dilink4 = isDilink4Mode();
        if (dilink4) {
            // ESCO-PARITY: esco does NOT launch com.byd.avc anywhere in its
            // panorama-camera flow. The 4 s blocking sleep + `am start
            // com.byd.avc/.MainActivity` was OverDrive-specific and
            // suspected of stealing the HAL's mosaic mode (PANORAMA_OUTPUT_STATE=7).
            // Skip the warmup entirely on dilink4. ensureAvcAlive() (pidof +
            // conditional am start, no sleep) still runs separately for the
            // multi-consumer keep-alive case; that's the closest behaviour
            // esco's environment naturally provides without an explicit
            // launch.
            logger.info("dilink4: skipping warmupAndWait (esco-parity — esco never launches com.byd.avc explicitly)");
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

                // ESCO-PARITY: on dilink4 do NOT launch com.byd.avc.
                // esco never launches AVC. ensureAvcAlive() (pidof check +
                // optional non-launching am start) is sufficient as a
                // presence probe; an explicit launch is suspected of
                // stealing the HAL's mosaic mode.
                if (isDilink4Mode()) {
                    logger.info("Keep-alive tick (dilink4): skipping AVC re-launch (esco-parity)");
                    continue;
                }
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

    // ==================== AVC KEEP-ALIVE (DILINK 4) ====================
    //
    // June 2026 reversal: on byd_apa firmware com.byd.avc is a co-consumer
    // of the vendor.byd.avm HAL daemon. Its presence is what keeps the
    // AVM mosaic blender feeding the panoramic producer surface; remove
    // it and our frames go all-zero. So instead of evicting AVC we
    // PROP IT UP — periodic pidof; if absent, am start.
    //
    // ensureAvcAlive() is intended to be called from a long-running
    // keep-alive tick (e.g. AccSentry's 10 s SystemKeepAlive) when the
    // active camera mode is dilink4. Static so any caller can hit it
    // without owning an AvcHalWarmup instance. Idempotent for concurrent
    // callers — `am start` on an already-running activity is a no-op
    // beyond an intent broadcast.

    /**
     * Returns the current pid of com.byd.avc, or -1 if not running / probe
     * failed. Uses `pidof` which is available in toybox on all BYD images
     * we've seen; falls back to -1 silently on parse failure.
     */
    private static int probeAvcPid() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "pidof", "com.byd.avc" });
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            try { p.waitFor(); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (line == null) return -1;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) return -1;
            String[] parts = trimmed.split("\\s+");
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /**
     * Periodic AVC keep-alive — re-launch com.byd.avc if it's currently
     * NOT running. Returns true if a launch was issued, false if AVC was
     * already alive.
     *
     * <p>Caller is responsible for gating on cameraMode=dilink4 — this
     * method assumes you've decided AVC must stay up.
     */
    public static boolean ensureAvcAlive() {
        // ESCO-PARITY: esco never launches com.byd.avc. On dilink4 we make
        // this a presence-check-only — no `am start`, no relaunch. If AVC
        // is dead, it's dead; we report state and move on. The HAL on
        // calibrated dilink4 firmware delivers mosaic frames without AVC
        // being a live consumer. Suspect cause of black frames in field
        // logs is exactly the AVC `am start` flipping HAL out of mosaic
        // mode (PANORAMA_OUTPUT_STATE=7).
        if (isDilink4Mode()) {
            int pid = probeAvcPid();
            if (pid > 0) {
                return false;
            }
            logger.info("AVC keep-alive (dilink4): pidof returned 0 — NOT relaunching (esco-parity)");
            return false;
        }

        // Legacy fleet: original behaviour — relaunch if absent.
        int pid = probeAvcPid();
        if (pid > 0) {
            return false;
        }
        try {
            Process p = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            int rc = p.waitFor();
            if (rc == 0) {
                logger.info("AVC keep-alive: re-launched com.byd.avc "
                    + "(was not running)");
                return true;
            } else {
                logger.warn("AVC keep-alive: am start exited rc=" + rc);
            }
        } catch (Exception e) {
            logger.warn("AVC keep-alive: " + e.getMessage());
        }
        return false;
    }
}
