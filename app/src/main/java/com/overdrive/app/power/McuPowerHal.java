package com.overdrive.app.power;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * MCU power-rail control via BYD HAL device-event writes.
 *
 * <p>Two surfaces:
 * <ol>
 *   <li><b>MCU sleep/wake</b> — {@code BYDAutoPowerDevice.set(new int[]{-1442840502}, BYDAutoEventValue.intValue=0/1)}.
 *       value=0 → request MCU sleep; value=1 → request MCU wake. The MCU
 *       gates the DC-DC converter that runs the head-unit + camera ISP rails;
 *       letting it sleep when the 12V is healthy is what makes ACC=OFF
 *       surveillance not drain the battery in 4 hours.</li>
 *   <li><b>Sentry-mode MCU</b> — {@code BYDAutoSpecialDevice.set(new int[]{1901}, …)} +
 *       {@code set(new int[]{1902}, …)}. Sleep: 1901→0, 1902→2.
 *       Wake: 1901→1, 1902→1.</li>
 * </ol>
 *
 * <p>All device handles are resolved process-locally from the caller-supplied
 * {@code appContext}. Reaching into {@code BydDataCollector.getInstance()}
 * doesn't work cross-process — that singleton is per-process and is only
 * initialised in cam_daemon. Other daemons (e.g. acc_sentry) get a fresh
 * empty collector.
 *
 * <p>Class FQN verified at runtime on DiLink 3.0:
 * <ul>
 *   <li>{@code android.hardware.bydauto.power.BYDAutoPowerDevice}</li>
 *   <li>{@code android.hardware.bydauto.special.BYDAutoSpecialDevice} —
 *       NOTE the {@code .bydauto.} segment, not the bare {@code .special.}
 *       guess from the sibling-app trace.</li>
 * </ul>
 *
 * <p>All calls are best-effort. When the underlying HAL/class is missing on a
 * trim, the call returns false and logs at info level. Caller must not rely
 * on success — guard surveillance behaviour with separate state.
 */
public final class McuPowerHal {

    private static final String TAG = "McuPowerHal";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Event-type constant for MCU sleep/wake writes. */
    public static final int EVENT_MCU_SLEEP_WAKE = -1442840502;

    /** Sentry-mode special-device feature keys. */
    public static final int SENTRY_KEY_1 = 1901;
    public static final int SENTRY_KEY_2 = 1902;

    /** Power-device class FQN. */
    private static final String POWER_CLASS = "android.hardware.bydauto.power.BYDAutoPowerDevice";

    /** Special-device class FQN — verified runtime FQN on DiLink 3.0. */
    private static final String SPECIAL_CLASS = "android.hardware.bydauto.special.BYDAutoSpecialDevice";

    /**
     * Process-local context handed in by whichever daemon boots us. Required
     * for both power and special device {@code getInstance} calls.
     */
    private static volatile android.content.Context appContext;

    private static volatile Object cachedPowerDevice;
    private static volatile Object cachedSpecialDevice;

    private McuPowerHal() {}

    /**
     * Daemons set this to their own (PermissionBypass-wrapped) appContext.
     * Must be called before any {@code request*} call — without it every
     * request silently no-ops because the device classes need a Context.
     */
    public static void setAppContext(android.content.Context ctx) {
        appContext = ctx;
        // Drop cached handles — they were resolved against the previous
        // (possibly null) context and may be unusable.
        cachedPowerDevice = null;
        cachedSpecialDevice = null;
    }

    // ── MCU sleep/wake on BYDAutoPowerDevice ──────────────────────────

    /** Request MCU sleep ({@code EVENT_MCU_SLEEP_WAKE} ← 0). */
    public static boolean requestMcuSleep() {
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuSleep: power device unresolved — no-op");
            return false;
        }
        boolean ok = BydDeviceHelper.sendSetCommand(device, EVENT_MCU_SLEEP_WAKE, 0);
        logger.info("requestMcuSleep -> " + ok);
        return ok;
    }

    /** Request MCU wake ({@code EVENT_MCU_SLEEP_WAKE} ← 1). */
    public static boolean requestMcuWake() {
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuWake: power device unresolved — no-op");
            return false;
        }
        boolean ok = BydDeviceHelper.sendSetCommand(device, EVENT_MCU_SLEEP_WAKE, 1);
        logger.info("requestMcuWake -> " + ok);
        return ok;
    }

    // ── Sentry-mode keys on BYDAutoSpecialDevice ─────────────────────

    /** Request sentry-mode sleep — writes 1901←0 then 1902←2. */
    public static boolean requestSentrySleep() {
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentrySleep: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = BydDeviceHelper.sendSetCommand(device, SENTRY_KEY_1, 0);
        boolean b = BydDeviceHelper.sendSetCommand(device, SENTRY_KEY_2, 2);
        logger.info("requestSentrySleep 1901<-0=" + a + " 1902<-2=" + b);
        return a && b;
    }

    /** Request sentry-mode wake — writes 1901←1 then 1902←1. */
    public static boolean requestSentryWake() {
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentryWake: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = BydDeviceHelper.sendSetCommand(device, SENTRY_KEY_1, 1);
        boolean b = BydDeviceHelper.sendSetCommand(device, SENTRY_KEY_2, 1);
        logger.info("requestSentryWake 1901<-1=" + a + " 1902<-1=" + b);
        return a && b;
    }

    // ── Internals ────────────────────────────────────────────────────

    private static Object resolvePowerDevice() {
        if (cachedPowerDevice != null) return cachedPowerDevice;
        if (appContext == null) {
            logger.debug("resolvePowerDevice: no appContext — call setAppContext first");
            return null;
        }
        try {
            Class<?> cls = Class.forName(POWER_CLASS);
            Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
            cachedPowerDevice = getInstance.invoke(null, appContext);
            if (cachedPowerDevice != null) {
                logger.info("resolvePowerDevice: " + cachedPowerDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("BYDAutoPowerDevice.getInstance failed: " + t.getMessage());
        }
        return cachedPowerDevice;
    }

    private static Object resolveSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) {
            logger.debug("resolveSpecialDevice: no appContext — call setAppContext first");
            return null;
        }
        try {
            Class<?> cls = Class.forName(SPECIAL_CLASS);
            Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
            cachedSpecialDevice = getInstance.invoke(null, appContext);
            if (cachedSpecialDevice != null) {
                logger.info("resolveSpecialDevice: " + cachedSpecialDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("BYDAutoSpecialDevice.getInstance failed: " + t.getMessage());
        }
        return cachedSpecialDevice;
    }
}
