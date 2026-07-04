package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventCondition {
    private static final String TYPE = "event";

    private final Label label;
    private final String description;
    private final Type value;
    private final List<EnumType> variables;

    /**
     * A condition based on an event happening
     * This does not have an interface as all conditions should be event driven
     * The actions should not hold up other code as they are pulled from a queue and actioned in a thread
     * The variables have been restricted to Enum types to prevent a large state map which may degrade performance
     *
     * @param label       The label for this condition with an id and display name
     * @param description The description for this condition
     * @param value       The type with constraints for the potential state values
     * @param variables   The variables that will also be set in the state. This allows extra options such as area for windows
     */
    public EventCondition(Label label, String description, Type value, EnumType... variables) {
        this.label = label;
        this.description = description;
        this.value = value;
        this.variables = List.of(variables);
    }

    /**
     * The label that was stored when this Condition was initialized
     *
     * @return The Label for this condition
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The description for this condition
     * Will be translated using the language files
     *
     * @return The description for this condition
     */
    public String getDescription() {
        return Messages.get(description);
    }

    /**
     * The value type for this condition
     * It will have restrictions such as min and max for numbers or options for an enum
     *
     * @return The value type for this condition
     */
    public Type getValue() {
        return value;
    }

    /**
     * The variables for this condition
     *
     * @return These will be enums and need to match events passed in to Automations.update
     */
    public List<EnumType> getVariables() {
        return variables;
    }

    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this condition
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
            JSONArray variables = new JSONArray();
            for (Type variable : getVariables()) {
                variables.put(variable.toJson());
            }
            json.put("variables", variables);
            json.put("description", getDescription());
            json.put("comparator", getValue().getComparators().toJson());
            json.put("value", getValue().toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Create an EventData instance by validating it with the config for this condition
     * This can be used for triggers as it has no comparator or value
     *
     * @return EventData instance
     */
    public EventData eventData(JSONObject input) {
        try {
            String type = getLabel().getId();
            Map<String, String> variables = new HashMap<>();
            JSONObject variablesJson = input.optJSONObject("variables");
            for (EnumType variable : getVariables()) {
                String key = variable.getLabel().getId();
                String value = variablesJson.getString(key);
                if (variable.isValid(value)) {
                    variables.put(key, value);
                } else {
                    return null;
                }
            }

            return new EventData(type, variables);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Create an AutomationCondition instance by validating it with the config for this condition
     * This will create the EventData that is also used for a trigger and add the comparator and value
     *
     * @return AutomationCondition instance
     */
    public AutomationCondition automationCondition(JSONObject input) {
        try {
            EventData eventData = eventData(input);
            if (eventData == null) return null;

            String comparator = input.getString("comparator");
            if (!getValue().isValidComparator(comparator)) return null;

            Object value = input.get("value");
            if (!getValue().isValid(value)) return null;

            return new AutomationCondition(eventData, comparator, value);
        } catch (Exception e) {
            return null;
        }
    }
}
