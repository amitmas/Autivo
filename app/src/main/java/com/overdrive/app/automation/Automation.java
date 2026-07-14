package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Automation {
    // Condition-combining logic. "AND" (default) = every condition must match;
    // "OR" = any one condition matching is enough. Stored as a String so the JSON
    // schema stays open and an unknown/absent value degrades safely to AND — which
    // is exactly the pre-existing behaviour, so saved automations are unaffected.
    public static final String LOGIC_AND = "AND";
    public static final String LOGIC_OR = "OR";

    private final LinkedHashSet<EventData> triggers;
    private final List<AutomationCondition> conditions;
    private final String conditionLogic;
    private final int delay;
    private final List<AutomationAction> actions;
    // Optional "else" branch: actions run when the automation is triggered but its
    // conditions are NOT met. Empty when unused, so an automation with no else
    // branch behaves exactly as before. Never null.
    private final List<AutomationAction> elseActions;

    // volatile: written from HTTP request threads (setDisabled) and read from the telemetry thread
    // (stateChanged) and the queue worker thread (triggerActions) with no shared lock, so a plain field
    // could let those threads observe a stale enabled/disabled state and fire a just-disabled automation.
    private volatile boolean disabled;

    /**
     * Backward-compatible constructor: AND logic, no else branch. Kept so any
     * existing caller / test that built an Automation the old way still compiles
     * and behaves identically.
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            Integer delay,
            List<AutomationAction> actions,
            boolean disabled) {
        this(triggers, conditions, LOGIC_AND, delay, actions, null, disabled);
    }

    /**
     * A representation of a single automation
     * Contains all the fields which build up an automation
     * Can be stored and loaded when needed
     *
     * @param triggers       The events which would cause this automation to be checked
     * @param conditions     The conditions to check before applying the actions of this automation
     * @param conditionLogic How conditions combine: {@link #LOGIC_AND} or {@link #LOGIC_OR}
     *                       (null/unknown → AND)
     * @param delay          The amount of time to wait before running the actions in seconds
     * @param actions        The actions which will be run in order when this automation is triggered
     * @param elseActions    Actions to run when triggered but conditions are NOT met (null → none)
     * @param disabled       Whether this automation is disabled. This can be mutated to disable and enable later
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            String conditionLogic,
            Integer delay,
            List<AutomationAction> actions,
            List<AutomationAction> elseActions,
            boolean disabled) {
        this.triggers = new LinkedHashSet<>(triggers);
        this.conditions = conditions;
        // Normalize: only "OR" (case-insensitive) enables OR; everything else is AND.
        this.conditionLogic = LOGIC_OR.equalsIgnoreCase(conditionLogic) ? LOGIC_OR : LOGIC_AND;
        this.delay = Objects.requireNonNullElse(delay, 0);
        this.actions = actions;
        this.elseActions = elseActions != null ? elseActions : new ArrayList<>();
        this.disabled = disabled;
    }

    /**
     * The events which would cause this automation to be checked
     *
     * @return The events which would cause this automation to be checked
     */
    public LinkedHashSet<EventData> getTriggers() {
        return triggers;
    }

    /**
     * The conditions to check before applying the actions of this automation
     *
     * @return The conditions to check before applying the actions of this automation
     */
    public List<AutomationCondition> getConditions() {
        return conditions;
    }

    /**
     * How the conditions combine: {@link #LOGIC_AND} or {@link #LOGIC_OR}.
     *
     * @return the normalized condition logic
     */
    public String getConditionLogic() {
        return conditionLogic;
    }

    /**
     * Actions to run when the automation is triggered but its conditions are NOT
     * met. Never null; empty when there is no else branch.
     *
     * @return the else-branch actions
     */
    public List<AutomationAction> getElseActions() {
        return elseActions;
    }

    /**
     * Whether this automation has a non-empty else branch.
     *
     * @return true if there are else actions to run when conditions fail
     */
    public boolean hasElseActions() {
        return elseActions != null && !elseActions.isEmpty();
    }

    /**
     * The amount of time to wait before running the actions in seconds
     *
     * @return The amount of time to wait before running the actions in seconds
     */
    public int getDelay() {
        return delay;
    }

    /**
     * The actions which will be run in order when this automation is triggered
     *
     * @return The actions which will be run in order when this automation is triggered
     */
    public List<AutomationAction> getActions() {
        return actions;
    }

    /**
     * Whether this automation is currently disabled
     *
     * @return Whether this automation is currently disabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Check if the triggered contains an event
     * Will be looked up from a set so is fast to check
     *
     * @param trigger The event to check
     * @return true if this automation should be checked, false otherwise
     */
    public boolean isTriggered(EventData trigger) {
        return triggers.contains(trigger);
    }

    /**
     * Whether all the conditions specified in this automation are met
     *
     * @param state The current event state
     * @return true if all conditions match the state, false otherwise
     */
    public boolean conditionsMet(Map<EventData, Value> state) {
        // No conditions → always met (unchanged behaviour), regardless of logic.
        // This also keeps OR from vacuously returning false on an empty list.
        if (conditions.isEmpty()) return true;
        if (LOGIC_OR.equals(conditionLogic)) {
            return conditions.stream().anyMatch(condition -> condition.compare(state.get(condition.getEventData())));
        }
        return conditions.stream().allMatch(condition -> condition.compare(state.get(condition.getEventData())));
    }

    /**
     * Mutate this automation to disable the actions
     *
     * @param disabled Whether this should be disabled
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * Run all the actions for this automation
     * Will not check whether the conditions are met so that should be checked first if needed
     */
    public void triggerActions() {
        runActions(getActions());
    }

    /**
     * Run the else-branch actions (the ones that fire when the automation is
     * triggered but its conditions are NOT met). No-op when there is no else branch.
     */
    public void triggerElseActions() {
        runActions(getElseActions());
    }

    /**
     * Run a list of actions in order. Shared by {@link #triggerActions()} and
     * {@link #triggerElseActions()} so both branches get the same null/unknown-type
     * defense-in-depth.
     */
    private void runActions(List<AutomationAction> actionList) {
        if (actionList == null) return;
        for (AutomationAction automationAction : actionList) {
            // Defense-in-depth: a hand-edited config.json can still yield a null or unknown-type
            // action element; skip it so it can never NPE the queue drainer or the /test path.
            if (automationAction == null) continue;
            Action action = Automations.getAction(automationAction.getType());
            if (action == null) continue;
            action.trigger(automationAction);
        }
    }

    /**
     * Create a JSON object which can be stored and loaded for this automation
     *
     * @return JSON representation of this automation
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            JSONArray triggers = new JSONArray();
            for (EventData trigger : getTriggers()) {
                triggers.put(trigger.toJson());
            }
            json.put("triggers", triggers);
            JSONArray conditions = new JSONArray();
            for (AutomationCondition condition : getConditions()) {
                conditions.put(condition.toJson());
            }
            json.put("conditions", conditions);
            json.put("conditionLogic", getConditionLogic());
            json.put("delay", getDelay());
            JSONArray actions = new JSONArray();
            for (AutomationAction action : getActions()) {
                actions.put(action.toJson());
            }
            json.put("actions", actions);
            // Always emit elseActions (possibly empty) so a round-trip is stable;
            // an empty array is equivalent to "no else branch".
            JSONArray elseActionsJson = new JSONArray();
            for (AutomationAction action : getElseActions()) {
                elseActionsJson.put(action.toJson());
            }
            json.put("elseActions", elseActionsJson);
            json.put("disabled", isDisabled());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Create an instance of this class from some JSON
     * Will validate that the automation is valid
     *
     * @param input The JSON for this automation
     * @return An automation instance if the JSON is valid, null otherwise
     */
    public static Automation fromJson(JSONObject input) {
        try {
            List<EventData> triggers = new ArrayList<>();
            JSONArray triggersJson = input.getJSONArray("triggers");
            if (triggersJson.length() == 0) return null;
            for (int i = 0; i < triggersJson.length(); i++) {
                JSONObject triggerJson = triggersJson.getJSONObject(i);
                String key = triggerJson.getString("type");
                EventCondition condition = Automations.getCondition(key);
                if (condition == null) return null;
                triggers.add(condition.eventData(triggerJson));
            }

            List<AutomationCondition> conditions = new ArrayList<>();
            JSONArray conditionsJson = input.optJSONArray("conditions");
            if (conditionsJson != null) {
                for (int i = 0; i < conditionsJson.length(); i++) {
                    JSONObject conditionJson = conditionsJson.getJSONObject(i);
                    String key = conditionJson.getString("type");
                    EventCondition condition = Automations.getCondition(key);
                    if (condition == null) return null;
                    conditions.add(condition.automationCondition(conditionJson));
                }
            }

            // Optional; absent/unknown → AND, which is the pre-existing behaviour, so
            // automations saved before this field existed load and run unchanged.
            String conditionLogic = input.optString("conditionLogic", LOGIC_AND);

            int delay = input.optInt("delay", 0);
            if (!Automations.isValidDelay(delay)) return null;

            // "actions" is required and must be non-empty (the primary branch).
            List<AutomationAction> actions = parseActions(input.getJSONArray("actions"));
            if (actions == null || actions.isEmpty()) return null;

            // "elseActions" is optional. Absent → no else branch. Present-but-invalid
            // (an unknown action type) → reject the whole automation rather than
            // silently dropping the branch, matching how the primary actions are
            // validated. An explicitly empty array is fine (== no else branch).
            List<AutomationAction> elseActions = new ArrayList<>();
            JSONArray elseActionsJson = input.optJSONArray("elseActions");
            if (elseActionsJson != null && elseActionsJson.length() > 0) {
                elseActions = parseActions(elseActionsJson);
                if (elseActions == null) return null;
            }

            boolean disabled = input.optBoolean("disabled", false);

            return new Automation(triggers, conditions, conditionLogic, delay, actions, elseActions, disabled);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse an actions JSON array into a list of {@link AutomationAction}. Shared by
     * the primary "actions" and optional "elseActions" parse so both validate an
     * unknown action type identically.
     *
     * @param actionsJson the JSON array of action objects
     * @return the parsed list, or null if any element is missing/unknown-type/invalid
     * @throws org.json.JSONException if the array shape is malformed (caller catches)
     */
    private static List<AutomationAction> parseActions(JSONArray actionsJson) throws org.json.JSONException {
        List<AutomationAction> actions = new ArrayList<>();
        for (int i = 0; i < actionsJson.length(); i++) {
            JSONObject actionJson = actionsJson.getJSONObject(i);
            String key = actionJson.getString("type");
            Action action = Automations.getAction(key);
            if (action == null) return null;
            AutomationAction automationAction = action.fromJson(actionJson);
            if (automationAction == null) return null;
            actions.add(automationAction);
        }
        return actions;
    }
}
