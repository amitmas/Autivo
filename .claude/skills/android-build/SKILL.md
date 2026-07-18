---
name: android-build
description: Build, test, lint, and deploy the Autivo/OverDrive Android app (Gradle/Kotlin/NDK project). Use whenever asked to build, compile, run tests, run lint, install/deploy the app, or launch it on an emulator or the real BYD head unit.
---

# Building, testing, and deploying Autivo

This is an Android app with a native C++ layer, built for a BYD DiLink v3
head unit. It also runs fine in a standard emulator for UI/logic work, but
several features are silent no-ops there. Details below come from a
first-hand local-build session recorded in
`/Users/amitmaslaton/Sources/Autivo/docs/plans/02-build-test-emulate.md` —
re-read that file if anything here seems out of date.

## 1. JDK: Gradle needs JDK 17, regardless of what's on PATH

AGP 8.13.x's Gradle daemon requires **JDK 17**. If the default `java` is a
different major version (e.g. 25), Gradle fails with an opaque
`What went wrong: 25.0.3`-style error and no other detail — don't chase that
error message, just fix the JDK.

On macOS with Homebrew, JDK 17 is typically keg-only (not on PATH by
default):

```bash
brew install openjdk@17   # if not already installed
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew <task>
```

Confirm the actual path with `brew --prefix openjdk@17` if the machine
differs. This is distinct from the app's own Java/Kotlin source
compatibility, which targets Java 11 (`app/build.gradle.kts`) — don't try to
"fix" that to 17, it's intentional and separate from the Gradle-runtime JDK.

## 2. Android SDK location

`local.properties` (gitignored, per-developer) must contain
`sdk.dir=<path>`. On a machine with Android Studio already installed this is
usually `~/Library/Android/sdk`. Never commit this file or its contents.

## 3. First native build downloads dependencies over the network

`app/build.gradle.kts` registers `downloadOpenH264` and `downloadOpenCV`
Gradle tasks that run before any `CMake`/`ExternalNativeBuild` task. First
build (or after clearing `app/src/main/cpp/{openh264,opencv}`) needs network
access to fetch Cisco's OpenH264 binary and `opencv-mobile`. Subsequent
builds skip this if the files already exist. If a build is failing with
native-toolchain errors, check whether these downloads actually completed
before digging into CMake/NDK config.

## 4. Commands

```bash
# Fast compile-only sanity check (no lint/tests, no packaging)
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:compileDebugJavaWithJavac

# Full local gate before proposing a change is done — matches what
# CONTRIBUTING.md asks contributors to run before a PR
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew assembleDebug lint test

# Install + launch on a connected device/emulator
./gradlew installDebug

# Release build — only needed if explicitly asked; requires signing env vars
# KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS (never hardcode
# these or put them in a committed file)
./gradlew assembleRelease
```

`./gradlew test` runs 4 JVM unit test files under `app/src/test/`
(`ManualClipWindowTest`, `TelegramCatalogParityTest`,
`CoordinateInputParserTest`, `TelegramCommandLocalizationTest`, 46 tests
total). If you see failures mentioning `org.json` (`keySet()` missing, or
`JSONException` treated as unchecked), that's a known classpath-precedence
gotcha: the unit-test compile classpath can resolve `org.json.*` to
Android's frozen stub instead of the `testImplementation("org.json:json:...")`
dependency the build file adds specifically to avoid this. Fix by using
`keys()`/`Iterator<String>` instead of `keySet()`, and treating
`JSONException` as checked — don't try to "fix" the dependency declaration
itself, that part is already correct.

## 5. What an emulator can and cannot validate

**Emulator-testable:** navigation between screens, Settings fragments,
MQTT/Telegram/tunnel config UI, the WebView-hosted pages under
`app/src/main/assets/web/local/`, i18n rendering across locales, any
pure-logic unit test.

**NOT emulator-testable:** camera capture, surveillance/motion detection,
sentry mode, and all real vehicle control. Everything under
`app/src/main/java/android/hardware/bydauto/` is a reflection-based stub
that looks up BYD's real HAL at runtime — on a stock emulator there's
nothing to find, so these are silent no-ops, not simulated failures. Don't
read "builds and runs without crashing on the emulator" as "the
vehicle-control path works."

## 6. Deploying to the real head unit

This is higher-stakes than a normal mobile deploy — the app can issue real
vehicle commands. Follow the staged path, don't skip straight to daily-driver
use:

1. `./gradlew assembleDebug lint test` clean, then `installDebug` on an AVD
   and smoke-test navigation/Settings/web UI/a couple of non-English locales.
2. Sideload to the real head unit via Wireless ADB (see `Readme.md` for the
   pairing flow), with the car **parked, ignition off**, and MQTT "Allow
   vehicle control," Telegram, tunnels, and shell automations all **off**.
   Verify camera/dashcam/surveillance basics.
3. After first install, a **hard reboot** is required to finalize
   (hold Volume Down 5s) — this is a real, documented requirement, not
   optional troubleshooting.
4. Enable higher-risk features (MQTT vehicle control, tunnels, shell
   automations) one at a time, in a parked setting, before relying on them
   while driving.
5. Only then build/sign a release for daily-driver use.

Never suggest enabling vehicle-control features for a first test, and never
suggest testing them while the vehicle is in motion.
