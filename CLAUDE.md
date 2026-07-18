# Autivo (fork of OverDrive)

Android app (Kotlin/Java + native C++) that turns a BYD DiLink v3 head unit
into a dashcam / sentry-mode system with vehicle telemetry, remote access,
and automations. Runs **on the car's head unit** with ADB-granted elevated
privileges and can issue real vehicle commands (unlock, windows, climate).
Treat vehicle-control code paths with the same care as safety-critical code.

## Fork relationship — read before touching `app/src/main`

This repo (`origin` = `amitmas/Autivo`) is a long-lived fork of
`upstream` = `yash-srivastava/Overdrive-release` ("OverDrive"), synced via
`git fetch upstream && git merge upstream/main`. The rebrand to "Autivo" is
done as a **Gradle product-flavor overlay** (`app/src/autivo/`), not a
package rename — `namespace`/`applicationId` in `app/build.gradle.kts` are
still `com.overdrive.app` today, and all `com.overdrive.app.*` class names,
`AndroidManifest.xml` identifiers, and `themes_overdrive.xml` are meant to
stay that way indefinitely. Full plan: `docs/plans/01-rebrand-autivo.md`
(this file lives in the **sibling** `docs/` working directory, not inside
this repo).

**Implication for every change:** avoid sweeping reformatting/reorganization
inside `app/src/main` — every line touched there is a line that can conflict
on the next `merge upstream/main`. Prefer additive changes; put anything
Autivo-specific under `app/src/autivo/` where it can't conflict. Generic
fixes (e.g. security hardening) are candidates for upstreaming as a PR to
`yash-srivastava/Overdrive-release` instead of living only as a local diff.

## Planning docs (sibling `docs/` directory)

`/Users/amitmaslaton/Sources/Autivo/docs/plans/` tracks in-progress
workstreams — check it before starting related work, since it captures
decisions/status not visible from the code alone:
- `01-rebrand-autivo.md` — flavor overlay plan (not started)
- `02-build-test-emulate.md` — verified local build/test recipe, emulator vs
  real-hardware capability matrix
- `03-security-fixes-critical.md`, `04-security-fixes-high.md` — security
  audit findings for this fork (`CredentialCipher`, MQTT TLS, JWT, etc.),
  some done, some not started
- `05-ui-ux-accessibility.md` — accessibility/non-technical-user UX audit

## Tech stack

- Kotlin 2.0.21 + Java 11 source/target, AGP 8.13.2, Gradle 8.13 (wrapper)
- `compileSdk` 36, `minSdk`/`targetSdk` 25 (intentionally low — sideloaded
  head-unit installs only, not Play Store; `ExpiredTargetSdkVersion` lint
  suppressed on purpose)
- NDK 26.1.10909125 — native C++ under `app/src/main/cpp/` (surveillance
  motion pipeline, camera, bundled OpenCV-mobile + OpenH264, auto-downloaded
  by Gradle on first native build — needs network access)
- XML/View + Fragment + Navigation Component UI (not Compose); Material 3
  theming (`Theme.Overdrive.M3`)
- No CI pipeline yet (only an issue-welcome bot workflow) and no dependency
  injection framework — check existing patterns in the target package before
  introducing either

## Build, test, run

Full tribal knowledge (exact JDK path, verified commands, emulator vs
real-device capability matrix, staged rollout to a real vehicle) lives in
the `android-build` skill — use it instead of guessing at Gradle invocations.
Short version: Gradle itself needs **JDK 17** (not whatever `java -version`
shows by default), `local.properties` needs `sdk.dir` set locally (gitignored,
never commit it), and `./gradlew test` covers 4 JVM unit test files under
`app/src/test/`. For conventions when adding test coverage (no
Mockito/Robolectric — only pure-logic classes are unit-testable today), use
the `test-writing` skill.

## Architecture map

