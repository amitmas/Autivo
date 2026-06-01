package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recordings API Handler - serves recording list, metadata, and video files.
 * 
 * SOTA: Uses StorageManager for dedicated Overdrive directories with size limits.
 * 
 * Endpoints:
 * - GET /api/recordings - List all recordings with optional filters
 * - GET /api/recordings/dates - Get dates with recordings (for calendar)
 * - GET /api/recordings/stats - Get storage statistics
 * - GET /video/{filename} - Stream video file
 * - GET /thumb/{filename} - Get video thumbnail (cached)
 * - DELETE /api/recordings/{filename} - Delete a recording
 */
public class RecordingsApiHandler {
    
    // Thumbnail cache directory - use parent of recordings dir
    private static String getThumbnailCacheDir() {
        String recordingsPath = StorageManager.getInstance().getRecordingsPath();
        File recordingsDir = new File(recordingsPath);
        File baseDir = recordingsDir.getParentFile();
        return new File(baseDir, "thumbs").getAbsolutePath();
    }
    
    // SOTA: Use StorageManager for paths
    private static String getRecordingsDir() {
        return StorageManager.getInstance().getRecordingsPath();
    }
    
    private static String getSentryDir() {
        return StorageManager.getInstance().getSurveillancePath();
    }
    
    // Legacy paths for backward compatibility (migration)
    private static final String LEGACY_RECORDINGS_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files";
    private static final String LEGACY_SENTRY_DIR = LEGACY_RECORDINGS_DIR + "/sentry_events";
    
