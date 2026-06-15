package com.overdrive.app.geo;

import org.json.JSONObject;

/**
 * Immutable point-in-time GPS snapshot, threaded through the recording
 * lifecycle into the v3 sidecar's {@code geo.start} / {@code geo.peak} /
 * {@code geo.end} blocks.
 *
 * <p>Fields are passed-through values from {@link com.overdrive.app.monitor.GpsMonitor}
 * at capture time:
 * <ul>
 *   <li>{@link #lat}, {@link #lng} — coordinate. {@link Double#NaN} when
 *       there was no fix; the JSON writer omits the block in that case
 *       rather than emit (0,0) which would point at the Atlantic Ocean.</li>
 *   <li>{@link #accuracy} — GPS accuracy radius in meters. 0 when unknown.</li>
 *   <li>{@link #ageMs} — wall-clock age of the underlying GPS fix at
 *       capture time. -1 when unknown. A 30+ s age is the visual cue that
 *       the location may not be reliable for narrow questions like "was
 *       the car at this exact street."</li>
 *   <li>{@link #capturedAtMs} — wall-clock at capture time.</li>
 *   <li>{@link #relMs} — milliseconds since the recording began. Only
 *       meaningful for {@code peak} captures (peak severity moment); 0 for
 *       {@code start}, equal to {@code durationMs} for {@code end}.</li>
 * </ul>
 */
public final class GeoSnapshot {

    public final double lat;
    public final double lng;
    public final float  accuracy;
    public final long   ageMs;
    public final long   capturedAtMs;
    public final long   relMs;

    public GeoSnapshot(double lat, double lng, float accuracy,
                       long ageMs, long capturedAtMs, long relMs) {
        this.lat = lat;
        this.lng = lng;
        this.accuracy = accuracy;
        this.ageMs = ageMs;
        this.capturedAtMs = capturedAtMs;
        this.relMs = relMs;
    }

    /** True iff lat/lng are real coordinates (not NaN). */
    public boolean hasFix() {
        return !Double.isNaN(lat) && !Double.isNaN(lng);
    }

    /** Sentinel "no fix" snapshot. */
    public static GeoSnapshot empty() {
        return new GeoSnapshot(Double.NaN, Double.NaN, 0f, -1L, 0L, -1L);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        if (!hasFix()) return o;
        try {
            o.put("lat", lat);
            o.put("lng", lng);
            if (accuracy > 0f) o.put("accuracy", (double) accuracy);
            if (ageMs >= 0L) o.put("ageMs", ageMs);
            if (capturedAtMs > 0L) o.put("capturedAtMs", capturedAtMs);
            if (relMs >= 0L) o.put("tMs", relMs);
        } catch (Throwable ignored) {}
        return o;
    }

    /** Max age (ms) for a captured fix to be tag-worthy — mirrors
     *  HardwareEventRecorderGpu.GEO_FIX_MAX_AGE_MS and the downstream fallback window. */
    private static final long MAX_AGE_MS = 5L * 60L * 1000L;

    /**
     * Capture from the running GpsMonitor at this instant. {@code relMs} caller-supplied.
     *
     * <p>FRESHNESS GATE: returns {@link #empty()} (no fix) when the live fix is
     * cache-loaded (from a previous drive/boot) or older than {@link #MAX_AGE_MS}.
     * {@code GpsMonitor.hasLocation()} alone is true for ANY non-(0,0) value including a
     * stale cached one, so without this the peak/end snapshots (and the cold-start
     * fallback) could tag a parked clip with yesterday's location. Matching the
     * recorder's start-snapshot gate keeps every geo block in a sidecar consistently
     * fresh-or-absent.
     */
    public static GeoSnapshot capture(long relMs) {
        try {
            com.overdrive.app.monitor.GpsMonitor gps =
                    com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (!gps.hasLocation()) return empty();
            long lastUpdate = gps.getLastUpdate();
            long nowMs = System.currentTimeMillis();
            long age = lastUpdate > 0 ? Math.max(0L, nowMs - lastUpdate) : -1L;
            // Reject cache-loaded or over-age fixes → no tag rather than a stale one.
            if (gps.isLoadedFromCache() || age < 0L || age > MAX_AGE_MS) {
                return empty();
            }
            return new GeoSnapshot(gps.getLatitude(), gps.getLongitude(),
                    gps.getAccuracy(), age, nowMs, relMs);
        } catch (Throwable t) {
            return empty();
        }
    }
}
