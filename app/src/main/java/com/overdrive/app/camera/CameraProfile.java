package com.overdrive.app.camera;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Vehicle/profile-specific panoramic defaults.
 *
 * Encoder dimensions are derived from the panoramic strip aspect: the mosaic
 * is a 2x2 grid of full-height tiles, so a 5120x960 strip yields 2x1280 wide
 * and 2x960 tall = 2560x1920 (4:3 quadrants). A 5120x720 strip yields
 * 2560x1440 (16:9 quadrants). Hardcoding 2560x1920 stretches Tang content.
 */
public final class CameraProfile {
    private final String id;
    private final String displayName;
    private final int panoCameraId;
    private final int panoWidth;
    private final int panoHeight;
    private final int panoSurfaceMode;
    private final int directPreviewWidth;
    private final int directPreviewHeight;
    private final EnumMap<CameraRole, CameraSourceRef> defaultRoleMappings;

    public CameraProfile(
            String id,
            String displayName,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            int directPreviewWidth,
            int directPreviewHeight,
            Map<CameraRole, CameraSourceRef> defaultRoleMappings) {
        this.id = id;
        this.displayName = displayName;
        this.panoCameraId = panoCameraId;
        this.panoWidth = panoWidth;
        this.panoHeight = panoHeight;
        this.panoSurfaceMode = panoSurfaceMode;
        this.directPreviewWidth = directPreviewWidth;
        this.directPreviewHeight = directPreviewHeight;
        this.defaultRoleMappings = new EnumMap<>(CameraRole.class);
        if (defaultRoleMappings != null) {
            this.defaultRoleMappings.putAll(defaultRoleMappings);
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPanoCameraId() {
        return panoCameraId;
    }

    public int getPanoWidth() {
        return panoWidth;
    }

    public int getPanoHeight() {
        return panoHeight;
    }

    public int getPanoSurfaceMode() {
        return panoSurfaceMode;
    }

    public int getDirectPreviewWidth() {
        return directPreviewWidth;
    }

    public int getDirectPreviewHeight() {
        return directPreviewHeight;
    }

    /**
     * Encoder/mosaic output width. The mosaic is a 2x2 grid of camera tiles,
     * each tile = (panoWidth/4) wide. Two tiles side-by-side = panoWidth/2.
     * For 5120 strip → 2560.
     */
    public int getEncoderWidth() {
        return Math.max(1, panoWidth / 2);
    }

    /**
     * Encoder/mosaic output height. Each tile is panoHeight tall, two tiles
     * stacked = panoHeight*2. For 960 strip → 1920 (Seal). For 720 strip → 1440 (Tang).
     */
    public int getEncoderHeight() {
        return Math.max(1, panoHeight * 2);
    }

    public EnumMap<CameraRole, CameraSourceRef> getDefaultRoleMappings() {
        return new EnumMap<>(defaultRoleMappings);
    }

    public JSONArray getVirtualViewsJson() {
        JSONArray out = new JSONArray();
        for (CameraVirtualView view : CameraVirtualView.values()) {
            out.put(view.toJson());
        }
        return out;
    }

    public JSONArray getPanoramicSlicesJson() {
        JSONArray out = new JSONArray();
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            out.put(slice.toJson());
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "id", id);
        putSafely(out, "label", displayName);
        putSafely(out, "panoCameraId", panoCameraId);
        putSafely(out, "panoWidth", panoWidth);
        putSafely(out, "panoHeight", panoHeight);
        putSafely(out, "panoSurfaceMode", panoSurfaceMode);
        putSafely(out, "directPreviewWidth", directPreviewWidth);
        putSafely(out, "directPreviewHeight", directPreviewHeight);
        putSafely(out, "encoderWidth", getEncoderWidth());
        putSafely(out, "encoderHeight", getEncoderHeight());

        JSONObject mappings = new JSONObject();
        for (Map.Entry<CameraRole, CameraSourceRef> entry : defaultRoleMappings.entrySet()) {
            putSafely(mappings, entry.getKey().getKey(), entry.getValue().toJson());
        }
        putSafely(out, "defaultRoleMappings", mappings);
        putSafely(out, "panoramicSlices", getPanoramicSlicesJson());
        putSafely(out, "virtualViews", getVirtualViewsJson());
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}
