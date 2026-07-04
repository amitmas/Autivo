package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

import org.json.JSONObject;

public class StringType extends BaseType<String> {
    private static final String TYPE = "string";

    private final Label label;
    private final int maxLength;

    /**
     * A string representation
     * Will take a maxLength to limit the size of the string
     *
     * @param label     An id and display name for this int
     * @param maxLength The maximum length this string can be (inclusive)
     */
    public StringType(Label label, int maxLength) {
        this.label = label;
        this.maxLength = maxLength;
    }

    /**
     * The label that was stored when this string was initialized
     *
     * @return The Label for this int
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The maximum length this string can be (inclusive)
     *
     * @return The maximum length this string can be (inclusive)
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * The comparators for this string
     *
     * @return The comparators for this string
     */
    public EnumType getComparators() {
        return StringValue.COMPARATORS;
    }

    /**
     * Check if the value length is less than maxLength
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(String value) {
        if (value == null) return false;

        return value.length() <= getMaxLength();
    }

    /**
     * Create a JSON representation of this string to display in the frontend
     * Will contain the maxLength
     *
     * @return JSON representation of this string
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
            json.put("maxLength", getMaxLength());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
