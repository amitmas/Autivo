package com.overdrive.app.automation;

import org.json.JSONObject;

import java.util.Map;

public class AutomationAction {
    private final String type;
    private final Map<String, Object> variables;

    /**
     * An action representation for a specific automation
     * The variables can have any type as they used within the trigger for an action
     *
     * @param type      The id of the action this is used for
     * @param variables The variables that are needed to run the action
     */
    public AutomationAction(String type, Map<String, Object> variables) {
        this.type = type;
        this.variables = variables;
    }

    /**
     * The id of the action this is used for
     *
     * @return The id of the action this is used for
     */
    public String getType() {
        return type;
    }

    /**
     * The variables that are needed to run the action
     *
     * @return The variables that are needed to run the action
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * Create a JSON object which can be stored and loaded for this action
     *
     * @return JSON representation of this action
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            json.put("type", getType());
            JSONObject variables = new JSONObject();
            for (Map.Entry<String, Object> variable : getVariables().entrySet()) {
                variables.put(variable.getKey(), variable.getValue());
            }
            json.put("variables", variables);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