`app/src/main/java/com/overdrive/app/` (see `CONTRIBUTING.md` for the
authoritative version):
- `byd/`, `android/hardware/bydauto/` — BYD vehicle HAL integration. Classes
  under `bydauto/` are **reflection-based stubs** that look up BYD's real
  HAL at runtime — silent no-ops on anything but real BYD hardware, so they
  can't be validated on an emulator. Use the `byd-vehicle-integration` skill
  when adding or changing any vehicle signal/property — it documents the
  two-layer stub/reflection pattern and catalogs what's already stubbed.
- `camera/`, `streaming/`, `cpp/surveillance/` (native) — AVM/panoramic
  camera capture, motion detection, GPU-accelerated streaming
- `daemon/` — background services incl. a **shell-UID daemon**
  (`CameraDaemon`) that runs outside the app's own UID; be careful with
  anything that assumes a single process/UID boundary
- `server/` — HTTP/TCP/IPC server backing `assets/web/` (the local/remote
  dashboard UI) and the JSON API; hand-written dispatch chain, not a router
  framework. Use the `web-dashboard-api` skill when adding an endpoint or
  page — it also covers the separate `AUTOMATION_ALLOWED_PREFIXES` auth-bypass
  allowlist, which is easy to miss.
- `mqtt/`, `telegram/`, `abrp/` — outbound integrations (home automation,
  notifications, EV telemetry)
- `automation/` — condition/action/trigger engine for user-defined rules
  ("On Change X → If Y → Then Z"). Use the `automation-engine` skill when
  adding a new trigger/condition/action — almost always an `EventCondition`
  or `ApiAction` registration, not a new class.
- `roadsense/` — AI-assisted road-condition detection (detect/warn/store/sync
  submodules)
- `trips/`, `telemetry/`, `monitor/` — trip scoring, overlay rendering,
  vehicle/battery/network monitoring
- `ui/` — Fragments + Navigation Component; nav graph at
  `app/src/main/res/navigation/nav_graph.xml`

## Security posture

Credentials (Telegram bot token, BYD Cloud password, MQTT, NavMap key,
tunnel tokens) are encrypted at rest via `CredentialCipher`
(`app/src/main/java/com/overdrive/app/byd/cloud/crypto/`), keyed off a
device ID shared cross-UID with the shell daemon — Android Keystore was
deliberately rejected because Keystore keys can't cross that UID boundary.
Recent/ongoing hardening is tracked in `docs/plans/03-*` and `04-*`
(critical/high severity). When touching crypto, MQTT TLS, JWT, or IPC
between the app UID and the shell-UID daemon, check those docs first — they
document what's already fixed vs. still open, and the specific attacker
model (no physical vehicle access required). The `car-security-checklist`
skill condenses the established patterns to follow (credential storage,
rate limiting, TLS pinning, shell-automation gating) for new code touching
these areas — use it alongside `/security-review`.

## Conventions

- Kotlin: prefer `val`, idiomatic scope/extension functions (see
  `CONTRIBUTING.md` for the full style guide)
- No hardcoded secrets/API keys; signing uses env vars (`KEYSTORE_FILE`,
  `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`), never files in the repo
- Keep log output meaningful — avoid excessive debug logging on paths that
  run on the real vehicle
- User-facing strings live in **three separate base catalogs**, not one:
  `res/values/strings.xml` (native Android UI), `assets/web/i18n/en.json`
  (web dashboard, nested JSON, consumed via `BYD.i18n.t()`), and
  `assets/server-i18n/en.json` (server-generated strings incl. every
  automation label/description, nested JSON, dotted-path lookup via
  `Messages.get()`). Adding a key to the wrong one is an easy mistake — see
  the `web-dashboard-api` skill. ~32 locales exist under `values-*/` /
  `i18n/*.json` / `server-i18n/*.json`; only edit the base `en` file in
  each — Crowdin (`crowdin.yml`) handles the rest, don't hand-translate.