    // Filename patterns (support optional _N segment suffix for multi-segment recordings)
    private static final Pattern CAM_PATTERN = Pattern.compile("cam(\\d+)?_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern EVENT_PATTERN = Pattern.compile("event_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern PROXIMITY_PATTERN = Pattern.compile("proximity_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    // SimpleDateFormat is not thread-safe; HTTP server worker threads may
    // invoke parseRecordingUncached concurrently (one fetch per dashboard
    // tab, recording-write events, etc.). All formatters live in ThreadLocal
    // — one set per worker thread, reused for the worker's lifetime. The
    // pre-existing static DATE_FORMAT was a latent race that could return
    // wrong timestamps or throw NumberFormatException under concurrent
    // parses; consolidating here closes that gap too.
    private static final ThreadLocal<SimpleDateFormat> FMT_FILENAME =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_ISO =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_ISO =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_DISPLAY =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM dd, yyyy", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_DISPLAY =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("h:mm a", Locale.US));

    // Per-recording cache keyed by absolute mp4 path. Validated against
    // (mp4 length + mp4 mtime + sidecar mtime); any change invalidates.
    // Without this, every /api/recordings call (UI auto-refresh polls it)
    // re-scans + re-parses every JSON sidecar from disk — a directory of
    // 1000 recordings means 1000 sidecar reads per poll. The cache turns
    // the steady-state cost into one File.exists() + two lastModified()
    // calls per recording.
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedRecording> RECORDING_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ==================================================================
    // Inverted index — lazy, in-memory, populated alongside RECORDING_CACHE
    // ==================================================================
    //
    // For per-filter listing/places endpoints we'd otherwise iterate every
    // RECORDING_CACHE entry on every request. The index has two halves:
    //
    //   filenameMeta   : filename → IndexEntry (timestamp + reverse keys
    //                    so we can clean up the inverted maps on remove)
    //                    USED on read by listPlaces — skips the JSON walk
    //                    by reading placeKey/placeLabel directly.
    //
    //   placeIdx       : place-short-key (lower) → Set<filename>
    //   severityIdx    : "ALERT"|"CRITICAL"|... → Set<filename>
    //   proximityIdx   : "VERY_CLOSE"|...       → Set<filename>
    //   classIdx       : "person"|"vehicle"|... → Set<filename>
    //   typeIdx        : "normal"|"sentry"|"proximity" → Set<filename>
    //                    Maintained for future use — once recording
    //                    libraries grow past the ≤2K typical, scanAndFilter
    //                    can intersect bucket sets to skip directory walks
    //                    for filtered queries. Today the cost is one
    //                    Set add/remove per put/remove (~µs); we keep the
    //                    structures populated so the future switch is a
    //                    diff in `scanAndFilter` rather than a rebuild.
    //
    // Memory bound: HARD cap of INDEX_MAX_ENTRIES filenames. Per-entry
    // realistic worst case ≈ 800 B (IndexEntry + 5 bucket Node overheads
    // + filenameMeta Node). At 20K cap that's ~16 MB heap ceiling —
    // within budget on a 256 MB-max daemon process, but if the device
    // sees larger libraries this should be re-tuned. When the cap is
    // hit, the oldest entries (lowest timestamp) are evicted from BOTH
    // the index AND RECORDING_CACHE together so the next /api/recordings
    // request rebuilds them on-demand from disk.
    //
    // Race: every mutation acquires `INDEX_LOCK`. Reads use the maps'
    // own concurrent-collection visibility guarantees — bucket Sets are
    // newSetFromMap(ConcurrentHashMap) values, so concurrent
    // Set.add/remove/contains are safe.

    private static final int INDEX_MAX_ENTRIES = 20_000;

    private static final Object INDEX_LOCK = new Object();

    /** filename → bucket keys. Used to dismantle the inverted maps on remove. */
    private static final java.util.concurrent.ConcurrentHashMap<String, IndexEntry> filenameMeta =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> placeIdx =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> severityIdx =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> proximityIdx =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> classIdx =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> typeIdx =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class IndexEntry {
        final String filename;
        final long timestamp;
        final String type;            // "normal" | "sentry" | "proximity"
        final String placeKey;        // lowercase short label, "" when no place
        final String placeLabel;      // canonical mixed-case form; used by
                                      // the chip-row endpoint so the index
                                      // is self-sufficient and listPlaces
                                      // doesn't re-read JSON for the label.
        final String severity;        // "" when none
        final String proximity;       // "" when none
        final java.util.Set<String> classes; // lowercase class group names

        IndexEntry(String filename, long timestamp, String type,
                   String placeKey, String placeLabel,
                   String severity, String proximity,
                   java.util.Set<String> classes) {
            this.filename = filename;
            this.timestamp = timestamp;
            this.type = type == null ? "" : type;
            this.placeKey = placeKey == null ? "" : placeKey;
            this.placeLabel = placeLabel == null ? "" : placeLabel;
            this.severity = severity == null ? "" : severity;
            this.proximity = proximity == null ? "" : proximity;
            this.classes = classes == null
                    ? java.util.Collections.<String>emptySet()
                    : classes;
        }
    }

    /**
     * Insert or update the index for a parsed recording. Mirrors the
     * `RECORDING_CACHE.put` lifecycle: every successful parse calls this
     * with the current parse result. Re-indexes existing entries by
     * removing the old bucket memberships first (read from filenameMeta
     * snapshot) — without this step a sidecar update that changes a
     * recording's place would leave it indexed under both old and new
     * place keys.
     */
    private static void indexPut(JSONObject rec) {
        if (rec == null) return;
        String filename = rec.optString("filename", "");
        if (filename.isEmpty()) return;
        synchronized (INDEX_LOCK) {
            // Remove the OLD entry's bucket memberships before inserting
            // the new one. Without this, a re-parse that changes any
            // bucket key (e.g. place resolved async after first parse)
            // would leave the file indexed under both old + new buckets.
            IndexEntry old = filenameMeta.get(filename);
            if (old != null) {
                indexRemoveFromBuckets(old);
            }

            long timestamp = rec.optLong("timestamp", 0L);
            String type = rec.optString("type", "");
            String severity = rec.optString("peakSeverity", "");
            String proximity = rec.optString("peakProximity", "");
            String placeKey = "";
            String placeLabel = "";
            JSONObject placeObj = rec.optJSONObject("place");
            if (placeObj != null) {
                String shortLabel = placeObj.optString("short", "");
                if (!shortLabel.isEmpty()) {
                    placeKey = shortLabel.toLowerCase(Locale.US);
                    placeLabel = shortLabel;
                }
            }
            java.util.Set<String> classes = new java.util.HashSet<>(4);
            org.json.JSONArray actors = rec.optJSONArray("actors");
            if (actors != null) {
                for (int i = 0; i < actors.length(); i++) {
                    JSONObject a = actors.optJSONObject(i);
                    if (a == null) continue;
                    String c = a.optString("class", "");
                    if (!c.isEmpty()) classes.add(c.toLowerCase(Locale.US));
                }
            }

            IndexEntry entry = new IndexEntry(filename, timestamp, type,
                    placeKey, placeLabel, severity, proximity, classes);
            filenameMeta.put(filename, entry);
            indexAddToBuckets(entry);

            // Memory cap enforcement. Eviction is amortised: we only do a
            // sweep when the size exceeds the cap by 5% so the cost
            // doesn't fire on every put once we're near the boundary.
            if (filenameMeta.size() > INDEX_MAX_ENTRIES * 21 / 20) {
                indexEvictOldest(filenameMeta.size() - INDEX_MAX_ENTRIES);
            }
        }
    }

    /** Remove `filename` from both the parse cache AND the inverted index. */
    private static void indexRemove(String filename) {
        if (filename == null || filename.isEmpty()) return;
        synchronized (INDEX_LOCK) {
            IndexEntry e = filenameMeta.remove(filename);
            if (e != null) indexRemoveFromBuckets(e);
        }
    }

    /** Remove `entry` from every inverted map. Caller holds INDEX_LOCK. */
    private static void indexRemoveFromBuckets(IndexEntry entry) {
        unbucket(typeIdx,      entry.type,     entry.filename);
        if (!entry.placeKey.isEmpty())  unbucket(placeIdx,     entry.placeKey, entry.filename);
        if (!entry.severity.isEmpty())  unbucket(severityIdx,  entry.severity, entry.filename);
        if (!entry.proximity.isEmpty()) unbucket(proximityIdx, entry.proximity, entry.filename);
        for (String cls : entry.classes) unbucket(classIdx, cls, entry.filename);
    }

    /** Insert `entry` into every inverted map. Caller holds INDEX_LOCK. */
    private static void indexAddToBuckets(IndexEntry entry) {
        if (!entry.type.isEmpty())      bucket(typeIdx,      entry.type,     entry.filename);
        if (!entry.placeKey.isEmpty())  bucket(placeIdx,     entry.placeKey, entry.filename);
        if (!entry.severity.isEmpty())  bucket(severityIdx,  entry.severity, entry.filename);
        if (!entry.proximity.isEmpty()) bucket(proximityIdx, entry.proximity, entry.filename);
        for (String cls : entry.classes) bucket(classIdx, cls, entry.filename);
    }

    private static void bucket(java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> map,
                               String key, String filename) {
        java.util.Set<String> set = map.get(key);
        if (set == null) {
            set = java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
            java.util.Set<String> prev = map.putIfAbsent(key, set);
            if (prev != null) set = prev;
        }
        set.add(filename);
    }

    private static void unbucket(java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> map,
                                 String key, String filename) {
        java.util.Set<String> set = map.get(key);
        if (set == null) return;
        set.remove(filename);
        // Don't remove the empty bucket from the map — we'd race with a
        // concurrent bucket() that just got the same Set ref. The empty
        // bucket is cheap (HashMap entry); periodic prune cleans these.
    }

    /**
     * Evict the {@code count} oldest entries (by recording timestamp).
     * Caller holds INDEX_LOCK. Drops both filenameMeta + inverted-map
     * memberships AND removes them from RECORDING_CACHE so the parse
     * cache stays in sync. The next list request rebuilds these from
     * disk on-demand.
     *
     * Cache scrub is O(cache.size) total — we build the evicted-filename
     * Set first, then make a single pass over RECORDING_CACHE removing
     * any entry whose path ends with `/<evicted-filename>`. Previous
     * implementation had an inner loop per evicted entry which was
     * O(cache.size * count) — at 20K cache + 1K evictions that's ~20M
     * comparisons inside the lock.
     */
    private static void indexEvictOldest(int count) {
        if (count <= 0 || filenameMeta.isEmpty()) return;
        java.util.List<IndexEntry> all = new java.util.ArrayList<>(filenameMeta.values());
        all.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        int actual = Math.min(count, all.size());

        // Pre-collect filenames-to-evict so the single cache pass below
        // can match in O(1) per cache entry. Add the leading slash here
        // so the suffix match below is one substring check.
        java.util.Set<String> evictedSuffixes = new java.util.HashSet<>(actual * 2);
        for (int i = 0; i < actual; i++) {
            IndexEntry e = all.get(i);
            filenameMeta.remove(e.filename);
            indexRemoveFromBuckets(e);
            evictedSuffixes.add("/" + e.filename);
        }

        // Single pass over the parse cache. Each entry's key is checked
        // once against the Set instead of looped against every evicted
        // filename.
        java.util.Iterator<java.util.Map.Entry<String, CachedRecording>> it =
                RECORDING_CACHE.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, CachedRecording> ce = it.next();
            String key = ce.getKey();
            int slash = key.lastIndexOf('/');
            String suffix = (slash >= 0) ? key.substring(slash) : "/" + key;
            if (evictedSuffixes.contains(suffix)) {
                it.remove();
            }
        }
        CameraDaemon.log("RecordingsApiHandler index evicted " + actual + " oldest entries");
    }

    /**
     * Drop a cache entry for the given mp4 absolute path. Callers outside
     * this class (loop rotation in HardwareEventRecorderGpu, the Kotlin
     * RecordingScanner, manual SD-card maintenance) should call this when
     * they delete an .mp4 so the API cache doesn't return phantom entries.
     * No-op when the key isn't present. Also tears down the inverted
     * index entry — both stay in lockstep.
     */
    public static void invalidateRecordingCache(String absMp4Path) {
        if (absMp4Path == null) return;
        RECORDING_CACHE.remove(absMp4Path);
        // Strip the trailing filename so the index can drop its entry.
        // Index is keyed on filename; absolute paths from the same .mp4
        // map to one filename across SD/internal mirrors.
        int slash = absMp4Path.lastIndexOf('/');
        String filename = (slash >= 0 && slash + 1 < absMp4Path.length())
                ? absMp4Path.substring(slash + 1)
                : absMp4Path;
        indexRemove(filename);
    }

    /**
     * Periodic prune. Removes entries whose underlying .mp4 no longer exists.
     * Call from a long-running daemon's hourly maintenance pass to keep the
     * cache from growing unbounded across months of uptime.
     */
    /**
     * Pre-populate the parse cache + inverted index without serving a
     * request. Called from the daemon's post-startup background thread
     * so the first user-visible /api/recordings call doesn't pay the
     * full directory-walk + sidecar-parse cost inline.
     *
     * Safe to call from any thread; the underlying scanAndFilter walks
     * are idempotent and the cache mutations are concurrent-collection
     * + INDEX_LOCK protected. Returns silently on any error.
     */
    public static void warmupCache() {
        try {
            // No filters — populate the cache for everything on disk.
            // The pre-existing scanAndFilter is the canonical entry
            // point so the warmup matches what the API would do
            // exactly (same dedup, same sort, same parse-then-cache
            // discipline).
            scanAndFilter(null, null, null, null, null, null);
            CameraDaemon.log("RecordingsApiHandler warmup: "
                    + RECORDING_CACHE.size() + " recordings cached, "
                    + filenameMeta.size() + " indexed");
        } catch (Throwable t) {
            CameraDaemon.log("RecordingsApiHandler warmup failed: " + t.getMessage());
        }
    }

    public static void pruneRecordingCache() {
        java.util.Iterator<java.util.Map.Entry<String, CachedRecording>> it =
                RECORDING_CACHE.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            java.util.Map.Entry<String, CachedRecording> e = it.next();
            String absPath = e.getKey();
            if (!new File(absPath).exists()) {
                it.remove();
                // Drop the matching index entry too — pruneRecordingCache
                // is the daemon's hourly garbage-collect path; without
                // this, an SD-card-mounted file deleted out-of-band would
                // leave a stale place chip indefinitely.
                int slash = absPath.lastIndexOf('/');
                String filename = (slash >= 0 && slash + 1 < absPath.length())
                        ? absPath.substring(slash + 1)
                        : absPath;
                indexRemove(filename);
                removed++;
            }
        }
        if (removed > 0) {
            CameraDaemon.log("RECORDING_CACHE pruned " + removed + " stale entries");
        }

        // Empty inverted-bucket cleanup. Over months of churn, transient
        // place keys (a one-clip side-trip district) accumulate empty
        // Sets in the bucket maps. Cheap (~64 B each) but unbounded
        // without this sweep. Per-entry cost is one Set.isEmpty() call,
        // so the cleanup itself is O(distinct-keys-ever-seen) on each
        // prune cycle — bounded by the index's own lifetime entry count.
        //
        // Sharded sweep: take + release INDEX_LOCK between each map so
        // a long key-cardinality across all five maps doesn't block
        // concurrent indexPut/indexRemove for the entire walk. A
        // single sweep can still have a long lock-hold for one map,
        // but the worst case is now bounded by the SLOWEST map's
        // size rather than the SUM. With 5K distinct keys per map,
        // hold time per shard is ~1-5 ms vs ~25 ms for the unsharded
        // version. Concurrent puts can interleave between shards.
        synchronized (INDEX_LOCK) { pruneEmptyBuckets(placeIdx); }
        synchronized (INDEX_LOCK) { pruneEmptyBuckets(severityIdx); }
        synchronized (INDEX_LOCK) { pruneEmptyBuckets(proximityIdx); }
        synchronized (INDEX_LOCK) { pruneEmptyBuckets(classIdx); }
        synchronized (INDEX_LOCK) { pruneEmptyBuckets(typeIdx); }
    }

