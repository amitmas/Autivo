package com.overdrive.app.camera;

/**
 * Hardcoded DiLink 4 (byd_apa) producer-corner remap + per-role flip
 * constants. Single source of truth for both the recorder
 * (GpuMosaicRecorder) and the stream scaler (GpuStreamScaler) so they
 * can never silently disagree on the mosaic arrangement.
 *
 * <p>Variant A: every DiLink 4 trim observed so far emits the same
 * physical-camera-to-producer-corner mapping:
 * <pre>
 *   Front  → producer TL  with X+Y flip
 *   Right  → producer BR  with Y flip
 *   Rear   → producer TR  no flip
 *   Left   → producer BL  no flip
 * </pre>
 *
 * <p>If a future Variant B SKU lands, switch on a profile field inside
 * this helper rather than duplicating new constants at every call site.
 */
public final class Dilink4Constants {
    private Dilink4Constants() {}

    public static final float[] CORNER_FRONT = { 0.0f, 0.0f };
    public static final float[] CORNER_RIGHT = { 0.5f, 0.5f };
    public static final float[] CORNER_REAR  = { 0.5f, 0.0f };
    public static final float[] CORNER_LEFT  = { 0.0f, 0.5f };

    public static final float[] FLIP_FRONT = { 1.0f, 1.0f };  // X + Y flip
    public static final float[] FLIP_RIGHT = { 0.0f, 1.0f };  // Y flip
    public static final float[] FLIP_REAR  = { 0.0f, 0.0f };  // no flip
    public static final float[] FLIP_LEFT  = { 0.0f, 0.0f };  // no flip
}
