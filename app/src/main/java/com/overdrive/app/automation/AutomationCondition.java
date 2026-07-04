package com.overdrive.app.automation;

import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONObject;

public class AutomationCondition {
    private final EventData eventData;
    private final String comparator;
    private final Object value;

    /**
     * A condition representation for a specific automation
     * The value can have any type as it is compared from an event with a specific type
     *
     * @param eventData  The variables for an event which would be compared to this condition
     * @param comparator The id of a comparator to use to compare the event and this value
     * @param value      The value to compare to an event
     */
    public AutomationCondition(EventData eventData, String comparator, Object value) {
        this.eventData = eventData;
        this.comparator = comparator;
        this.value = value;
    }

    /**
     * The variables for an event which would be compared to this condition
     *
     * @return The variables for an event which would be compared to this condition
     */
    public EventData getEventData() {
        return eventData;
    }

    /**
     * The id of a comparator to use to compare the event and this value
     * See the getComparators() method of values or types
     *
     * @return The id of a comparator to use to compare the event and this value
     */
    public String getComparator() {
        return comparator;
    }

    /**
     * The value to compare to an event
     *
     * @return The value to compare to an event
     */
    public Object getValue() {
        return value;
    }

    /**
     * Compare this value using the stored comparator
     * Checks for null response from the compare method to ensure the comparator and values are valid
     *
     * @param value The value to compare with this condition
     * @return true if the comparison was successful, false otherwise
     */
    public boolean compare(Value value) {
        // Compare to true as it will be null when not a valid comparison
        return Boolean.TRUE.equals(value.compare(this.value, comparator));
    }

    /**
     * Create a JSON object which can be stored and loaded for this condition
     *
     * @return JSON representation of this condition
     */
    public JSONObject toJson() {
        JSONObject json = getEventData().toJson();

        try {
            json.put("comparator", getComparator());
            json.put("value", getValue());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
