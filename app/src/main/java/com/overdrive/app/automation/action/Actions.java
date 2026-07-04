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
                new EnumType(
                        new Label("payload", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("medium", "automation.high"))));
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
