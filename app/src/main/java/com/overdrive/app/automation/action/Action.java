package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

public interface Action {
    /**
     * Get the label of this action with an id and display value
     *
     * @return The label for this type
     */
    Label getLabel();

    /**
     * Trigger the action using the parameters from an AutomationAction
     * The automation action will have been built using the fromJson method in this interface
     * All the variables required to trigger this action should be set in the fromJson method
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    void trigger(AutomationAction automationAction);


    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this type
     */
    JSONObject toJson();

    /**
     * An automation action with the id of this instance and any variables needed for the action trigger
     *
     * @param input The JSON passed from the frontend
     * @return An AutomationAction that can later be used to trigger this action
     */
    AutomationAction fromJson(JSONObject input);
}
