---
name: byd-vehicle-integration
description: Add or modify integrations with BYD DiLink vehicle hardware — radar, doors/windows, climate, gearbox, tyres, charging, lights, engine, power, etc. Use when asked to expose a new vehicle signal, wire up a BYD sensor/property, or read/write a vehicle control under android/hardware/bydauto/* or com/overdrive/app/byd/*.
---

# BYD vehicle hardware integration pattern

Every BYD hardware integration in this app is two layers. Getting the split
right matters more than the code inside either layer — the compiler cannot
catch a mismatch with real hardware, since layer 1 never actually runs.

## Layer 1 — `android.hardware.bydauto.<module>/` (compile-only stubs)

Classes here (`AbsBYDAuto<Module>Listener`, `BYDAuto<Module>Device`) are
**stubs that exist only so the project compiles**. At runtime on a real BYD
head unit, the *actual* system framework provides classes with these exact
same fully-qualified names, method signatures, and integer constant values —
the JVM loads BYD's real implementation instead of this stub because it's
higher on the classpath at that point. On anything else (emulator, JVM unit
test), calls into this layer are no-ops.

**This means method names, signatures, and magic int values in this layer
must match BYD's real (undocumented) API exactly, or the integration will
silently fail at runtime** — not fail to compile. Do not invent a
signature/constant here; only add what's confirmed from BYD SDK
disassembly/documentation, the project's Discord community
(https://discord.gg/PZutk9fg4h — README's stated source for this kind of
reverse-engineered API knowledge), or an existing sibling module's already-
verified pattern. If unsure, say so and ask rather than guessing plausible
values — a wrong guess is worse than a missing feature because it fails
silently on real hardware.

Check `references/hardware-modules.md` first — it catalogs every module and
method already stubbed. A new signal often already has partial support
there under a module you weren't expecting (e.g. safety-belt status is
exposed from both `instrument` and `safetybelt`).

## Layer 2 — `com.overdrive.app.byd.<module>/` (the real logic, reflection-invoked)

This is where actual app behavior lives: `<Module>Manager.java` +
`<Module>Constants.java`. The manager never imports layer-1 classes
directly by static type — it loads them via `Class.forName(...)` +
reflection (`getMethod`/`invoke`), so the exact same compiled APK works
whether or not BYD's real classes are present. Study
`com/overdrive/app/byd/radar/RadarManager.java` +
`RadarConstants.java` as the canonical example.

Manager shape, consistently:
```java
public class <Module>Manager {
    private final Context context;
    private final EventCallback eventCallback;   // com.overdrive.app.byd.EventCallback
    private final LogCallback logCallback;        // com.overdrive.app.byd.LogCallback

    public void register() {
        try {
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.<module>.BYDAuto<Module>Device");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);
            // registerListener(...) via reflection, same pattern
        } catch (Exception e) {
            log("ERROR registering <module> listener: " + e.getMessage());
            // never throw — hardware may legitimately be absent (emulator, wrong car model)
        }
    }

    private class <Module>Listener extends AbsBYDAuto<Module>Listener {
        @Override
        public void on<Signal>Changed(...) {
            // update cached lastState, then build a JSONObject event and push via eventCallback.onEvent(...)
        }
    }
}
```

Constants class conventions (see `RadarConstants.java`,
`BodyworkConstants.java`): `private` no-arg constructor (utility class), int
constants for every raw hardware state value, a `String[]` name table or
`switch`-based `xToString(int)` helper for logging/JSON, never expose the
raw int to the JSON API without a matching `xName` string field.

Event JSON shape (matches every existing emitter): `type`, one or more
signal-specific fields, and always a `timestamp` (`System.currentTimeMillis()`),
wrapped in its own try/catch so a JSON-building bug can't take down the
listener callback.

## Wiring a new/changed manager in

Managers are instantiated once, centrally, in
`app/src/main/java/com/overdrive/app/byd/BydEventDaemon.java` — search that
file for the existing `new <X>Manager(context, BydEventDaemon::broadcastEvent, BydEventDaemon::logMessage)`
lines and add the new one alongside its siblings. Don't instantiate managers
ad hoc elsewhere; `BydEventDaemon` is the single place that owns their
lifecycle and fans events out.

## Steps to add a new signal

1. Check `references/hardware-modules.md` (below) and the existing
   `android/hardware/bydauto/` module for whether the stub method already
   exists — if so, skip straight to step 3.
2. Add the method to the existing `Abs<Module>Listener`/`BYDAuto<Module>Device`
   stub (layer 1), matching BYD's real signature exactly. If this is a
   genuinely new hardware module (no existing directory), mirror an existing
   module's file pair (`AbsBYDAuto<Module>Listener.java` +
   `BYDAuto<Module>Device.java`, both `extends AbsBYDAutoDevice` /
   `implements IBYDAutoListener`).
3. Add/extend the corresponding `<Module>Manager` + `<Module>Constants` in
   `com.overdrive.app.byd.<module>` (layer 2), following the shape above.
4. Wire the manager into `BydEventDaemon` if it's new.
5. Consumers (UI, web dashboard, automation triggers) read from the manager's
   `getLastStates()`/`getStateAsJson()`-style accessor or subscribe to
   `eventCallback` events — don't have UI code call layer 1 directly.
6. This can't be verified on an emulator (see the `android-build` skill) —
   say so explicitly rather than claiming the integration "works" after only
   a clean compile.
