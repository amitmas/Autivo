package com.overdrive.app.community.config

import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.roadsense.sync.DeviceId
import org.json.JSONObject
import java.util.UUID

/**
 * Typed accessor for the `community` section of [UnifiedConfigManager] — config for
 * the Community Automations sharing feature (browse/publish shared automations via
 * the open-source `community-edge/` Cloudflare Worker + D1 backend).
 *
 * Mirrors [com.overdrive.app.roadsense.config.RoadSenseConfig]: a feature-owned
 * wrapper over UCM's PUBLIC generic API (`loadConfig()`/`forceReload()` to read,
 * `updateSection()` to merge-write) so it never touches UCM source, and the section
 * is file-backed so both the app UID (web settings writes) and the daemon UID
 * (handler reads) see the same values. Daemon reads that need the very latest
 * app-written value pass `forceReload=true` (cross-UID staleness — see
 * feedback_unified_config_force_reload).
 *
 * ## Identity is SHARED with RoadSense — not minted twice
 * The anonymous rotating device UUID lives in the `roadSense` section
 * ([com.overdrive.app.roadsense.sync.DeviceId], key `deviceId`). Community reuses it
 * via `DeviceId.current(nowMs)` so the two crowdsource features share ONE identity
 * instead of creating a second. This section therefore holds only the community
 * worker URL + the remembered author display name.
 *
 * All fields have safe defaults so a missing/partial section never crashes a read.
 */
object CommunityConfig {

    private const val SECTION = "community"

    /**
     * Project-run SHARED community backend (mirrors RoadSenseConfig.DEFAULT_WORKER_URL,
     * D-026): the out-of-box default so all users' published automations pool into ONE
     * D1 instance and the browse catalog is shared fleet-wide. A fork can override this
     * on the Automations settings page to run its own pool.
     *
     * NOTE: this URL follows the same account/subdomain convention as roadsense-edge
     * but the `community-edge/` Worker must be DEPLOYED before it responds (see
     * community-edge/README.md). Until then the provider fails gracefully (browse shows
     * an empty/"couldn't load" state; publish reports an error) — the feature is inert,
     * never crashy. The field stays editable so a self-host can point elsewhere; a user
     * who blanks it disables community sync entirely.
     */
    const val DEFAULT_WORKER_URL = "https://community-edge.yash321sri.workers.dev"

    // Keys (also the JSON field names the web settings page reads/writes).
    private const val K_WORKER_URL = "workerUrl"     // user-configurable community-edge URL
    private const val K_AUTHOR_NAME = "authorName"   // remembered display name for publishing
    private const val K_PUBLISHER_ID = "publisherId" // STABLE per-install id — the ownership key

    /** Immutable snapshot of the section — read once per use. */
    data class Snapshot(
        val workerUrl: String?,
        val authorName: String?,
    )

    /**
     * Read the current section. [forceReload]=true forces a cross-UID disk re-read
     * (daemon reading app-written values) — the daemon handler uses this so a
     * just-changed URL / author name is picked up without a restart.
     */
    fun snapshot(forceReload: Boolean = false): Snapshot {
        val root = if (forceReload) UnifiedConfigManager.forceReload()
        else UnifiedConfigManager.loadConfig()
        val s = root.optJSONObject(SECTION) ?: JSONObject()
        return Snapshot(
            // Distinguish UNSET (key absent → project-run shared default so browse
            // works out of the box) from EXPLICITLY-BLANKED (key present but empty →
            // null, which disables sync). This is the only opt-out lever Community
            // has (no separate enabled/crowd toggles), so a user who clears the URL
            // on the settings page to opt out MUST actually stop all cloud calls —
            // baseUrl() returns null → provider fails closed. Diverges deliberately
            // from RoadSenseConfig.kt (which coalesces blank→default, but is benign
            // there because RoadSense has separate enable/crowd gates).
            workerUrl = if (s.has(K_WORKER_URL)) s.optString(K_WORKER_URL, "").ifEmpty { null }
                        else DEFAULT_WORKER_URL,
            authorName = s.optString(K_AUTHOR_NAME, "").ifEmpty { null },
        )
    }

    /**
     * The STABLE community publisher id — the ownership key for publish/delete/rate/
     * report. This is DELIBERATELY separate from [DeviceId.current], which ROTATES every
     * 30 days for RoadSense location-privacy: if community reused the rotating id, then
     * after a rotation the genuine author's new id would no longer match the
     * `author_device` stored on the rows they published, and they could never delete
     * their own uploads. Community has no location-linkage risk (an automation blob isn't
     * geo-anchored) and the id is never exposed to other users (the Worker keeps
     * author_device private and only emits a computed `mine` flag), so a stable id is
     * correct here — ownership must persist for the life of the install.
     *
     * SEEDING: on first use we seed it from the CURRENT [DeviceId.current] value rather
     * than a fresh random UUID, so any automations already published under the current
     * (not-yet-rotated) device id remain owned by this install after the upgrade. If
     * DeviceId can't be read, fall back to a fresh UUID. First call mints + persists;
     * later calls reuse forever (no rotation).
     */
    fun publisherId(): String {
        val root = UnifiedConfigManager.forceReload()
        val existing = root.optJSONObject(SECTION)?.optString(K_PUBLISHER_ID, "")?.ifEmpty { null }
        if (existing != null) return existing
        // Seed from the current rotating device id so already-published rows stay owned;
        // fall back to a fresh UUID if that read fails.
        val seed = try {
            DeviceId.current(System.currentTimeMillis())
        } catch (_: Throwable) { null } ?: UUID.randomUUID().toString()
        try {
            UnifiedConfigManager.updateSection(SECTION, JSONObject().put(K_PUBLISHER_ID, seed))
        } catch (_: Throwable) { /* if persist fails we'll re-seed next call; harmless */ }
        return seed
    }

    /** Persist the remembered author display name (so a user types it once). */
    fun setAuthorName(name: String): Boolean =
        UnifiedConfigManager.updateSection(SECTION, JSONObject().put(K_AUTHOR_NAME, name))

    /** Persist a user-overridden worker URL (self-host / point elsewhere). */
    fun setWorkerUrl(url: String): Boolean =
        UnifiedConfigManager.updateSection(SECTION, JSONObject().put(K_WORKER_URL, url))
}
