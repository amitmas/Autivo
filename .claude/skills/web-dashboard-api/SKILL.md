---
name: web-dashboard-api
description: Add a new HTTP API endpoint or a new page to the local/remote web dashboard (app/src/main/java/com/overdrive/app/server/ + app/src/main/assets/web/). Use when asked to add a server API handler, a new dashboard page, or wire up new backend data to the web UI.
---

# Adding to the HTTP server / web dashboard

The whole local + tunneled remote-access UI is one `HttpServer.java`
serving both static pages (`assets/web/local/*.html` + `assets/web/shared/*.js`)
and a JSON API, dispatching to per-feature `*ApiHandler` classes. There's no
router library or annotation-based routing — it's a hand-written dispatch
chain — so new endpoints/pages follow the existing shape exactly rather than
introducing a new pattern.

## Adding a new API endpoint

1. Create `app/src/main/java/com/overdrive/app/server/<Feature>ApiHandler.java`
   as a `final` class with a private constructor and a single static entry
   point, mirroring `AppsApiHandler.java` (the smallest complete example):
   ```java
   public final class MyFeatureApiHandler {
       private MyFeatureApiHandler() {}
       public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
           int q = path.indexOf('?');
           String cleanPath = q >= 0 ? path.substring(0, q) : path;
           if (cleanPath.equals("/api/myfeature/thing") && method.equals("GET")) {
               handleThing(out);
               return true;
           }
           return false; // not our path — let HttpServer try other handlers / 404
       }
       private static void handleThing(OutputStream out) throws Exception {
           JSONObject resp = new JSONObject();
           try {
               resp.put("success", true);
               // ...
           } catch (Throwable t) {
               resp.put("success", false);
               resp.put("error", t.getMessage());
           }
           HttpResponse.sendJson(out, resp.toString());
       }
   }
   ```
   Every handler returns `{"success": bool, ...}` (or `{"success": false,
   "error": "..."}`) and wraps its body in try/catch — a handler should
   never let an exception propagate and take down the connection.
2. Wire it into `HttpServer.routeToHandlers()` — add a
   `if (path.startsWith("/api/myfeature")) return MyFeatureApiHandler.handle(...);`
   branch alongside the existing ones (search for `/api/gps` or `/api/apps`
   for a neighboring example). Order matters only where prefixes overlap
   (e.g. `/api/surveillance/safe-locations` is checked *before* the broader
   `/api/surveillance` branch) — put more specific prefixes first if you
   introduce one.
3. **Decide whether this endpoint needs to be automation-callable.** By
   default it is NOT — automations can only reach endpoints listed in
   `HttpServer.AUTOMATION_ALLOWED_PREFIXES`, a hard security boundary
   separate from normal auth (automation requests skip `AuthMiddleware`
   entirely). Only add a prefix there if a saved automation should be able
   to hit it unattended — see the `automation-engine` and
   `car-security-checklist` skills before doing so.
4. Normal (non-automation) requests go through `AuthMiddleware` — new
   endpoints get the standard JWT check for free by virtue of being under
   `/api/`; don't add bespoke auth logic unless the endpoint has genuinely
   different requirements (e.g. `/auth/*` itself).

## Adding a new dashboard page

1. Add `app/src/main/assets/web/local/<page>.html` following an existing
   page's structure (shared `<head>` boilerplate, `app-shell.js` nav,
   `core.js` i18n init — copy a small existing page like `about.html` or
   `abrp.html` as a starting skeleton rather than writing the shell from
   scratch).
2. Add its JS logic under `assets/web/shared/<page>.js` if it's
   substantial, or inline in the page if trivial — follow the existing
   split (most feature pages have a same-named `.js` file in `shared/`).
3. Register the route in `HttpServer.java`'s page-serving `if/else if`
   chain (same file as the API dispatch, further up — search for
   `path.equals("/about.html")` for the pattern): both the `.html` and the
   extension-less alias (`/about` as well as `/about.html`) are
   conventionally registered together.
4. Add a nav entry in `app-shell.js` if the page should appear in the
   dashboard's navigation.

## Calling the API from page JS

`assets/web/shared/auth.js` **monkey-patches the global `window.fetch`** to
automatically inject `Authorization: Bearer <jwt>` on every call. New page
JS should just call `fetch('/api/myfeature/thing')` directly — do not
manually attach an Authorization header or reimplement token handling; it's
already done globally. (`BYDAuth.fetch(...)` also exists as an explicit
wrapper but is redundant with the global patch for normal same-origin
calls.)

## Adding user-facing strings

Don't hardcode English strings in JS/HTML or Java handler responses.
- **Web UI text**: `BYD.i18n.t('some.key')` (see `core.js`), key defined in
  `app/src/main/assets/web/i18n/en.json`.
- **Server-generated strings** (API error messages, automation
  labels/descriptions, notification text): `Messages.get("section.key")` in
  Java, defined in `app/src/main/assets/server-i18n/en.json` — this file is
  **nested JSON** (`{"section": {"key": "..."}}`), and `Messages.get` walks
  the dotted key as a path (`"automation.power"` → `automation.power`
  nested lookup), not a flat key.
- **Native Android UI** (fragments/layouts): standard Android
  `res/values/strings.xml` + `getString(R.string.x)`.

All three are separate catalogs — adding a key to the wrong one is a common
mistake (e.g. an automation label goes in `server-i18n/en.json`, not
`strings.xml`, even though `com.overdrive.app.automation.*` sounds like
"native Android"). Only edit the **base `en` file** in each; Crowdin
(`crowdin.yml`) handles the other ~32 locale files — don't hand-translate
them yourself unless explicitly asked.
