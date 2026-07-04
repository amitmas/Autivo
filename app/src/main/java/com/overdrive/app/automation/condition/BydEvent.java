package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.monitor.GearMonitor;

import java.util.Arrays;
import java.util.Map;

public class BydEvent {
    // Stored as static variables to prevent the EventData objects being created repeatedly
    public static final EventData POWER = new EventData("power");
    public static final EventData GEAR = new EventData("gear");
    public static final EventData WINDOW_LF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lf"));
    public static final EventData WINDOW_RF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rf"));
    public static final EventData WINDOW_LR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lr"));
    public static final EventData WINDOW_RR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunshade"));
    public static final EventData WINDOW_LF = new EventData("windowState", Map.of("area", "lf"));
    public static final EventData WINDOW_RF = new EventData("windowState", Map.of("area", "rf"));
    public static final EventData WINDOW_LR = new EventData("windowState", Map.of("area", "lr"));
    public static final EventData WINDOW_RR = new EventData("windowState", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF = new EventData("windowState", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE = new EventData("windowState", Map.of("area", "sunshade"));
    public static final EventData WINDOW_ALL = new EventData("windowState", Map.of("area", "all"));
    public static final EventData BATTERY_LEVEL = new EventData("batteryLevel");
    public static final EventData ESTIMATED_RANGE = new EventData("estimatedRange");
    public static final EventData LIGHTS_LOW_BEAM = new EventData("lights", Map.of("area", "lowBeam"));
    public static final EventData LIGHTS_HIGH_BEAM = new EventData("lights", Map.of("area", "highBeam"));
    public static final EventData LIGHTS_HAZARD = new EventData("lights", Map.of("area", "hazard"));
    public static final EventData LIGHTS_DRL = new EventData("lights", Map.of("area", "drl"));
    public static final EventData SLW = new EventData("slw");
    public static final EventData SEAT_HEAT_DRIVER = new EventData("seatClimate", Map.of("type", "heat", "area", "driver"));
    public static final EventData SEAT_HEAT_PASSENGER = new EventData("seatClimate", Map.of("type", "heat", "area", "passenger"));
    public static final EventData SEAT_COOL_DRIVER = new EventData("seatClimate", Map.of("type", "cool", "area", "driver"));
    public static final EventData SEAT_COOL_PASSENGER = new EventData("seatClimate", Map.of("type", "cool", "area", "passenger"));
    public static final EventData AC = new EventData("ac");
    public static final EventData TEMPERATURE = new EventData("temperature");

    private BydEvent() {}

    /**
     * This class is created to make gathering events easier
     * As the BydVehicleData is not updated often, this does not affect app performance
     * If this changes in the future, Automations.update should be called directly when an event is triggered
     * This would allow it to update a single variable instead of updating all variables when a single value changes
     *
     * @param data The current BydVehicleData with the vehicle state
     */
    public static void bydEvent(BydVehicleData data) {
        // Do nothing when no automations enabled
        if (Automations.isDisabled()) return;

        Automations.update(POWER, BodyworkConstants.powerLevelToString(data.powerLevel).toLowerCase());
        Automations.update(GEAR, GearMonitor.gearToString(data.gearMode).toLowerCase());
        Automations.update(WINDOW_LF_PERCENT, data.windowOpenPercent[0]);
        Automations.update(WINDOW_LF, data.windowOpenPercent[0] == 0 ? "closed" : "open");
        Automations.update(WINDOW_RF_PERCENT, data.windowOpenPercent[1]);
        Automations.update(WINDOW_RF, data.windowOpenPercent[1] == 0 ? "closed" : "open");
        Automations.update(WINDOW_LR_PERCENT, data.windowOpenPercent[2]);
        Automations.update(WINDOW_LR, data.windowOpenPercent[2] == 0 ? "closed" : "open");
        Automations.update(WINDOW_RR_PERCENT, data.windowOpenPercent[3]);
        Automations.update(WINDOW_RR, data.windowOpenPercent[3] == 0 ? "closed" : "open");
        if (data.windowOpenPercent.length > 4) Automations.update(WINDOW_SUNROOF_PERCENT, data.windowOpenPercent[4]);
        if (data.windowOpenPercent.length > 4) Automations.update(WINDOW_SUNROOF, data.windowOpenPercent[4] == 0 ? "closed" : "open");
        if (data.windowOpenPercent.length > 5) Automations.update(WINDOW_SUNSHADE_PERCENT, data.windowOpenPercent[5]);
        if (data.windowOpenPercent.length > 5) Automations.update(WINDOW_SUNSHADE, data.windowOpenPercent[5] == 0 ? "closed" : "open");

        if (Arrays.stream(data.windowOpenPercent).allMatch(percent -> percent == 0)) {
            Automations.update(WINDOW_ALL, "closed");
        } else {
            // Set the state to open when any window is open so it can be used as a shortcut to see that all are closed
            Automations.update(WINDOW_ALL, "open");
        }
        if (!Double.isNaN(data.socPercent)) Automations.update(BATTERY_LEVEL, (int) data.socPercent);
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.elecRangeKm);
        } else if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.bodyworkRangeKm);
        }
        Automations.update(LIGHTS_LOW_BEAM, data.lowBeam ? "on" : "off");
        Automations.update(LIGHTS_HIGH_BEAM, data.highBeam ? "on" : "off");
        Automations.update(LIGHTS_HAZARD, data.hazard ? "on" : "off");
        Automations.update(LIGHTS_DRL, data.dayTimeLight ? "on" : "off");
        Automations.update(SLW, data.speedLimitWarning ? "on" : "off");
        if (data.seatHeat != null) {
            if (data.seatHeat.length > 0) Automations.update(SEAT_HEAT_DRIVER, seatClimateToString(data.seatHeat[0]));
            if (data.seatHeat.length > 1) Automations.update(SEAT_HEAT_PASSENGER, seatClimateToString(data.seatHeat[1]));
        }
        if (data.seatCool != null) {
            if (data.seatCool.length > 0) Automations.update(SEAT_COOL_DRIVER, seatClimateToString(data.seatCool[0]));
            if (data.seatCool.length > 1) Automations.update(SEAT_COOL_PASSENGER, seatClimateToString(data.seatCool[1]));
        }
        boolean poweredOn = data.powerLevel >= 2;
        Automations.update(AC, (poweredOn && data.acStartState == 1) ? "on" : "off");
        if (!Double.isNaN(data.insideTempC)) Automations.update(TEMPERATURE, (int) data.insideTempC);
    }

    private static String seatClimateToString(int level) {
        switch (level) {
            case 0:
                return "off";
            case 1:
                return "low";
            case 2:
                return "high";
            default:
                return "unknown";
        }
    }
}