    /** Caller holds INDEX_LOCK. Drops bucket entries whose Set is empty. */
    private static void pruneEmptyBuckets(
            java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> map) {
        java.util.Iterator<java.util.Map.Entry<String, java.util.Set<String>>> it =
                map.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, java.util.Set<String>> e = it.next();
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    private static final class CachedRecording {
        final long mp4Length;
        final long mp4Mtime;
        final long sidecarMtime;  // 0 if absent
        final String json;        // serialized JSONObject — cheaper to clone than to rebuild
        CachedRecording(long mp4Length, long mp4Mtime, long sidecarMtime, String json) {
            this.mp4Length = mp4Length;
            this.mp4Mtime = mp4Mtime;
            this.sidecarMtime = sidecarMtime;
            this.json = json;
        }
    }
    
    /**
     * Handle recordings API requests.
     * @return true if handled, false if not a recordings endpoint
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // List recordings (with optional query params)
        if ((path.equals("/api/recordings") || path.startsWith("/api/recordings?")) && method.equals("GET")) {
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            Map<String, String> params = parseQuery(query);
            String type = params.get("type");
            String date = params.get("date");
            int page = parseIntParam(params.get("page"), 1);
            int pageSize = parseIntParam(params.get("pageSize"), 12);
            // Clamp pageSize to reasonable limits
            pageSize = Math.max(1, Math.min(pageSize, 50));
            // v3 filters (item 6): comma-separated lists of class groups, severities,
            // and proximity bands. Empty / missing = no filter.
            String classes = params.get("class");        // e.g. "person,vehicle"
            String severities = params.get("severity");  // e.g. "ALERT,CRITICAL"
            String proximities = params.get("proximity"); // e.g. "VERY_CLOSE,CLOSE"
            // Place filter (item 7): single short label, case-insensitive.
            // Server-side so pagination + totalCount stay honest under the
            // filter — client-side filtering would let "page 2 of 5" hide
            // matching clips on later pages.
            String place = params.get("place");
            listRecordings(out, type, date, page, pageSize,
                    classes, severities, proximities, place);
            return true;
        }

        // Distinct places list (top-N by count) — drives the dynamic
        // Place chip row in events.html. Scoped by the SAME filter
        // context as /api/recordings (minus the place filter itself),
        // so e.g. switching to the Sentry tab refreshes the chip set
        // to "places where sentry events happened" instead of every
        // place across every type.
        if ((path.equals("/api/recordings/places") || path.startsWith("/api/recordings/places?"))
                && method.equals("GET")) {
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            Map<String, String> params = parseQuery(query);
            String type = params.get("type");
            String date = params.get("date");
            String classes = params.get("class");
            String severities = params.get("severity");
            String proximities = params.get("proximity");
            listPlaces(out, type, date, classes, severities, proximities);
            return true;
        }

        // Get dates with recordings
        if (path.equals("/api/recordings/dates") && method.equals("GET")) {
            getDatesWithRecordings(out);
            return true;
        }
        
        // Get storage stats
        if (path.equals("/api/recordings/stats") && method.equals("GET")) {
            getStorageStats(out);
            return true;
        }
        
        // Serve thumbnail
        if (path.startsWith("/thumb/")) {
            String filename = path.substring(7);
            serveThumbnail(out, filename);
            return true;
        }
        
        // Stream video file
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, null, null);
            return true;
        }
        
        // Batch delete recordings
        if (path.equals("/api/recordings/batch-delete") && method.equals("POST")) {
            batchDeleteRecordings(out, body);
            return true;
        }

        // In-flight recording probe — used by events.js to show a pinned
        // "Recording in progress" placeholder when the user taps a fresh
        // notification before the .mp4.tmp has been finalized to .mp4.
        if (path.startsWith("/api/recordings/inflight/") && method.equals("GET")) {
            String filename = path.substring("/api/recordings/inflight/".length());
            serveInflightStatus(out, filename);
            return true;
        }

        // Delete recording
        if (path.startsWith("/api/recordings/") && method.equals("DELETE")) {
            String filename = path.substring(16);
            deleteRecording(out, filename);
            return true;
        }
        
        // SOTA: Get event timeline for a recording (JSON sidecar)
        if (path.startsWith("/api/events/") && method.equals("GET")) {
            String filename = path.substring(12);
            serveEventTimeline(out, filename);
            return true;
        }
        
        return false;
    }
    
    private static int parseIntParam(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Handle with Range header support for video seeking and conditional GET
     * (If-None-Match) for ETag-based 304 responses on cached recordings.
     */
    public static boolean handleWithRange(String method, String path, String body,
                                          String rangeHeader, String ifNoneMatchHeader,
                                          OutputStream out) throws Exception {
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, rangeHeader, ifNoneMatchHeader);
            return true;
        }
        return handle(method, path, body, out);
    }
    
    // Background thumbnail generator
    private static final java.util.concurrent.ExecutorService thumbExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final Set<String> pendingThumbs = java.util.Collections.synchronizedSet(new HashSet<>());
    
    /**
     * Serve a cached thumbnail for a video file.
     * Returns placeholder immediately if not cached, generates in background.
     */
    private static void serveThumbnail(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }

        // Direct sidecar JPEG hits — heroes ("event_xxx.jpg") or per-actor
        // ("thumb_event_xxx_a17_9300.jpg") written by ThumbnailBuffer next to
        // the MP4. Looking these up here means events.js can use a single URL
        // shape (/thumb/<filename>) for both video-frame and AI thumbnails.
        if (filename.toLowerCase(Locale.US).endsWith(".jpg")) {
            File jpegFile = findSiblingJpeg(filename);
            if (jpegFile != null && jpegFile.exists() && jpegFile.length() > 0) {
                HttpResponse.sendImage(out, jpegFile, "image/jpeg");
                return;
            }
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_thumbnail_not_found_with_filename", filename));
            return;
        }

        // Check cache first
        File cacheDir = new File(getThumbnailCacheDir());
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        String thumbName = filename.replace(".mp4", ".jpg");
        File thumbFile = new File(cacheDir, thumbName);

        // SOTA: if a v3 hero JPEG exists alongside the MP4, prefer it.
        // It's the peak-severity moment captured during the recording rather
        // than a generic frame at +1s. Backwards-compat: legacy clips without
        // a hero file fall through to the cache + MediaMetadataRetriever path.
        File heroSibling = findSiblingJpeg(thumbName);
        if (heroSibling != null && heroSibling.exists() && heroSibling.length() > 0) {
            HttpResponse.sendImage(out, heroSibling, "image/jpeg");
            return;
        }

        // If cached thumbnail exists and is valid, serve it immediately
        if (thumbFile.exists() && thumbFile.length() > 0) {
            HttpResponse.sendImage(out, thumbFile, "image/jpeg");
            return;
        }

        // Find the source video file. allowInFlightTmp=true so a notification
        // tapped within seconds of motion still gets a hero image: the
        // MediaMetadataRetriever can read sync frames from <name>.mp4.tmp
        // before the muxer finalises the moov atom on close.
        File videoFile = findVideoFile(filename, true);
        if (videoFile == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_video_not_found_with_filename", filename));
            return;
        }

        // Queue background generation if not already pending. add() returns
        // false when the element was already present, so a single atomic
        // call avoids the check-then-act race where two concurrent requests
        // both pass `contains()` and submit overlapping FileOutputStreams to
        // the same thumb file.
        if (pendingThumbs.add(filename)) {
            final File vf = videoFile;
            final File tf = thumbFile;
            final String fn = filename;
            thumbExecutor.submit(() -> {
                try {
                    byte[] data = generateThumbnail(vf);
                    if (data != null) {
                        try (FileOutputStream fos = new FileOutputStream(tf)) {
                            fos.write(data);
                        }
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Background thumb gen failed: " + e.getMessage());
                } finally {
                    pendingThumbs.remove(fn);
                }
            });
        }
        
        // Return 202 Accepted with retry hint - client should retry
        String headers = "HTTP/1.1 202 Accepted\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Retry-After: 1\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write("{\"status\":\"generating\"}".getBytes());
        out.flush();
    }
    
    /**
     * Generate a thumbnail from a video file using MediaMetadataRetriever.
     * Extracts frame at 1 second mark, scales to 160x90 for efficiency.
     */
    private static byte[] generateThumbnail(File videoFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        // setDataSource(String) calls ActivityThread.currentApplication().getPackageManager()
        // for MIME lookup. The daemon has no registered Application, so that NPEs on DiLink5.
        // The FileDescriptor overload skips the package-manager probe entirely.
        try (FileInputStream fis = new FileInputStream(videoFile)) {
            retriever.setDataSource(fis.getFD());

            // Get frame at 1 second (1000000 microseconds)
            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                // Try frame at 0 if 1 second fails
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            
            if (frame == null) {
                return null;
            }
            
            // Scale down to thumbnail size (320x180 for 16:9 aspect)
            int targetWidth = 320;
            int targetHeight = 180;
            Bitmap scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);
            
            // Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            
            // Clean up
            if (scaled != frame) {
                scaled.recycle();
            }
            frame.recycle();
            
            return baos.toByteArray();
        } catch (Exception e) {
            CameraDaemon.log("Thumbnail generation failed: " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {}
        }
    }
    
    /**
     * Reports whether a given filename is currently being written by the
     * encoder as {@code <filename>.tmp}. Used by the events page to display
     * a pinned "Recording in progress" placeholder when the user taps a
     * notification before the post-record window finalizes the file.
     *
     * <p>Response shape:
     * <pre>{ "inflight": true, "filename": "...", "sizeBytes": 1234567 }</pre>
     * or
     * <pre>{ "inflight": false, "filename": "..." }</pre>
     *
     * <p>{@code inflight=false} can mean either "the file finished and was
     * renamed" (success) or "no such recording exists" — the caller already
     * reloads the recordings list when the probe flips, so the success and
     * not-found branches converge in the UI.
     */
    private static void serveInflightStatus(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename == null || filename.isEmpty()
                || filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        File tmp = findInflightTmp(filename);
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("filename", filename);
            json.put("inflight", tmp != null);
            if (tmp != null) {
                json.put("sizeBytes", tmp.length());
            }
        } catch (Exception ignored) {}
        HttpResponse.sendJson(out, json.toString());
    }

    /**
     * Locate {@code <filename>.tmp} across all recording storage roots.
     * Returns null when no in-flight write is happening.
     */
    private static File findInflightTmp(String filename) {
        StorageManager sm = StorageManager.getInstance();
        String tmpName = filename + ".tmp";
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        return null;
    }

    /**
     * Find a video file across all storage locations.
     * Uses StorageManager to get all possible directories without hardcoding paths.
     */
    private static File findVideoFile(String filename) {
        return findVideoFile(filename, false);
    }

    /**
     * @param allowInFlightTmp when true, fall through to {@code <filename>.tmp}
     *        for files still being written by HardwareEventRecorderGpu. Useful
     *        for thumbnail generation (MediaMetadataRetriever reads frames
     *        without needing the moov atom). NOT safe for video streaming —
     *        a .tmp lacks the moov atom and the {@code <video>} element will
     *        fail to load it. Streaming MUST use the default false.
     */
    private static File findVideoFile(String filename, boolean allowInFlightTmp) {
        StorageManager sm = StorageManager.getInstance();

        // Search all recordings directories (active + alternate)
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // Search all surveillance directories (active + alternate)
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // Search all proximity directories (active + alternate)
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // Check legacy recordings location
        File legacyFile = new File(LEGACY_RECORDINGS_DIR, filename);
        if (legacyFile.exists() && legacyFile.canRead() && legacyFile.length() > 0) return legacyFile;

        // Check legacy sentry location
        File legacySentryFile = new File(LEGACY_SENTRY_DIR, filename);
        if (legacySentryFile.exists() && legacySentryFile.canRead() && legacySentryFile.length() > 0) return legacySentryFile;

        // In-flight fallback (thumbnails only): a notification fires the moment
        // startRecording() returns, but the file on disk is still
        // <name>.mp4.tmp until closeEventRecording() finishes (10-15s
        // post-record). Without this fallback, a tap within that window
        // fetches /thumb/<name> and gets 404, so the push notification banner
        // shows no hero image. We DON'T enable this for video streaming
        // because a .tmp lacks the moov atom.
        if (allowInFlightTmp) {
            String tmpName = filename + ".tmp";
            for (File dir : sm.getAllRecordingsDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
            for (File dir : sm.getAllSurveillanceDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
            for (File dir : sm.getAllProximityDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
        }

        return null;
    }
    
    /**
     * Locate a JPEG sibling next to a recording. Used to serve hero / per-actor
     * thumbnails that ThumbnailBuffer writes alongside the MP4. Same security
     * + directory-search rules as findVideoFile.
     */
    private static File findSiblingJpeg(String jpegName) {
        if (jpegName == null || jpegName.isEmpty()) return null;
        if (jpegName.contains("..") || jpegName.contains("/")) return null;
        StorageManager sm = StorageManager.getInstance();
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        File legacy = new File(LEGACY_RECORDINGS_DIR, jpegName);
        if (legacy.exists() && legacy.canRead() && legacy.length() > 0) return legacy;
        File legacySentry = new File(LEGACY_SENTRY_DIR, jpegName);
        if (legacySentry.exists() && legacySentry.canRead() && legacySentry.length() > 0) return legacySentry;
        return null;
    }

    private static Set<String> splitCsvLower(String csv) {
        if (csv == null || csv.isEmpty()) return java.util.Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.US);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<String> splitCsvUpper(String csv) {
        if (csv == null || csv.isEmpty()) return java.util.Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toUpperCase(Locale.US);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }


    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;

        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                // URL-decode both halves so values with spaces or
                // unicode (e.g. place="Petaling Jaya" → "Petaling%20Jaya"
                // or "Cheras" → "Cheras") survive the round-trip.
                //
                // Important: java.net.URLDecoder.decode honours form-
                // urlencoded semantics where `+` decodes to space. Our
                // web client uses encodeURIComponent which emits `%20`
                // for spaces and passes literal `+` through unchanged.
                // Pre-escape `+` to `%2B` BEFORE decoding so a real-world
                // place name like "Marina Bay+" keeps its plus sign
                // instead of becoming "Marina Bay " (trailing space).
                // Pre-feature filters (class/severity/proximity) used a
                // fixed vocab without `+`; place names are user / OSM
                // strings and may contain it.
                //
                // Decode failure falls back to the raw value so a
                // malformed param can't break the request.
                String key = kv[0];
                String val = kv[1];
                try {
                    key = java.net.URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
                    val = java.net.URLDecoder.decode(val.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {}
                params.put(key, val);
            }
        }
        return params;
    }
    
    /**
     * List all recordings with optional filters and pagination.
     */
    private static void listRecordings(OutputStream out, String typeFilter, String dateFilter,
                                       int page, int pageSize) throws Exception {
        listRecordings(out, typeFilter, dateFilter, page, pageSize, null, null, null, null);
    }

    private static void listRecordings(OutputStream out, String typeFilter, String dateFilter,
                                       int page, int pageSize,
                                       String classFilter, String severityFilter,
                                       String proximityFilter,
                                       String placeFilter) throws Exception {
        List<JSONObject> recordings = scanAndFilter(typeFilter, dateFilter,
                classFilter, severityFilter, proximityFilter, placeFilter);

        // Pagination
        int totalCount = recordings.size();
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        if (totalPages == 0) totalPages = 1;

        // Clamp page to valid range
        page = Math.max(1, Math.min(page, totalPages));

        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalCount);

        List<JSONObject> pageRecordings = startIndex < totalCount
            ? recordings.subList(startIndex, endIndex)
            : new ArrayList<>();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordings", new JSONArray(pageRecordings));
        response.put("totalCount", totalCount);
        response.put("totalPages", totalPages);
        response.put("page", page);
        response.put("pageSize", pageSize);

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Distinct places across the filtered set. Same filter inputs as
     * {@link #listRecordings} except the place filter itself — chips
     * are derived from "places that are reachable under the current
     * type/date/class/severity/proximity context," NOT from the
     * already-narrowed-by-place subset (that would always return only
     * the active chip).
     *
     * <p>Returns top {@link #PLACES_LIMIT} entries by count, alpha
     * tiebreak, with bucketed display label = canonical mixed-case
     * picked from the most recent clip in each bucket.
     */
    private static void listPlaces(OutputStream out, String typeFilter, String dateFilter,
                                   String classFilter, String severityFilter,
                                   String proximityFilter) throws Exception {
        List<JSONObject> recordings = scanAndFilter(typeFilter, dateFilter,
                classFilter, severityFilter, proximityFilter, /* placeFilter */ null);

        // Index fast path: scanAndFilter has already populated
        // filenameMeta during its parseRecording loop (every successful
        // parse calls indexPut). For each recording in the filtered set,
        // we read the placeKey from the index INSTEAD of re-walking the
        // recording's JSON. The full canonical-mixed-case label still
        // needs to come from the JSON (the index stores only the
        // lowercase key), but only for the bucket-leader (newest clip).
        //
        // Net effect: cache-hit case is O(N) Set lookups vs. O(N) JSON
        // walks. ~5-10x faster on the hot path. Cold cache falls back
        // naturally because filenameMeta.get returns null and we just
        // re-derive from the JSON like before.
        java.util.Map<String, long[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> labels = new java.util.HashMap<>();

        for (JSONObject rec : recordings) {
            String filename = rec.optString("filename", "");
            String key = "";
            String shortLabel = "";
            // Index fast path: read placeKey + placeLabel directly from
            // the index — no JSON walk needed. Index is populated by
            // parseRecording on cache miss. Cache hit means the entry
            // was already indexed by an earlier miss, so filenameMeta
            // is in sync with the cache by construction. Only miss path
            // is index eviction (in-flight), which falls through to the
            // JSON fallback below.
            if (!filename.isEmpty()) {
                IndexEntry entry = filenameMeta.get(filename);
                if (entry != null && !entry.placeKey.isEmpty()) {
                    key = entry.placeKey;
                    shortLabel = entry.placeLabel;
                }
            }
            if (key.isEmpty()) {
                // Fallback: index doesn't have this filename yet (cold
                // start, race with eviction). Walk the JSON like the
                // pre-index path did.
                JSONObject place = rec.optJSONObject("place");
                if (place == null) continue;
                shortLabel = place.optString("short", "");
                if (shortLabel.isEmpty()) continue;
                key = shortLabel.toLowerCase(Locale.US);
            }
            long ts = rec.optLong("timestamp", 0L);
            long[] entry = counts.get(key);
            if (entry == null) {
                counts.put(key, new long[] { 1L, ts });
                labels.put(key, shortLabel);
            } else {
                entry[0]++;
                if (ts > entry[1]) {
                    entry[1] = ts;
                    labels.put(key, shortLabel);
                }
            }
        }

        // Sort: count desc, then lowercase key alpha asc — stable across
        // calls so the chip row doesn't reshuffle on every refresh.
        java.util.List<java.util.Map.Entry<String, long[]>> sorted =
                new java.util.ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            long ca = a.getValue()[0], cb = b.getValue()[0];
            if (ca != cb) return Long.compare(cb, ca);
            return a.getKey().compareTo(b.getKey());
        });

        JSONArray places = new JSONArray();
        int emitted = 0;
        for (java.util.Map.Entry<String, long[]> e : sorted) {
            if (emitted >= PLACES_LIMIT) break;
            JSONObject row = new JSONObject();
            row.put("key", e.getKey());            // matches what /api/recordings?place= expects
            row.put("label", labels.get(e.getKey()));
            row.put("count", e.getValue()[0]);
            places.put(row);
            emitted++;
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("places", places);
        response.put("totalDistinct", counts.size());
        HttpResponse.sendJson(out, response.toString());
    }

    /** Cap mirrored across native + web. */
    private static final int PLACES_LIMIT = 8;

    /**
     * Scan + sort + dedupe + filter helper shared by listRecordings and
     * listPlaces. Pulled out so server-side place filtering can reuse
     * the same actor/severity/proximity gates and produce a consistent
     * universe for the chip-derivation endpoint.
     */
    private static List<JSONObject> scanAndFilter(String typeFilter, String dateFilter,
                                                  String classFilter, String severityFilter,
                                                  String proximityFilter,
                                                  String placeFilter) {
        List<JSONObject> recordings = new ArrayList<>();
        StorageManager sm = StorageManager.getInstance();

        // Scan normal recordings from ALL locations (active + alternate + legacy)
        if (typeFilter == null || typeFilter.equals("normal")) {
            for (File dir : sm.getAllRecordingsDirs()) {
                scanDirectory(dir, "normal", recordings, dateFilter);
            }
            File legacyDir = new File(LEGACY_RECORDINGS_DIR);
            if (legacyDir.exists()) {
                scanDirectory(legacyDir, "normal", recordings, dateFilter);
            }
        }

        // Scan sentry events from ALL locations (active + alternate + legacy)
        if (typeFilter == null || typeFilter.equals("sentry")) {
            for (File dir : sm.getAllSurveillanceDirs()) {
                scanDirectory(dir, "sentry", recordings, dateFilter);
            }
            File legacySentryDir = new File(LEGACY_SENTRY_DIR);
            if (legacySentryDir.exists()) {
                scanDirectory(legacySentryDir, "sentry", recordings, dateFilter);
            }
        }

        // Scan proximity events from ALL locations (active + alternate)
        if (typeFilter == null || typeFilter.equals("proximity")) {
            for (File dir : sm.getAllProximityDirs()) {
                scanDirectory(dir, "proximity", recordings, dateFilter);
            }
        }

        // Sort by timestamp descending (newest first)
        recordings.sort((a, b) -> Long.compare(
            b.optLong("timestamp", 0),
            a.optLong("timestamp", 0)
        ));

        // Deduplicate by filename — same file may appear from multiple scan locations
        // (e.g., SD card + internal storage fallback). Keep the first occurrence.
        Set<String> seenFilenames = new HashSet<>();
        recordings.removeIf(rec -> {
            String name = rec.optString("filename", "");
            if (seenFilenames.contains(name)) return true;
            seenFilenames.add(name);
            return false;
        });

        // v3 filters (item 6): each filter is comma-separated; recording must
        // match at least one value in each non-empty filter. Static actors
        // (parked cars, idle people) are intentionally excluded — chips
        // surface threats, not scenery.
        Set<String> classSet = splitCsvLower(classFilter);
        Set<String> sevSet   = splitCsvUpper(severityFilter);
        Set<String> proxSet  = splitCsvUpper(proximityFilter);
        // Place filter (item 7): single short label, lowercase. Untagged
        // clips (no place block) are EXCLUDED when active — same UX as
        // the actor/severity rule. Match is case-insensitive.
        final String placeKey = (placeFilter == null || placeFilter.isEmpty())
                ? null
                : placeFilter.trim().toLowerCase(Locale.US);

        if (!classSet.isEmpty() || !sevSet.isEmpty() || !proxSet.isEmpty() || placeKey != null) {
            recordings.removeIf(rec -> {
                if (!sevSet.isEmpty()) {
                    String sev = rec.optString("peakSeverity", "");
                    if (sev.isEmpty() || !sevSet.contains(sev)) return true;
                }
                if (!proxSet.isEmpty()) {
                    String prox = rec.optString("peakProximity", "");
                    if (prox.isEmpty() || !proxSet.contains(prox)) return true;
                }
                if (!classSet.isEmpty()) {
                    org.json.JSONArray actors = rec.optJSONArray("actors");
                    if (actors == null || actors.length() == 0) return true;
                    boolean any = false;
                    for (int i = 0; i < actors.length(); i++) {
                        JSONObject a = actors.optJSONObject(i);
                        if (a == null) continue;
                        if (classSet.contains(a.optString("class", "").toLowerCase(Locale.US))) {
                            any = true; break;
                        }
                    }
                    if (!any) return true;
                }
                if (placeKey != null) {
                    JSONObject place = rec.optJSONObject("place");
                    if (place == null) return true;
                    String shortLabel = place.optString("short", "");
                    if (shortLabel.isEmpty()) return true;
                    if (!shortLabel.toLowerCase(Locale.US).equals(placeKey)) return true;
                }
                return false;
            });
        }

        return recordings;
    }
    
    private static void scanDirectory(File dir, String type, List<JSONObject> recordings, String dateFilter) {
        if (!dir.exists() || !dir.isDirectory()) return;

        // Verify directory is actually readable (catches unmounted SD card ghost paths)
        if (!dir.canRead()) return;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) return;

        long filterStart = 0, filterEnd = 0;
        if (dateFilter != null && !dateFilter.isEmpty()) {
            try {
                // Parse date filter (YYYY-MM-DD format)
                String[] parts = dateFilter.split("-");
                Calendar cal = Calendar.getInstance();
                cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]), 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                filterStart = cal.getTimeInMillis();
                cal.add(Calendar.DAY_OF_MONTH, 1);
                filterEnd = cal.getTimeInMillis();
            } catch (Exception e) {
                CameraDaemon.log("Invalid date filter: " + dateFilter);
            }
        }

        for (File file : files) {
            // Skip ghost files: must be readable and have actual content
            // On BYD, unmounted SD card can leave stale directory entries with 0-byte ghosts
            if (!file.canRead() || file.length() <= 0) continue;

            JSONObject recording = parseRecording(file, type);
            if (recording != null) {
                // Apply date filter if specified
                if (filterStart > 0) {
                    long ts = recording.optLong("timestamp", 0);
                    if (ts < filterStart || ts >= filterEnd) continue;
                }
                recordings.add(recording);
            }
        }
    }
    
    private static JSONObject parseRecording(File file, String type) {
        // Cache lookup: hot path skips regex + DateFormat + sidecar I/O when
        // nothing has changed. Key by absolute path; the type only affects
        // which regex matches but a given file matches at most one regex,
        // so cached entries are stable across `type` values.
        final String cacheKey = file.getAbsolutePath();
        final long mp4Length = file.length();
        final long mp4Mtime = file.lastModified();
        File sidecar = new File(file.getParentFile(),
                file.getName().replace(".mp4", ".json"));
        final long sidecarMtime = sidecar.exists() ? sidecar.lastModified() : 0L;

        CachedRecording cached = RECORDING_CACHE.get(cacheKey);
        if (cached != null
                && cached.mp4Length == mp4Length
                && cached.mp4Mtime == mp4Mtime
                && cached.sidecarMtime == sidecarMtime) {
            try {
                return new JSONObject(cached.json);
            } catch (Exception ignored) {
                // fall through to re-parse
            }
        }

        JSONObject parsed = parseRecordingUncached(file, type);
        if (parsed != null) {
            try {
                RECORDING_CACHE.put(cacheKey,
                        new CachedRecording(mp4Length, mp4Mtime, sidecarMtime, parsed.toString()));
                // Keep the inverted index aligned with every parse so a
                // sidecar that gets a place merged in async (via
                // SidecarGeoUpdater) lands in the place bucket on the
                // very next list request — sidecar mtime change → re-
                // parse → indexPut → place bucket updated.
                indexPut(parsed);

                // Cold-boot place backfill. If the sidecar has
                // geo.start but no geo.place, the resolver was killed
                // before it could merge — typical case is SIGKILL /
                // OOM mid-write or a SIGKILL that happened during the
                // ~800 ms async resolve window. Schedule a resolve
                // here so the place name appears on the NEXT request.
                // No-op when the per-flow geocoding toggle is off
                // (the resolver itself gates), so disabled installs
                // pay nothing. Cache hit means already-resolved or
                // already-scheduled, so the post-fetch merge fires
                // at most once per backfill instance.
                maybeBackfillPlace(file, parsed);
            } catch (Exception ignored) {}
        }
        return parsed;
    }

    /**
     * If the parsed sidecar has start coords but no resolved place
     * name, dispatch an async resolve. Used to recover from SIGKILL'd
     * recordings whose async resolver never completed before process
     * death — the sidecar is on disk with `geo.start` but no
     * `geo.place`. Resolver short-circuits when the per-flow toggle
     * is off, so this is a no-op for users who haven't opted in.
     */
    private static void maybeBackfillPlace(File mp4File, JSONObject parsed) {
        try {
            // Already has a place? Nothing to do.
            JSONObject place = parsed.optJSONObject("place");
            if (place != null && !place.optString("short", "").isEmpty()) return;
            Double startLat = parsed.has("startLat") ? parsed.optDouble("startLat", Double.NaN) : null;
            Double startLng = parsed.has("startLng") ? parsed.optDouble("startLng", Double.NaN) : null;
            if (startLat == null || startLng == null
                    || Double.isNaN(startLat) || Double.isNaN(startLng)) return;
            String filename = mp4File.getName();
            String flow = filename.startsWith("event_") ? "surveillance" : "recording";
            final File mp4 = mp4File;
            com.overdrive.app.geo.GeocodingResolver.getInstance()
                    .resolveAsync(startLat, startLng, flow, resolved -> {
                        if (resolved == null) return;
                        com.overdrive.app.geo.SidecarGeoUpdater
                                .mergePlaceForMp4(mp4, resolved);
                    });
        } catch (Throwable ignored) {
            // Backfill is best-effort; failure here must not corrupt
            // the parse result we're about to return.
        }
    }

    private static JSONObject parseRecordingUncached(File file, String type) {
        try {
            String name = file.getName();
            long timestamp;
            int cameraId = 0;
            
            if (type.equals("sentry")) {
                Matcher m = EVENT_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String dateStr = m.group(1) + "_" + m.group(2);
                timestamp = FMT_FILENAME.get().parse(dateStr).getTime();
            } else if (type.equals("proximity")) {
                Matcher m = PROXIMITY_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String dateStr = m.group(1) + "_" + m.group(2);
                timestamp = FMT_FILENAME.get().parse(dateStr).getTime();
            } else {
                Matcher m = CAM_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String camStr = m.group(1);
                cameraId = camStr != null ? Integer.parseInt(camStr) : 0;
                String dateStr = m.group(2) + "_" + m.group(3);
                timestamp = FMT_FILENAME.get().parse(dateStr).getTime();
            }
            
            JSONObject rec = new JSONObject();
            rec.put("filename", name);
            rec.put("path", file.getAbsolutePath());
            rec.put("type", type);
            rec.put("cameraId", cameraId);
            rec.put("timestamp", timestamp);
            rec.put("size", file.length());
            rec.put("sizeFormatted", formatSize(file.length()));
            
            // Format date/time for display
            Date date = new Date(timestamp);
            rec.put("date", FMT_DATE_ISO.get().format(date));
            rec.put("time", FMT_TIME_ISO.get().format(date));
            rec.put("dateFormatted", FMT_DATE_DISPLAY.get().format(date));
            rec.put("timeFormatted", FMT_TIME_DISPLAY.get().format(date));
            
            // Video URL for playback
            rec.put("videoUrl", "/video/" + name);
            
            // Thumbnail URL - server generates thumbnail from video
            rec.put("thumbnailUrl", "/thumb/" + name);

            // ---- v3 sidecar enrichment (item 6) ----
            // If a JSON sidecar accompanies this MP4, attach the high-level stats so
            // the events list can render badges + filter without opening every file.
            // Backwards-compatible: if no sidecar / v2 sidecar / parse error, the
            // recording entry simply lacks the new fields and the UI degrades.
            try {
                File sidecar = new File(file.getParentFile(), name.replace(".mp4", ".json"));
                if (sidecar.exists() && sidecar.canRead()) {
                    StringBuilder sb = new StringBuilder((int) Math.min(sidecar.length(), 65536));
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(sidecar))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject side = new JSONObject(sb.toString());
                    int sideVersion = side.optInt("version", 2);
                    rec.put("schemaVersion", sideVersion);
                    JSONObject stats = side.optJSONObject("stats");
                    if (stats != null) {
                        // v2 fields (always present)
                        rec.put("personSpans", stats.optInt("person", 0));
                        rec.put("vehicleSpans", stats.optInt("car", 0));
                        rec.put("bikeSpans", stats.optInt("bike", 0));
                        // v3 fields (may be absent on legacy clips)
                        if (stats.has("personCount"))  rec.put("personCount",  stats.optInt("personCount"));
                        if (stats.has("vehicleCount")) rec.put("vehicleCount", stats.optInt("vehicleCount"));
                        if (stats.has("bikeCount"))    rec.put("bikeCount",    stats.optInt("bikeCount"));
                        if (stats.has("animalCount"))  rec.put("animalCount",  stats.optInt("animalCount"));
                        if (stats.has("peakSeverity")) rec.put("peakSeverity", stats.optString("peakSeverity"));
                        if (stats.has("peakProximity")) rec.put("peakProximity", stats.optString("peakProximity"));
                        if (stats.has("peakSeverityMs")) rec.put("peakSeverityMs", stats.optLong("peakSeverityMs"));
                    }
                    // Hero thumbnail filename (v3 only)
                    if (side.has("heroThumbnail")) {
                        String heroName = side.optString("heroThumbnail");
                        if (heroName != null && !heroName.isEmpty()) {
                            File heroFile = new File(file.getParentFile(), heroName);
                            if (heroFile.exists()) {
                                rec.put("heroThumbnailUrl", "/thumb/" + heroName);
                            }
                        }
                    }
                    // v3 geo block — populated by EventTimelineCollector at sidecar
                    // write time and asynchronously by SidecarGeoUpdater when the
                    // place resolver completes. All fields are optional; legacy
                    // clips and clips with no GPS fix simply omit them.
                    JSONObject geo = side.optJSONObject("geo");
                    if (geo != null) {
                        JSONObject startObj = geo.optJSONObject("start");
                        if (startObj != null) {
                            if (startObj.has("lat")) rec.put("startLat", startObj.optDouble("lat"));
                            if (startObj.has("lng")) rec.put("startLng", startObj.optDouble("lng"));
                        }
                        JSONObject placeObj = geo.optJSONObject("place");
                        if (placeObj != null) {
                            String dn = placeObj.optString("displayName", "");
                            String dist = placeObj.optString("district", "");
                            String city = placeObj.optString("city", "");
                            String country = placeObj.optString("country", "");
                            String cc = placeObj.optString("countryCode", "");
                            String src = placeObj.optString("source", "");
                            String shortLabel = !dist.isEmpty() ? dist
                                    : !city.isEmpty() ? city
                                    : dn;
                            String mediumLabel = (!dist.isEmpty() && !city.isEmpty() && !dist.equals(city))
                                    ? (dist + ", " + city)
                                    : shortLabel;
                            JSONObject placeOut = new JSONObject();
                            if (!dn.isEmpty()) placeOut.put("displayName", dn);
                            if (!dist.isEmpty()) placeOut.put("district", dist);
                            if (!city.isEmpty()) placeOut.put("city", city);
                            if (!country.isEmpty()) placeOut.put("country", country);
                            if (!cc.isEmpty()) placeOut.put("countryCode", cc);
                            if (!src.isEmpty()) placeOut.put("source", src);
                            if (shortLabel != null && !shortLabel.isEmpty()) {
                                placeOut.put("short", shortLabel);
                            }
                            if (mediumLabel != null && !mediumLabel.isEmpty()) {
                                placeOut.put("medium", mediumLabel);
                            }
                            rec.put("place", placeOut);
                        }
                    }
                    // Compact actors[] for filter chips. Strip the heavy fields,
                    // but KEEP static actors with their isStatic flag intact.
                    //
                    // The Class chip ("does this clip contain a vehicle?") only
                    // makes sense if it counts every vehicle that physically
                    // appeared. The tracker's isStatic flag is a frame-by-frame
                    // bbox-stability heuristic (STATIC_FRAMES_NEEDED_VEHICLE=2,
                    // ~200ms) that flips true on a vehicle passing laterally
                    // through a quadrant — which is exactly the kind of clip a
                    // user filtering on Vehicle wants to see.
                    //
                    // Severity / Proximity filters key off rec.peakSeverity and
                    // rec.peakProximity (which EventTimelineCollector aggregates
                    // from non-static actors only) so the "scenery doesn't
                    // escalate" rule still holds for those chips.
                    org.json.JSONArray actors = side.optJSONArray("actors");
                    if (actors != null && actors.length() > 0) {
                        org.json.JSONArray slim = new org.json.JSONArray();
                        for (int i = 0; i < actors.length(); i++) {
                            JSONObject a = actors.optJSONObject(i);
                            if (a == null) continue;
                            JSONObject s = new JSONObject();
                            s.put("class", a.optString("class", "object"));
                            s.put("peakSeverity", a.optString("peakSeverity", "NOTICE"));
                            s.put("peakProximity", a.optString("peakProximity", "UNKNOWN"));
                            s.put("isStatic", a.optBoolean("isStatic", false));
                            slim.put(s);
                        }
                        rec.put("actors", slim);
                    }
                }
            } catch (Exception se) {
                // Sidecar parse failure is non-fatal; recording still appears in list.
            }

            return rec;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get dates that have recordings (for calendar highlighting).
     */
    private static void getDatesWithRecordings(OutputStream out) throws Exception {
        Set<String> dates = new HashSet<>();
        Map<String, Integer> countByDate = new HashMap<>();
        Map<String, Boolean> hasSentryByDate = new HashMap<>();
        StorageManager sm = StorageManager.getInstance();
        
        // Scan normal recordings from ALL locations (active + alternate + legacy)
        for (File dir : sm.getAllRecordingsDirs()) {
            scanDatesInDirectory(dir, false, dates, countByDate, hasSentryByDate);
        }
        File legacyDir = new File(LEGACY_RECORDINGS_DIR);
        if (legacyDir.exists()) {
            scanDatesInDirectory(legacyDir, false, dates, countByDate, hasSentryByDate);
        }
        
        // Scan sentry events from ALL locations (active + alternate + legacy)
        for (File dir : sm.getAllSurveillanceDirs()) {
            scanDatesInDirectory(dir, true, dates, countByDate, hasSentryByDate);
        }
        File legacySentryDir = new File(LEGACY_SENTRY_DIR);
        if (legacySentryDir.exists()) {
            scanDatesInDirectory(legacySentryDir, true, dates, countByDate, hasSentryByDate);
        }
        
        // Scan proximity events from ALL locations (active + alternate)
        for (File dir : sm.getAllProximityDirs()) {
            scanDatesInDirectory(dir, false, dates, countByDate, hasSentryByDate);
        }
        
        JSONArray datesArray = new JSONArray();
        for (String date : dates) {
            JSONObject dateObj = new JSONObject();
            dateObj.put("date", date);
            dateObj.put("count", countByDate.getOrDefault(date, 0));
            dateObj.put("hasSentry", hasSentryByDate.getOrDefault(date, false));
            datesArray.put(dateObj);
        }
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("dates", datesArray);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void scanDatesInDirectory(File dir, boolean isSentry, Set<String> dates, 
            Map<String, Integer> countByDate, Map<String, Boolean> hasSentryByDate) {
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) return;
        
        for (File file : files) {
            // Skip ghost files from unmounted SD card
            if (!file.canRead() || file.length() <= 0) continue;
            
            String name = file.getName();
            String dateStr = null;
            boolean isSentryFile = false;
            
            // Try all patterns to extract date — handles mixed directories
            Matcher eventMatcher = EVENT_PATTERN.matcher(name);
            Matcher camMatcher = CAM_PATTERN.matcher(name);
            Matcher proxMatcher = PROXIMITY_PATTERN.matcher(name);
            
            if (eventMatcher.matches()) {
                dateStr = eventMatcher.group(1);
                isSentryFile = true;
            } else if (camMatcher.matches()) {
                dateStr = camMatcher.group(2);
            } else if (proxMatcher.matches()) {
                dateStr = proxMatcher.group(1);
            }
            
            if (dateStr != null && dateStr.length() == 8) {
                String formattedDate = dateStr.substring(0, 4) + "-" + 
                                       dateStr.substring(4, 6) + "-" + 
                                       dateStr.substring(6, 8);
                dates.add(formattedDate);
                countByDate.merge(formattedDate, 1, Integer::sum);
                if (isSentryFile) {
                    hasSentryByDate.put(formattedDate, true);
                }
            }
        }
    }
    
    /**
     * Get storage statistics.
     * Scans ALL locations (active + alternate) via StorageManager, deduplicating by filename.
     */
    private static void getStorageStats(OutputStream out) throws Exception {
        StorageManager storage = StorageManager.getInstance();
        
        long normalSize = 0, normalCount = 0;
        long sentrySize = 0, sentryCount = 0;
        long proximitySize = 0, proximityCount = 0;
        
        long normalTodayCount = 0;
        long sentryTodayCount = 0;
        long proximityTodayCount = 0;
        
        String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        
        // Track seen filenames to avoid double-counting files that exist in both locations
        Set<String> seenNormal = new HashSet<>();
        Set<String> seenSentry = new HashSet<>();
        Set<String> seenProximity = new HashSet<>();
        
        // Normal recordings from ALL locations
        for (File dir : storage.getAllRecordingsDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenNormal.contains(f.getName())) continue;
                seenNormal.add(f.getName());
                normalSize += f.length();
                normalCount++;
                if (isFileFromToday(f.getName(), todayStr, CAM_PATTERN, 2)) {
                    normalTodayCount++;
                }
            }
        }
        // Legacy location
        File legacyDir = new File(LEGACY_RECORDINGS_DIR);
        if (legacyDir.exists() && legacyDir.canRead()) {
            File[] files = legacyDir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files != null) {
                for (File f : files) {
                    if (!f.canRead() || f.length() <= 0) continue;
                    if (seenNormal.contains(f.getName())) continue;
                    seenNormal.add(f.getName());
                    normalSize += f.length();
                    normalCount++;
                    if (isFileFromToday(f.getName(), todayStr, CAM_PATTERN, 2)) {
                        normalTodayCount++;
                    }
                }
            }
        }
        
        // Sentry events from ALL locations
        for (File dir : storage.getAllSurveillanceDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenSentry.contains(f.getName())) continue;
                seenSentry.add(f.getName());
                sentrySize += f.length();
                sentryCount++;
                if (isFileFromToday(f.getName(), todayStr, EVENT_PATTERN, 1)) {
                    sentryTodayCount++;
                }
            }
        }
        // Legacy sentry location
        File legacySentryDir = new File(LEGACY_SENTRY_DIR);
        if (legacySentryDir.exists() && legacySentryDir.canRead()) {
            File[] files = legacySentryDir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files != null) {
                for (File f : files) {
                    if (!f.canRead() || f.length() <= 0) continue;
                    if (seenSentry.contains(f.getName())) continue;
                    seenSentry.add(f.getName());
                    sentrySize += f.length();
                    sentryCount++;
                    if (isFileFromToday(f.getName(), todayStr, EVENT_PATTERN, 1)) {
                        sentryTodayCount++;
                    }
                }
            }
        }
        
        // Proximity events from ALL locations
        for (File dir : storage.getAllProximityDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenProximity.contains(f.getName())) continue;
                seenProximity.add(f.getName());
                proximitySize += f.length();
                proximityCount++;
                if (isFileFromToday(f.getName(), todayStr, PROXIMITY_PATTERN, 1)) {
                    proximityTodayCount++;
                }
            }
        }
        
        // Get available space from the active recordings directory
        File activeRecDir = storage.getRecordingsDir();
        long availableSpace = activeRecDir.exists() ? activeRecDir.getFreeSpace() : 0;
        long totalSpace = activeRecDir.exists() ? activeRecDir.getTotalSpace() : 0;
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("normalCount", normalCount);
        response.put("normalSize", normalSize);
        response.put("normalSizeFormatted", formatSize(normalSize));
        response.put("sentryCount", sentryCount);
        response.put("sentrySize", sentrySize);
        response.put("sentrySizeFormatted", formatSize(sentrySize));
        response.put("proximityCount", proximityCount);
        response.put("proximitySize", proximitySize);
        response.put("proximitySizeFormatted", formatSize(proximitySize));
        response.put("totalCount", normalCount + sentryCount + proximityCount);
        response.put("totalSize", normalSize + sentrySize + proximitySize);
        response.put("totalSizeFormatted", formatSize(normalSize + sentrySize + proximitySize));
        response.put("availableSpace", availableSpace);
        response.put("availableSpaceFormatted", formatSize(availableSpace));
        response.put("totalSpace", totalSpace);
        response.put("totalSpaceFormatted", formatSize(totalSpace));
        
        // Today's counts
        response.put("normalTodayCount", normalTodayCount);
        response.put("sentryTodayCount", sentryTodayCount);
        response.put("proximityTodayCount", proximityTodayCount);
        response.put("totalTodayCount", normalTodayCount + sentryTodayCount + proximityTodayCount);
        
        // SOTA: Add storage limit info
        response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
        response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
        response.put("recordingsLimitBytes", storage.getRecordingsLimitMb() * 1024 * 1024);
        response.put("surveillanceLimitBytes", storage.getSurveillanceLimitMb() * 1024 * 1024);
        response.put("recordingsUsagePercent", storage.getRecordingsLimitMb() > 0 ? 
            Math.round(normalSize * 100.0 / (storage.getRecordingsLimitMb() * 1024 * 1024)) : 0);
        response.put("surveillanceUsagePercent", storage.getSurveillanceLimitMb() > 0 ? 
            Math.round(sentrySize * 100.0 / (storage.getSurveillanceLimitMb() * 1024 * 1024)) : 0);
        
        // Storage paths
        response.put("recordingsPath", getRecordingsDir());
        response.put("surveillancePath", getSentryDir());
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Check if a filename matches today's date based on the pattern.
     * @param filename The filename to check
     * @param todayStr Today's date in YYYYMMDD format
     * @param pattern The regex pattern to match
     * @param dateGroup The group index containing the date in the pattern
     * @return true if the file is from today
     */
    private static boolean isFileFromToday(String filename, String todayStr, Pattern pattern, int dateGroup) {
        Matcher m = pattern.matcher(filename);
        if (m.matches()) {
            String dateStr = m.group(dateGroup);
            return todayStr.equals(dateStr);
        }
        return false;
    }
    
    /**
     * Stream video file with optional Range support and ETag-based caching.
     *
     * Finalized event recordings are immutable (the daemon writes to
     * <name>.mp4.tmp and atomically renames once the file is closed), so we
     * emit a strong ETag derived from length+mtime and a 24h max-age so the
     * WebView's HTTP cache can serve repeat playback locally instead of
     * re-streaming from the daemon. Cache headers are added in
     * HttpResponse.sendVideo / sendVideoRange.
     */
    private static void streamVideo(OutputStream out, String filename, String rangeHeader,
                                    String ifNoneMatchHeader) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }

        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);

        if (file == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_not_found_with_filename", filename));
            return;
        }

        // Conditional GET: if the client's cached copy matches our ETag,
        // skip re-streaming. Tag is "<length>-<mtime>" so any append/replace
        // invalidates without us needing a content hash.
        String etag = buildVideoEtag(file);
        if (ifNoneMatchHeader != null && etagMatches(ifNoneMatchHeader, etag)) {
            HttpResponse.sendNotModified(out, etag);
            return;
        }

        // Handle Range request for video seeking
        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String rangeSpec = rangeHeader.substring(6);
                String[] parts = rangeSpec.split("-");
                long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : -1;

                // Validate range
                long fileLength = file.length();
                if (start < 0 || start >= fileLength) {
                    HttpResponse.sendError(out, 416, Messages.get("errors.recordings_range_not_satisfiable"));
                    return;
                }

                HttpResponse.sendVideoRange(out, file, start, end, etag);
            } else {
                HttpResponse.sendVideo(out, file, etag);
            }
        } catch (NumberFormatException e) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_range_header"));
        } catch (java.io.FileNotFoundException e) {
            // File disappeared between check and read (SD card unmount)
            HttpResponse.sendError(out, 410, Messages.get("errors.recordings_file_no_longer_accessible"));
        }
    }

    /**
     * Build a strong ETag for a video file from its size and mtime. Anything
     * that mutates the file (replacement, append, ext-storage rotation)
     * changes at least one of these, invalidating the client's cache.
     */
    private static String buildVideoEtag(File file) {
        return "\"" + file.length() + "-" + file.lastModified() + "\"";
    }

    /**
     * Check whether the client's If-None-Match header matches our ETag.
     * Tolerates the wildcard form, weak prefix ("W/"), and comma-separated
     * lists per RFC 7232 §3.2.
     */
    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) return false;
        if ("*".equals(ifNoneMatch.trim())) return true;
        for (String token : ifNoneMatch.split(",")) {
            String t = token.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag)) return true;
        }
        return false;
    }

    /**
     * Delete a recording.
     */
    private static void deleteRecording(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        
        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);
        
        if (file == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_not_found"));
            return;
        }
        
        boolean deleted = file.delete();
        if (deleted) {
            deleteSidecars(file, filename);
        }

        JSONObject response = new JSONObject();
        response.put("success", deleted);
        if (!deleted) {
            response.put("error", Messages.get("errors.recordings_delete_failed"));
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Sweep the .mp4's sidecar files: JSON event timeline, cached thumb,
     * v3 hero JPEG, per-actor thumbs ({@code thumb_<base>_a*.jpg}).
     *
     * Mirrors RecordingScanner.deleteRecording on the Android side — without
     * this sweep, web-UI deletes leak hero/per-actor JPEGs into the storage
     * directory until disk fills (the loop-rotation cleanup also doesn't see
     * them because it only iterates .mp4 files).
     */
    private static void deleteSidecars(File mp4File, String filename) {
        // Invalidate the in-memory parse cache so the next /api/recordings
        // call doesn't return a phantom entry for the just-deleted file.
        // Tearing down the inverted-index entry happens here too so a
        // place chip pointing at this file's place key vanishes the
        // moment the delete completes.
        RECORDING_CACHE.remove(mp4File.getAbsolutePath());
        indexRemove(filename);

        // JSON event timeline
        String jsonName = filename.replace(".mp4", ".json");
        File jsonFile = new File(mp4File.getParentFile(), jsonName);
        if (jsonFile.exists()) jsonFile.delete();

        // Cached thumbnail
        String thumbName = filename.replace(".mp4", ".jpg");
        File thumbFile = new File(getThumbnailCacheDir(), thumbName);
        if (thumbFile.exists()) thumbFile.delete();

        // v3 hero JPEG sibling: <base>.jpg next to the mp4
        File parent = mp4File.getParentFile();
        if (parent == null || !parent.canRead()) return;
        String base = filename.endsWith(".mp4")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        File heroSibling = new File(parent, base + ".jpg");
        if (heroSibling.exists()) heroSibling.delete();

        // Per-actor thumbs: thumb_<base>_a<id>(_<rel>).jpg
        // Anchor with "_a" so a sibling segment named "<base>_2.mp4" with
        // its own thumbs at "thumb_<base>_2_a*.jpg" is NOT swept when we
        // delete <base>.mp4 — the underscore-after-_2_ is followed by 'a'
        // for actor thumbs, but "_2_" itself is followed by an actor digit
        // that the original prefix-only check would catch incorrectly.
        final String perActorPrefix = "thumb_" + base + "_a";
        File[] perActor = parent.listFiles((dir, name) ->
                name.startsWith(perActorPrefix) && name.endsWith(".jpg"));
        if (perActor != null) {
            for (File f : perActor) f.delete();
        }
    }
    
    /**
     * Batch delete multiple recordings at once.
     * Accepts JSON body: { "filenames": ["file1.mp4", "file2.mp4", ...] }
     * Returns: { "success": true, "deleted": N, "failed": N, "errors": [...] }
     */
    private static void batchDeleteRecordings(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        
        if (body == null || body.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.recordings_body_required"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            JSONObject request = new JSONObject(body);
            JSONArray filenames = request.optJSONArray("filenames");
            
            if (filenames == null || filenames.length() == 0) {
                response.put("success", false);
                response.put("error", Messages.get("errors.recordings_no_filenames"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            
            // Limit batch size to prevent abuse
            int maxBatch = 100;
            if (filenames.length() > maxBatch) {
                response.put("success", false);
                response.put("error", Messages.get("errors.recordings_max_batch_with_count", maxBatch));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            
            int deleted = 0;
            int failed = 0;
            JSONArray errors = new JSONArray();
            
            for (int i = 0; i < filenames.length(); i++) {
                String filename = filenames.getString(i);
                
                // Security: prevent path traversal
                if (filename.contains("..") || filename.contains("/")) {
                    failed++;
                    errors.put(filename + ": invalid filename");
                    continue;
                }
                
                File file = findVideoFile(filename);
                if (file == null) {
                    failed++;
                    errors.put(filename + ": not found");
                    continue;
                }
                
                boolean success = file.delete();
                if (success) {
                    deleted++;
                    deleteSidecars(file, filename);
                } else {
                    failed++;
                    errors.put(filename + ": delete failed");
                }
            }
            
            response.put("success", true);
            response.put("deleted", deleted);
            response.put("failed", failed);
            if (errors.length() > 0) {
                response.put("errors", errors);
            }
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * SOTA: Serve event timeline JSON for a recording.
     * Returns the JSON sidecar if it exists, or an empty events array for backward compatibility.
     */
    private static void serveEventTimeline(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        
        // Convert .mp4 filename to .json
        String jsonFilename = filename.replace(".mp4", ".json");
        
        // Search for the JSON sidecar in all storage locations
        File jsonFile = findJsonSidecar(jsonFilename);
        
        if (jsonFile != null && jsonFile.exists()) {
            // Serve the actual event data
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(jsonFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                HttpResponse.sendJson(out, sb.toString());
            } catch (Exception e) {
                // File exists but can't be read — return empty
                sendEmptyTimeline(out);
            }
        } else {
            // Backward compatible: no sidecar = empty events array
            sendEmptyTimeline(out);
        }
    }
    
    /**
     * Send an empty timeline response (backward compatibility for videos without sidecars).
     */
    private static void sendEmptyTimeline(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("version", 1);
        response.put("events", new JSONArray());
        response.put("durationMs", 0);
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Find a JSON sidecar file across all storage locations.
     * Uses StorageManager to get all possible directories without hardcoding paths.
     */
    private static File findJsonSidecar(String jsonFilename) {
        StorageManager sm = StorageManager.getInstance();
        
        // Check all surveillance directories
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        // Check all recordings directories
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        // Check all proximity directories
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        return null;
    }
    
    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format(Locale.US, "%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
}
