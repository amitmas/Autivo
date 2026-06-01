package com.overdrive.app.geo;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Atomically merge a resolved {@link PlaceResult} into an existing v3
 * JSON sidecar. Used by the async resolver-completion path so a place
 * name resolved 800 ms after the .mp4 finalized can be added to the
 * sidecar without the writer having to know it was coming.
 *
 * <p>Atomic write: read → mutate → write to {@code .tmp} sibling → rename.
 * Mirrors the discipline used by {@code EventTimelineCollector.writeJsonSidecar}
 * and {@code UnifiedConfigManager.saveConfigInternal}.
 *
 * <p>Failure semantics: every failure mode is logged at warn level and
 * silently swallowed. A failed sidecar update never breaks recording or
 * playback — the sidecar simply lacks the {@code geo.place} field, exactly
 * the state it was in before this updater ran.
 */
public final class SidecarGeoUpdater {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SidecarGeo");

    /** Cap on sidecar size we'll ingest. Mirrors RecordingScanner's 64 KB cap. */
    private static final int MAX_BYTES = 256 * 1024;

    private SidecarGeoUpdater() {}

    /**
     * Merge {@code place} into {@code sidecarFile}'s {@code geo.place} field.
     * No-op when the file doesn't exist or doesn't parse as JSON.
     */
    public static void mergePlace(File sidecarFile, PlaceResult place) {
        if (sidecarFile == null || place == null) return;
        if (!sidecarFile.exists() || !sidecarFile.canRead()) return;
        try {
            JSONObject root = readSidecar(sidecarFile);
            if (root == null) return;

            JSONObject geo = root.optJSONObject("geo");
            if (geo == null) {
                geo = new JSONObject();
                root.put("geo", geo);
            }
            geo.put("place", place.toJson());

            writeSidecar(sidecarFile, root);
        } catch (Throwable t) {
            logger.warn("mergePlace failed for "
                    + sidecarFile.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Convenience: locate the sidecar sibling of {@code mp4File} and merge.
     */
    public static void mergePlaceForMp4(File mp4File, PlaceResult place) {
        if (mp4File == null) return;
        File parent = mp4File.getParentFile();
        if (parent == null) return;
        String name = mp4File.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File sidecar = new File(parent, base + ".json");
        mergePlace(sidecar, place);
    }

    private static JSONObject readSidecar(File f) {
        try (FileReader r = new FileReader(f)) {
            long len = Math.min(f.length(), MAX_BYTES);
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder((int) len);
            int total = 0;
            int n;
            while (total < len && (n = r.read(buf, 0, (int) Math.min(buf.length, len - total))) > 0) {
                sb.append(buf, 0, n);
                total += n;
            }
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            logger.warn("readSidecar failed for " + f.getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static void writeSidecar(File f, JSONObject root) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".geo.tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(root.toString());
        }
        try { tmp.setReadable(true, false); } catch (Throwable ignored) {}
        if (!tmp.renameTo(f)) {
            // Fall back to a direct rewrite if rename is denied (shouldn't
            // happen for a same-directory rename, but guard anyway so we
            // don't leave the .geo.tmp orphan).
            try (FileWriter w = new FileWriter(f)) {
                w.write(root.toString());
            }
            try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }
}
