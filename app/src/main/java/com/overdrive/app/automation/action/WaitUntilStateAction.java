package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Map;

/**
 * Automation action that blocks the action chain until a chosen ON/OFF vehicle signal
 * reaches a target state, or a timeout elapses. The on/off counterpart to
 * {@link WaitUntilAction} (which handles numeric signals): together they let a chain
 * pause on either "speed &lt; 5" or "left indicator = off".
 *
 * <p>Example the user asked for: "when I signal to change lane → wait until indicators
 * = off → log/notify". Scope is the on/off (and combined-indicator) signals for which
 * "wait until &lt;x&gt; is on/off" reads naturally; multi-value enums (gear P/R/N/D)
 * are intentionally left to trigger+condition, matching {@link WaitUntilAction}'s
 * scalar-only rationale, so the form stays a flat, self-explanatory row.
 *
 * <p>Like {@link WaitUntilAction} and {@link PauseAction} the wait runs on the single
 * {@link com.overdrive.app.automation.AutomationQueue} worker thread, so it delays only
 * THIS automation's remaining actions — never telemetry, HTTP, or other automations —
 * and is bounded by a timeout so a never-satisfied state can't park the worker forever.
 * It polls the SAME live automation state the triggers/conditions evaluate against.
 */
public class WaitUntilStateAction extends BaseAction {
    private static final String TYPE = "waitUntilState";

    private static final long POLL_MS = 250L;
    private static final int MAX_TIMEOUT_S = 600; // 10 min hard ceiling
    private static final int DEFAULT_TIMEOUT_S = 30;

    // Combined-indicator sentinel: satisfied when BOTH turn signals match "off", or
    // EITHER matches "on" (i.e. "indicators are off" vs "an indicator is on").
    private static final String EVENT_TURN_ANY = "turnAny";

    private final Label label;
    private final String description;
    private final List<Type> variables;

    public WaitUntilStateAction(Label label, String description) {
        this.label = label;
        this.description = description;
        // event: which on/off signal to watch. ids MUST match the resolveEvent switch.
        // state: the target on/off value. timeout: bounded wait ceiling.
        this.variables = List.of(
                new EnumType(new Label("event", "automation.wait_signal"),
                        new Label("turnLeft", "automation.turn_left"),
                        new Label("turnRight", "automation.turn_right"),
                        new Label(EVENT_TURN_ANY, "automation.turn_indicators"),
                        new Label("lowBeam", "automation.low_beam"),
                        new Label("highBeam", "automation.high_beam"),
                        new Label("hazard", "automation.hazard"),
                        new Label("drl", "automation.drl"),
                        new Label("ac", "automation.ac"),
                        new Label("slw", "automation.slw"),
                        new Label("wifiState", "automation.wifi"),
                        new Label("btState", "automation.bluetooth"),
                        new Label("emergencyAlarm", "automation.emergency_alarm")),
                new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off")),
                new IntType(new Label("timeout", "automation.wait_timeout"), 1, MAX_TIMEOUT_S));
    }

    public String getType() { return TYPE; }

    public Label getLabel() { return label; }

    public String getDescription() { return Messages.get(description); }

    public List<Type> getVariables() { return variables; }

    /** Map the picker's event id to its published {@link EventData}, or null for the
     *  combined-indicator sentinel / an unknown id (handled specially by the caller). */
    private static EventData resolveEvent(String id) {
        if (id == null) return null;
        switch (id) {
            case "turnLeft":       return BydEvent.TURN_LEFT;
            case "turnRight":      return BydEvent.TURN_RIGHT;
            case "lowBeam":        return BydEvent.LIGHTS_LOW_BEAM;
            case "highBeam":       return BydEvent.LIGHTS_HIGH_BEAM;
            case "hazard":         return BydEvent.LIGHTS_HAZARD;
            case "drl":            return BydEvent.LIGHTS_DRL;
            case "ac":             return BydEvent.AC;
            case "slw":            return BydEvent.SLW;
            case "wifiState":      return BydEvent.WIFI_STATE;
            case "btState":        return BydEvent.BT_STATE;
            case "emergencyAlarm": return BydEvent.EMERGENCY_ALARM;
            default:               return null; // incl. EVENT_TURN_ANY (combined)
        }
    }

    /**
     * Block until the chosen signal reaches {@code state} (on/off) or the timeout
     * elapses. Polls the shared automation state every {@link #POLL_MS}. Interrupt
     * (worker teardown) is re-asserted so the worker unwinds cleanly.
     */
    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String eventId = str(vars.get("event"));
        String target = str(vars.get("state"));
        int timeoutS = clampTimeout(toInt(vars.get("timeout")));

        if (eventId == null || target == null) {
            logger.warn("WaitUntilStateAction: missing/invalid parameters, skipping");
            return;
        }

        long deadline = System.currentTimeMillis() + timeoutS * 1000L;
        while (true) {
            if (stateMet(eventId, target)) {
                logger.info("WaitUntilStateAction: satisfied (" + eventId + " = " + target + ")");
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                logger.info("WaitUntilStateAction: timed out after " + timeoutS + "s waiting for "
                        + eventId + " = " + target);
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Whether the live state of {@code eventId} equals {@code target} ("on"/"off"). The
     * combined-indicator sentinel is satisfied for "off" only when BOTH turn signals
     * read off, and for "on" when EITHER reads on. A never-fired signal (null state) is
     * treated as "not yet met" so the wait keeps polling until it appears or times out.
     */
    private static boolean stateMet(String eventId, String target) {
        if (EVENT_TURN_ANY.equals(eventId)) {
            boolean leftOn = isOn(BydEvent.TURN_LEFT);
            boolean rightOn = isOn(BydEvent.TURN_RIGHT);
            boolean anyOn = leftOn || rightOn;
            return "on".equalsIgnoreCase(target) ? anyOn : !anyOn;
        }
        EventData event = resolveEvent(eventId);
        if (event == null) return false;
        Value current = Automations.getStateValue(event);
        if (current == null) return false;
        return Boolean.TRUE.equals(current.compare(target, "eq"));
    }

    /** True iff the event's current state reads "on". Unknown (null) → false. */
    private static boolean isOn(EventData event) {
        Value v = Automations.getStateValue(event);
        return v != null && Boolean.TRUE.equals(v.compare("on", "eq"));
    }

    private static int clampTimeout(Integer t) {
        if (t == null) return DEFAULT_TIMEOUT_S;
        return Math.max(1, Math.min(MAX_TIMEOUT_S, t));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
