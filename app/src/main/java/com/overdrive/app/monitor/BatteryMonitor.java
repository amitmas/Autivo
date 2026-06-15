package com.overdrive.app.monitor;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

/**
 * Battery Monitor - reads battery info from VehicleDataMonitor.
 *
 * This class provides battery data to the HTTP status API for HTML UI display.
 *
 * <p>It runs INSIDE the CameraDaemon process (HttpServer.sendStatus calls
 * {@link #getBatteryInfo()} on a daemon HTTP worker thread). VehicleDataMonitor —
 * the source of truth — is a singleton in that SAME process. Previously this
 * opened a TCP socket to 127.0.0.1:19877 (SurveillanceIpcServer, also in this
 * process) and sent GET_VEHICLE_DATA, i.e. a full TCP connect+handshake+teardown
 * just to call a same-process singleton whose handler is literally
 * {@code VehicleDataMonitor.getInstance().getAllData()}. We now call that
 * directly — no socket, no loopback stack, no IPC server thread.
 */
public class BatteryMonitor {

    private static volatile double lastBatteryVoltage = 0.0;
    private static volatile String lastBatteryLevel = "UNKNOWN";
    private static volatile double lastBatterySoc = 0.0;
    private static volatile long lastBatteryUpdate = 0;

    /**
     * Derive battery level from actual voltage when BYD API returns INVALID.
     * 12V automotive battery voltage ranges:
     * - < 11.8V: LOW (battery needs charging)
     * - 11.8V - 14.8V: NORMAL (healthy range, 14.4V+ when charging)
     * - > 14.8V: Overcharging warning
     */
    private static String deriveLevelFromVoltage(double voltage) {
        if (voltage <= 0) return "UNKNOWN";
        if (voltage < 11.8) return "LOW";
        if (voltage >= 11.8 && voltage <= 14.8) return "NORMAL";
        return "HIGH";  // Overcharging
    }

    /**
     * Fetch battery info from SurveillanceIpcServer.
     */
    public static void fetchBatteryInfo() {
        try {
            // Direct same-process read — VehicleDataMonitor is the singleton the
            // SurveillanceIpcServer's GET_VEHICLE_DATA handler delegates to, and
            // we run in the same process, so skip the loopback socket entirely.
            JSONObject data =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance().getAllData();

            if (data != null) {
                // Get battery power voltage (actual volts)
                JSONObject batteryPower = data.optJSONObject("batteryPower");
                if (batteryPower != null) {
                    lastBatteryVoltage = batteryPower.optDouble("voltageVolts", 0.0);
                }

                // Get battery voltage level (LOW/NORMAL/INVALID)
                JSONObject batteryVoltage = data.optJSONObject("batteryVoltage");
                if (batteryVoltage != null) {
                    String apiLevel = batteryVoltage.optString("levelName", "UNKNOWN");
                    // If BYD API returns INVALID, derive level from actual voltage
                    if ("INVALID".equals(apiLevel) || "UNKNOWN".equals(apiLevel)) {
                        lastBatteryLevel = deriveLevelFromVoltage(lastBatteryVoltage);
                    } else {
                        lastBatteryLevel = apiLevel;
                    }
                } else {
                    // No API data, derive from voltage
                    lastBatteryLevel = deriveLevelFromVoltage(lastBatteryVoltage);
                }

                // Get battery SOC percentage
                JSONObject batterySoc = data.optJSONObject("batterySoc");
                if (batterySoc != null) {
                    lastBatterySoc = batterySoc.optDouble("socPercent", 0.0);
                }

                lastBatteryUpdate = System.currentTimeMillis();
                CameraDaemon.log("Battery updated: " + lastBatteryVoltage + "V (" + lastBatteryLevel + "), SOC: " + lastBatterySoc + "%");
            }
        } catch (Exception e) {
            CameraDaemon.log("Battery info fetch: " + e.getMessage());
        }
    }

    /**
     * Get battery info as JSON object.
     * Fetches fresh data if stale (> 30 seconds).
     */
    public static JSONObject getBatteryInfo() {
        if (System.currentTimeMillis() - lastBatteryUpdate > 30000) {
            fetchBatteryInfo();
        }
        
        JSONObject battery = new JSONObject();
        try {
            battery.put("voltage", lastBatteryVoltage);
            battery.put("level", lastBatteryLevel);
            battery.put("soc", lastBatterySoc);
            battery.put("lastUpdate", lastBatteryUpdate);
        } catch (Exception e) {
            // Ignore
        }
        return battery;
    }
}
