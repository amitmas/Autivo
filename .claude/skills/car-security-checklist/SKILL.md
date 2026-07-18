---
name: car-security-checklist
description: Project-specific security checklist for the Autivo/OverDrive vehicle app — credential storage, MQTT/tunnel auth, vehicle-control command paths, OTA updates, shell automations. Use alongside /security-review, or whenever touching crypto, auth, MQTT, tunnels, OTA, or automation-triggered shell/vehicle commands in this repo.
---

# Security checklist for this app

This app runs with ADB-granted elevated privileges directly on a BYD
vehicle's head unit and can issue real vehicle commands (unlock, windows,
climate). The attacker model throughout is: **no physical possession of the
vehicle required** — a co-located malicious app, a leaked config backup, an
open/misconfigured broker, or a compromised update pipeline are the
realistic threats, not someone holding the key fob.

Full narrative detail and **current status** (done / not started) for each
item lives in `docs/plans/03-security-fixes-critical.md` and
`04-security-fixes-high.md` (sibling `docs/` working directory) — those
files are the source of truth and change over time; this skill is a
condensed, pattern-focused checklist for *new* code, not a status tracker.
Re-read those docs rather than trusting this file's framing of what's
"already fixed."

## Established patterns to follow (don't reinvent)

- **Any new credential at rest** (token, password, API key) must go through
  `CredentialCipher`
  (`app/src/main/java/com/overdrive/app/byd/cloud/crypto/CredentialCipher.java`),
  following `BydCloudConfig.java`'s pattern: decrypt-on-read,
  encrypt-with-fail-open-guard-on-write (`if
  (!CredentialCipher.isEncrypted(encrypted)) return;` — never persist
  plaintext over a previously-working encrypted value). Do **not** reach for
  Android Keystore for anything the shell-UID daemon (UID 2000) also needs to
  read — Keystore keys are bound to the creating UID and this was already
  evaluated and rejected for that reason.
- **The world-readable/writable device-id and config files under
  `/data/local/tmp/`** are an intentional, documented cross-UID tradeoff
  (app UID + daemon UID both need read access) — this is not itself a bug to
  "fix" by tightening permissions; don't propose that. The actual bug
  surface is what derives keys *from* that shared material (entropy,
  fallback behavior), not the file permissions themselves.
- **Any new remote-triggerable vehicle command** (HTTP or MQTT) should route
  through the existing rate limiter pattern in `VehicleControlApiHandler`/
  `VehicleCommandRouter.java`, not a new ad hoc throttle. If it's
  physically consequential (unlock, tailgate/window open), consider whether
  it needs the same step-up-confirmation treatment already planned for
  those actions (reuse the `PinLockActivity` PIN concept rather than
  inventing a new confirmation mechanism).
- **TLS "trust self-signed" toggles** must never resolve to a no-op
  `checkServerTrusted` (blanket accept-all). Use TOFU certificate/public-key
  pinning (store the fingerprint via `CredentialCipher`-protected storage on
  first connect, verify on every subsequent connect) if a self-signed-cert
  use case is required.
- **`ShellAction` automation (arbitrary `sh -c` on vehicle-state triggers)**
  is intentionally gated behind an opt-in flag
  (`UnifiedConfigManager.isAutomationShellAllowed()`) plus standard
  JWT auth — do not add a path that reaches this without both gates, and
  do not add this endpoint to any auth-bypass allowlist.
- **JWTs / session secrets**: prefer short lifetimes with rotation over
  long-lived tokens; if you're touching `AuthManager`/`AuthMiddleware`,
  check who currently holds long-lived sessions (native app, web UI,
  external integrations like Home Assistant hitting the HTTP API directly)
  before changing expiry/rotation behavior, so you don't silently break them
  without a re-auth UX.
- **Path handling for served files** (`HttpServer.java` static file
  serving): a canonical-path containment check, not a naive
  `relativePath.contains("..")` substring guard.
- **`android:usesCleartextTraffic`**: prefer scoping to the specific
  domains/IPs that need it (network security config) over an app-wide
  blanket allow, when touching this area.
- **`exported="true"` Android components**: prefer an actual
  signature/permission-based caller check over a code comment explaining
  why "only the daemon happens to call this" is safe.

## When reviewing a change in this repo, specifically check

1. Does it introduce a new credential/secret at rest? → must use
   `CredentialCipher`, not raw file writes.
2. Does it add a new way to trigger a vehicle command (HTTP endpoint, MQTT
   topic, automation action, deep link)? → must pass through
   `AuthMiddleware` + the existing rate limiter; flag if it's a
   physically-consequential action without confirmation.
3. Does it touch TLS/certificate validation anywhere (MQTT, tunnels, OTA
   downloads)? → no blanket trust-all `TrustManager`; OTA downloads should
   be checksum-verified against the published release artifact, not just
   TLS + platform signature.
4. Does it touch `ShellAction`, `AuthManager`, or anything in
   `automation/action/`? → confirm the opt-in-flag + auth gates aren't
   weakened or bypassed.
5. Does it change file permissions on `/data/local/tmp/*`? → confirm you
   understand the cross-UID read requirement before "hardening" it.

For a full review (not just these hot spots), still run `/security-review`
or `/code-review ultra` — this checklist supplements those with
project-specific context they won't otherwise have, it doesn't replace them.
