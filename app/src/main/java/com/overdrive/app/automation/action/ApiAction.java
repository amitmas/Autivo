package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.server.HttpServer;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiAction extends BaseAction {
    private static final String TYPE = "api";
    // Regex to match ${variable}
    private static final Pattern p = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Label label;
    private final String description;
    private final String method;
    private final String path;
    private final String body;
    private final List<Type> variables;

    /**
     * An action to send an API event
     * The variables passed in to this will replace placeholders inside the path and body
     * This allows a path like /api/delete/${id} or a body like { "id": "${id}" }
     * Ensure the body can still be parsed as JSON after the variable replacement
     *
     * @param label       The label for this notification with an id and display name
     * @param description The description for this action
     * @param method      The HTTP method e.g. POST or GET
     * @param path        The HTTP endpoint
     * @param body        The HTTP body. Would usually need to be valid JSON
     * @param variables   The variables to concatenate for the payload
     */
    public ApiAction(Label label, String description, String method, String path, String body, Type... variables) {
        this.label = label;
        this.description = description;
        this.method = method;
        this.path = path;
        this.body = body;
        this.variables = List.of(variables);
    }

    /**
     * A string id for this action
     *
     * @return String representing this action
     */
    public String getType() {
        return TYPE;
    }

    /**
     * The label that was stored when this Action was initialized
     *
     * @return The Label for this action
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The description for this action
     * Will be translated using the language files
     *
     * @return The description for this action
     */
    public String getDescription() {
        return Messages.get(description);
    }

    /**
     * The HTTP method that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP method that was stored for this action
     */
    private String getMethod() {
        return method;
    }

    /**
     * The HTTP endpoint that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP endpoint that was stored for this action
     */
    private String getPath() {
        return path;
    }

    /**
     * The HTTP body that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP body that was stored for this action
     */
    private String getBody() {
        return body;
    }

    /**
     * The variables for this action
     *
     * @return The variables for this action
     */
    public List<Type> getVariables() {
        return variables;
    }

    /**
     * Trigger a vehicle control action
     * The variables stored will be concatenated and sent as the control payload
     * <p>
     * This method needs to be updated to implement the sub variable to allow more vehicle controls
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    public void trigger(AutomationAction automationAction) {
        String path = replaceVariables(getPath(), automationAction.getVariables());
        String body = replaceVariables(getBody(), automationAction.getVariables());
        HttpServer server = CameraDaemon.getHttpServer();
        if (server != null && path != null && body != null) {
            // Ignoring the response for now but it contains the full HTTP response
            server.automationApiRequest(getMethod(), path, body);
        } else {
            logger.error("Could not trigger API action (" + getMethod() + "," + path + "," + body + ")");
        }
    }

    /**
     * Replace any variables in the input with the value from the variables map
     * Uses the toString method to convert the variables to a String
     *
     * @param input     The String to replace the variables for
     * @param variables A map of String -> a class which has a toString method
     * @return The string with any variables of format ${variable} replaced
     */
    private String replaceVariables(String input, Map<String, Object> variables) {
        try {
            Matcher m = p.matcher(input);

            StringBuffer result = new StringBuffer();
            while (m.find()) {
                // Keep the variable in the String if it doesn't exist in the map
                String value = variables.getOrDefault(m.group(1), m.group(0)).toString();
                m.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            m.appendTail(result);

            return result.toString();
        } catch (Exception e) {
            logger.error("Failed to replace variables for automation", e);
            return null;
        }
    }
}
