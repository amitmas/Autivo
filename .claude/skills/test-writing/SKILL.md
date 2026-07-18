---
name: test-writing
description: Write or extend JVM unit tests for the Autivo/OverDrive Android app under app/src/test/. Use when asked to add test coverage, write unit tests, or verify logic with tests in this project.
---

# Writing tests in this repo

## Hard constraint: no Android framework, no mocking framework

`app/build.gradle.kts` only declares `testImplementation(libs.junit)` (JUnit
4) and `org.json:json:20231013` for `app/src/test/`. There is **no
Mockito, no Robolectric, no MockK**, and `app/src/androidTest/` doesn't
exist yet despite `androidx.test`/Espresso being declared as
`androidTestImplementation` — those are unused today.

Practical consequence: **only test classes that have zero Android
dependency** (no `Context`, no `android.*` imports, pure Kotlin/Java logic —
parsers, validators, formatters, pure calculators). If the logic you want to
cover is entangled with `Context`, a `Manager`/`Daemon` class, or anything
under `android.hardware.bydauto/`, either:
- extract the pure-logic piece into its own class first (preferred — this is
  what `ManualClipWindow` and `CoordinateInputParser` already look like:
  small, dependency-free classes that happen to be called from
  Android-entangled code elsewhere), or
- say explicitly that the class isn't unit-testable as structured, rather
  than reaching for a mocking framework that isn't a project dependency.

Don't add Mockito/Robolectric as a new dependency to unblock a test unless
asked — that's a build-system change, not a test-writing change.

## Where tests live

Mirror the production package under `app/src/test/java/...` — e.g.
`com/overdrive/app/recording/ManualClipWindow.java` →
`app/src/test/java/com/overdrive/app/recording/ManualClipWindowTest.java`.
Both Java and Kotlin test files exist side by side (test language generally
follows the class under test's language, not a fixed project-wide choice).

## Conventions from the existing 4 test files (46 tests)

- **One test class per production class**, method names are the
  spec in plain English (`rejectsNegativeBeforeSeconds`,
  `parsesHemisphereLetters`, `shortPlusCode_withoutReferenceReturnsNull`) —
  no `test` prefix, no `@DisplayName`, the method name alone should read as
  the assertion being made.
- **Group with comment banners**, not nested classes: `// ── Bare
  coordinates ──` style section dividers when a file covers several input
  shapes (see `CoordinateInputParserTest.kt`).
- **`assertThrows(IllegalArgumentException.class, () -> ...)`** for
  validation/boundary rejection, not try/catch-and-fail.
- **Floating point**: always pass an explicit epsilon to `assertEquals`
  (`private val eps = 1e-5`, loosened per-assertion, e.g. `1e-3`, when the
  computation itself has inherent imprecision — comment why when you loosen
  it).
- **Regression tests are labeled**: a comment starting `// REGRESSION` (or
  `// REGRESSION (audit blocker)`) marks a test added because a real bug
  shipped — explain the failure mode in the comment, not just the assertion,
  since that's what stops it from quietly being "simplified" away later.
- **Test against reference data, not self-derived expectations** where an
  authoritative source exists — `CoordinateInputParserTest` uses Google's
  own published Plus Code test vectors rather than hand-computing expected
  lat/lng. Prefer this whenever the logic under test reimplements a public
  spec/format.
- Kotlin tests use `!!` after `assertNotNull(r)` rather than a safe-call
  chain — the null-assertion IS the check that the previous line already
  covered, don't add smart-cast workarounds.

## Running tests

See the `android-build` skill for the JDK-17 requirement and the known
`org.json` classpath gotcha (`keySet()`/unchecked-`JSONException` failing
under the test classpath — use `keys()`/checked `JSONException` instead).
To run a single test class: `./gradlew test --tests "com.overdrive.app.recording.ManualClipWindowTest"`.
