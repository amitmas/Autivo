package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Automations {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final File AUTOMATION_HOME = new File("/data/local/tmp/.automations");
    private static final File AUTOMATION_CONFIG = new File(AUTOMATION_HOME, "config.json");
    private static final Map<EventData, Value> state = new ConcurrentHashMap<>();
    private static final Conditions conditions = new Conditions();
    private static final Type delay = new IntType(new Label("delay", Messages.get("automation.delay")), 0, 86400);
    private static final Actions actions = new Actions();
    private static final Map<String, Automation> automations = new ConcurrentHashMap<>();

    static {
        // Load config from the file at startup
        loadFromFile();
    }

    private Automations() {}

    /**
     * Get a condition schema with a specific key
     *
     * @param key The key for a condition
     * @return The condition schema for that key
     */
    public static EventCondition getCondition(String key) {
        return conditions.getCondition(key);
    }

    /**
     * Get an action schema with a specific key
     *
     * @param key The key for an action
     * @return The action schema for that key
     */
    public static Action getAction(String key) {
        return actions.getAction(key);
    }

    /**
     * Check whether the delay is an allowed value
     *
     * @param seconds The number of seconds to delay the actions
     * @return true if it is valid, false otherwise
     */
    public static boolean isValidDelay(int seconds) {
        return delay.isValid(seconds);
    }

    /**
     * Whether automations are disabled
     * It will be disabled if there are no automation or if all the available automations are disabled
     *
     * @return Whether the automation feature is enabled
     */
    public static boolean isDisabled() {
        return automations.isEmpty() || automations.values().stream().allMatch(Automation::isDisabled);
    }

    /**
     * Create or update an automation
     * Will use a UUID for new automations
     *
     * @param id         The id of an existing automation or null if a new automation is needed
     * @param automation The automation to add to the map
     */
    public static void updateAutomation(String id, Automation automation) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        automations.put(id, automation);
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info("Updated automation: " + id);
    }

    /**
     * Create or update an automation from a JSON representation
     *
     * @param id   The id for this automation or null if a new automation is needed
     * @param json The JSON representation of this automation
     * @return true if successfully created/updated, false otherwise
     */
    public static boolean updateAutomation(String id, JSONObject json) {
        Automation automation = Automation.fromJson(json);
        if (automation == null) return false;
        updateAutomation(id, automation);
        return true;
    }

    /**
     * Delete an automation with a specific id
     *
     * @param id The id of the automation to delete
     * @return true if successfully deleted
     */
    public static boolean deleteAutomation(String id) {
        automations.remove(id);
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info("Removed automation: " + id);
        return true;
    }

    /**
     * Disable an automation
     *
     * @param id       The id of the automation to disable
     * @param disabled true if it should be disabled, false otherwise
     * @return true if successfully disabled, false otherwise
     */
    public static boolean disableAutomation(String id, boolean disabled) {
        Automation automation = automations.get(id);
        if (automation == null) return false;
        automation.setDisabled(disabled);
        saveToFile();
        AutomationQueue.checkWorkerState();
        logger.info((disabled ? "Disabled" : "Enabled") + " automation: " + id);
        return true;
    }

    /**
     * The schema containing allowed values and descriptions for an automation
     *
     * @return The JSON schema for an automation
     */
    public static JSONArray schemaJson() {
        JSONArray json = conditions.toJson();

        try {
            JSONObject delayJson = delay.toJson();
            delayJson.put(
                    "description", Messages.get("automation.delay_description"));
            json.put(delayJson);
            json.put(actions.toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * The JSON for all the stored automations
     * Can be stored to load later
     *
     * @return JSON for all the stored automations
     */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                json.put(automation.getKey(), automation.getValue().toJson());
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Persist the automations to a file
     */
    public static void saveToFile() {
        if (!AUTOMATION_HOME.exists()) AUTOMATION_HOME.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(AUTOMATION_CONFIG)) {
            fos.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
            logger.info("Saved " + automations.size() + " Automations to " + AUTOMATION_CONFIG);
        } catch (IOException e) {
            logger.error("Failed to save automations to file");
        }
    }

    /**
     * Load persisted automations from the file
     */
    public static void loadFromFile() {
        if (!AUTOMATION_CONFIG.exists()) return;
        try (FileInputStream fis = new FileInputStream(AUTOMATION_CONFIG)) {
            byte[] bytes = new byte[(int) AUTOMATION_CONFIG.length()];
            fis.read(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Automation automation = Automation.fromJson(json.optJSONObject(key));
                if (automation != null) automations.put(key, automation);
            }
            logger.info("Loaded " + automations.size() + " Automations from " + AUTOMATION_CONFIG);
        } catch (Exception e) {
            logger.error("Failed to load automations from file");
        }
    }

    /**
     * Method to call when an event caused a value in the state to change
     * Will check all automations which contain this event as a trigger
     * If the previous value is unknown, the event will not be triggered as the value may not have changed
     * For this reason, events should fire at least once at startup to fill unknown values in the state
     *
     * @param key      The event key
     * @param oldValue The value of the event before this change
     * @param newValue The new value of the event
     */
    private static void stateChanged(EventData key, Value oldValue, Value newValue) {
        state.put(key, newValue);
        // Don't trigger events when we don't know the previous value
        if (oldValue != null) {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                if (!automation.getValue().isDisabled() && automation.getValue().isTriggered(key)) {
                    if (automation.getValue().conditionsMet(state)) {
                        logger.info("Adding automation to queue: " + automation.getKey());
                        AutomationQueue.addToQueue(automation.getKey(), automation.getValue().getDelay());
                    } else {
                        logger.info("Removing automation from queue: " + automation.getKey());
                        AutomationQueue.removeFromQueue(automation.getKey());
                    }
                }
            }
        }
    }

    /**
     * Update the value in the state with a new value
     * Uses the Not Equal To comparator to see if the value has changed
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Value value) {
        // Do nothing when no automations enabled
        if (isDisabled()) return;
        if (key == null || value == null) return;

        Value current = state.get(key);
        if (current == null || Boolean.TRUE.equals(current.compare(value, "neq"))) {
            stateChanged(key, current, value);
        }
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, String value) {
        update(key, new StringValue(value));
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Integer value) {
        update(key, new IntValue(value));
    }

    /**
     * Run the actions for a specific automation
     * Can run the actions without checking the conditions for testing the actions
     *
     * @param id              The id of the automation to run the actions for
     * @param checkConditions Whether to check that the conditions match before running the actions
     */
    public static void triggerActions(String id, boolean checkConditions) {
        Automation automation = automations.get(id);
        if (automation == null) return;

        if (!checkConditions || automation.conditionsMet(state)) {
            logger.info("Triggering automation actions: " + id);
            automation.triggerActions();
        }
    }
}
