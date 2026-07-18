---
name: automation-engine
description: Add a new trigger, condition, or action to the user-defined automation engine (the "if this happens, do that" rules under app/src/main/java/com/overdrive/app/automation/). Use when asked to add a new automation option, expose a vehicle signal as a condition, or add a new automation action.
---

# Automation engine (triggers / conditions / actions)

Rules are "On Change: `<event>` → If: `<conditions>` → Then: `<actions>` /
Else: `<actions>`", authored in the web UI (`automations.html`) and
evaluated by `Automations.java`/`AutomationQueue.java`. Almost every new
feature request in this area is one of two things — a **new condition/
trigger source** (a vehicle signal to react to) or a **new action** (a
thing to do) — and both have a strongly preferred, low-code path. Don't
invent a new class hierarchy; extend the existing catalogs.

## Adding a new action — prefer `ApiAction`, not a new `Action` subclass

`ApiAction` (`automation/action/ApiAction.java`) calls an *existing*
allowlisted HTTP endpoint with `${variable}` substitution into the path
and/or JSON body. This covers the overwhelming majority of actions already
registered in `Actions.java` (windows, climate, seat, lights, drive mode,
camera view, app launch, etc.) — look at any of them as a template. Only
write a dedicated `Action`/`BaseAction` subclass (like `ShellAction`,
`PauseAction`, `WaitUntilAction`, `SetVariableAction`, `ManualClipAction`)
when the behavior genuinely isn't "call an HTTP endpoint" — e.g. control
flow, or something that must run in-process rather than through the API
allowlist.

Steps:
1. Confirm the target HTTP endpoint already exists (see the
   `web-dashboard-api` skill if it doesn't yet).
2. **Add the endpoint's path prefix to `AUTOMATION_ALLOWED_PREFIXES` in
   `HttpServer.java`** if it isn't already covered by an existing prefix.
   This is a hard, separate security gate — `ApiAction.trigger()` calls
   `HttpServer.automationApiRequest()`, which **skips `AuthMiddleware`
   entirely** and checks only this allowlist. Forgetting this step means
   the action silently no-ops (logged as "AUTH: automation API request
   denied"); accidentally over-broadening it exposes sensitive surfaces
   (`/api/debug/*`, `/api/backup/`, `/api/update/`, `/api/telegram/`) to
   any saved automation. Keep it as tight as the new action genuinely needs
   — see the `car-security-checklist` skill.
3. Register the action in `Actions.java`'s constructor:
   ```java
   addAction(new ApiAction(
           new Label("myAction", "automation.my_action"), "automation.my_action_description",
           "POST", "/api/vehicle/whatever", "{\"field\":${value}}",
           new EnumType(new Label("value", "automation.value"), new Label("a", "automation.opt_a"), ...)));
   ```
   The `Label` id (`"myAction"`) is also the JSON `type` key persisted for
   every saved automation using it — treat it as a stable identifier, don't
   rename it later without a migration.
4. Add the referenced i18n keys (`automation.my_action`,
   `automation.my_action_description`, any option labels) to
   `app/src/main/assets/server-i18n/en.json` under the `"automation"`
   section — see the localization note in `CLAUDE.md`. Missing keys render
   as the raw key string, not a crash, but should still be added.
5. If the action gates something safety/consequence-sensitive (unlock,
   physically moving parts), check `car-security-checklist` for whether it
   needs the same rate-limiting / confirmation treatment as existing
   vehicle-control actions.

## Adding a new condition/trigger — `EventCondition`

Register in `Conditions.java`'s constructor, e.g.:
```java
addCondition(new EventCondition(
        new Label("mySignal", "automation.my_signal"), "automation.my_signal_description",
        new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
```
The same catalog entry serves both the "On Change" trigger list and the
"If" condition list (triggers are the condition schema with `comparator`/
`value` stripped) — you only register once.

**Sub-variables must be `EnumType`** (`EventCondition`'s constructor takes
`EnumType...` for variables, deliberately — not arbitrary `Type`), to keep
the live state map bounded (see the comment in `EventCondition.java`). Use
this for things like an `area`/`side`/`seat` selector alongside the main
value.

The condition only *displays and validates* — something has to actually
**publish** the signal into the engine via `Automations.update(EventData,
String value)`. Two patterns for that, both already used:
- **Polled**: read the signal out of the periodic vehicle-data snapshot
  (`BydDataCollector`) wherever that's already being consumed.
- **Event-driven (preferred when a raw hardware callback exists)**: see
  `condition/DoorEvent.java` — subscribes to a `BydDataCollector` listener
  once at startup and republishes each edge via `Automations.update(...)`,
  guarded by `Automations.isDisabled()` as a zero-cost early-out. Prefer
  this over polling when the underlying BYD hardware callback
  (`AbsBYDAuto<Module>Listener`, see `byd-vehicle-integration` skill)
  already fires on the edge you care about — it's cheaper and lower-latency
  than a poll loop.

## Conventions to preserve

- Every registered action/condition uses `Label(id, i18nKey)` — the `id` is
  a stable machine key (persisted in saved automations and matched by
  `automationCondition.type`), the second argument is the i18n key looked
  up via `Messages.get()`. Never reuse another entry's `id`.
- `LinkedHashMap` insertion order in `Actions`/`Conditions` is the display
  order in the UI picker — insert new entries near related ones, not
  alphabetically or at the end by default.
- `BaseAction.fromJson`/`toJson` already handle the generic
  variable-validation round-trip; only override them if the action's JSON
  shape is genuinely non-standard (rare — none of the current actions do).
