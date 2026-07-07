package com.overdrive.app.automation.action;

import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class Actions {
    // Create LinkedHashMap to maintain the insertion order of actions to display in the frontend
    private final Map<String, Action> actions = new LinkedHashMap<>();

    /**
     * Initialize actions list with actions that can be selected
     */
    public Actions() {
        addAction(new NotificationAction(new Label("notification", "automation.send_notification"), "automation.send_notification_description"));
        addAction(new VehicleControlAction(
                new Label("adas_slw", "automation.set_slw"), "automation.set_slw_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new VehicleControlAction(
                new Label("drl", "automation.set_drl"), "automation.set_drl_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new VehicleControlAction(
                new Label("sunshade", "automation.set_sunshade"),
                "automation.set_sunshade_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunroof", "automation.set_sunroof"),
                "automation.set_sunroof_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("seat", "automation.seat_climate"),
                "automation.set_seat_climate_description",
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("vent", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger")),
                // Seat climate is 3-level hardware (off/low/high); the SDK write path collapses any
                // "medium" onto high, so exposing only these three keeps the action honest and
                // symmetric with the seatClimate condition enum.
                new EnumType(
                        new Label("payload", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high"))));
        // Drive / energy modes — same catalog entities the keymap and MQTT use.
        // The Label id must match the VehicleControlCatalog key so
        // VehicleControlAction.trigger resolves it.
        addAction(new VehicleControlAction(
                new Label("drive_mode", "automation.set_drive_mode"), "automation.set_drive_mode_description",
                // Operation mode has exactly two SDK values: ENERGY_OPERATION_ECONOMY(1)
                // and ENERGY_OPERATION_SPORT(2). "normal"/"snow" are NOT operation modes
                // (snow is a separate road-surface axis), so they are not offered — a
                // binding to them would send an invalid value the HAL rejects.
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("eco", "automation.mode_eco"),
                        new Label("sport", "automation.mode_sport"))));
        addAction(new VehicleControlAction(
                new Label("powertrain_mode", "automation.set_powertrain_mode"), "automation.set_powertrain_mode_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("ev", "automation.mode_ev"),
                        new Label("hev", "automation.mode_hev"))));
        addAction(new VehicleControlAction(
                new Label("regen_level", "automation.set_regen"), "automation.set_regen_description",
                new EnumType(new Label("payload", "automation.level"),
                        new Label("standard", "automation.regen_standard"),
                        new Label("high", "automation.regen_high"))));
        addAction(new VehicleControlAction(
                new Label("steering_mode", "automation.set_steering"), "automation.set_steering_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.steering_comfort"),
                        new Label("sport", "automation.steering_sport"))));
        // Shell command — the free-text StringType variable is defined inside
        // ShellAction. Autonomous exec, so it self-gates on the dedicated
        // automation.allowShell flag (toggle on the Automations page) at fire
        // time (off → logged no-op). Registered last so curated actions lead.
        addAction(new ShellAction(
                new Label("shell", "automation.run_shell"), "automation.run_shell_description"));
    }

    /**
     * Add an action to the map
     * Stored as a map to prevent duplicates
     *
     * @param action The Action to store
     */
    private void addAction(Action action) {
        actions.put(action.getLabel().getId(), action);
    }

    /**
     * Get a stored action
     *
     * @param key The id of the action
     * @return The requested action
     */
    public Action getAction(String key) {
        return actions.get(key);
    }

    /**
     * A JSON representation of the schema for actions
     * All automations require at least 1 action so the required key is set to 1
     *
     * @return A JSON representation of the Actions schema
     */
    public JSONObject toJson() {
        JSONObject actions = new Label("actions", "Then").toJson();

        try {
            actions.put(
                    "description", Messages.get("automation.actions_description"));
            JSONArray actionsList = new JSONArray();
            for (Action action : this.actions.values()) {
                actionsList.put(action.toJson());
            }
            actions.put("options", actionsList);
            actions.put("required", 1);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return actions;
    }
}
