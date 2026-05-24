package com.overdrive.app.surveillance;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * FoveatedCropper — High-resolution targeted AI crop from the raw camera strip.
 *
 * Implements the "foveated vision" pattern: the low-res 640×480 mosaic acts as
 * peripheral vision to detect WHERE motion is happening, then this cropper
 * extracts a 640×640 window directly from the raw 5120×960 camera strip at
 * full resolution, centered on the motion centroid.
 *
 * SOTA async readback (matches GpuDownscaler.readPixelsDirect):
 *   Two FBOs are kept alive permanently. On call N we render to renderFbo and
 *   read back from readFbo, which finished its draw on call N-1. Because the
 *   GPU has already drained that pipeline by the time we sample it, glReadPixels
 *   returns immediately — no glFinish, no pipeline flush, no stutter on the
 *   encoder GL thread.
 *
 *   Trade-off: the AI lane sees a crop that is one service-tick old (~150 ms).
 *   The motion pipeline runs at 10 Hz internally; one tick of staleness is
 *   invisible to YOLO. Result struct carries the quadrant the readback bytes
 *   actually correspond to so the caller publishes to the correct slot.
 */
public class FoveatedCropper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("FoveatedCrop");

    public static final int CROP_SIZE = 640;

    // Source strip dimensions (runtime-resolved per camera profile).
    // Seal = 5120x960, Tang = 5120x720. Per-camera tile is stripWidth/4.
    private final int stripWidth;
    private final int stripHeight;
    private final float[] quadrantStripOffsetX;

    private int program = -1;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uCameraTex = -1;
    private int uCropRect = -1;

    private final int[] fboIds = new int[2];
    private final int[] fboTextures = new int[2];
    private int currentFbo = 0;          // index of FBO we render to this call
    private boolean hasPreviousFrame = false;

    // Quadrant + centroid metadata for the FBO that's currently in flight.
    // Slot 0/1 mirrors the FBO indices. When we read back FBO[i], the caller
    // is told these bytes belong to pendingQuadrant[i].
    private final int[] pendingQuadrant = new int[]{-1, -1};

    private ByteBuffer readBuffer = null;
    private byte[] scratchRgba = null;   // bulk-copy RGBA scratch (single JNI hop)
    private byte[] rgbBuffer = null;
    private boolean initialized = false;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform vec4 uCropRect;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos = vec2(\n" +
        "        uCropRect.x + vTexCoord.x * uCropRect.z,\n" +
        "        uCropRect.y + vTexCoord.y * uCropRect.w\n" +
        "    );\n" +
        "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
        "}\n";

    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f,  // Q0 (TL → Front)
        0.50f,  // Q1 (TR → Right)
        0.00f,  // Q2 (BL → Rear)
        0.25f   // Q3 (BR → Left)
    };

    public FoveatedCropper() {
        this(5120, 960, DEFAULT_QUADRANT_STRIP_OFFSET_X);
    }

    public FoveatedCropper(int stripWidth, int stripHeight) {
        this(stripWidth, stripHeight, DEFAULT_QUADRANT_STRIP_OFFSET_X);
    }

    public FoveatedCropper(int stripWidth, int stripHeight, float[] quadrantStripOffsetX) {
        this.stripWidth = Math.max(1, stripWidth);
        this.stripHeight = Math.max(1, stripHeight);
        this.quadrantStripOffsetX = (quadrantStripOffsetX != null && quadrantStripOffsetX.length == 4)
            ? quadrantStripOffsetX.clone()
            : DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
    }

    /** Result of a crop call. {@link #rgb} may be null on the very first
     *  invocation (no previous frame to read back) — caller falls back to mosaic. */
    public static final class Result {
        public final byte[] rgb;
        public final int quadrant;
        public final int width;
        public final int height;
        public Result(byte[] rgb, int quadrant, int w, int h) {
            this.rgb = rgb;
            this.quadrant = quadrant;
            this.width = w;
            this.height = h;
        }
    }

    public void init() {
        try {
            program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                logger.error("Foveated crop shader compilation failed");
                return;
            }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uCameraTex = GLES20.glGetUniformLocation(program, "uCameraTex");
            uCropRect = GLES20.glGetUniformLocation(program, "uCropRect");

            GLES20.glGenTextures(2, fboTextures, 0);
            GLES20.glGenFramebuffers(2, fboIds, 0);

            for (int i = 0; i < 2; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[i]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        CROP_SIZE, CROP_SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, fboTextures[i], 0);

                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    logger.error("Foveated FBO[" + i + "] incomplete: " + status);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    return;
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            readBuffer = ByteBuffer.allocateDirect(CROP_SIZE * CROP_SIZE * 4);
            readBuffer.order(ByteOrder.nativeOrder());
            scratchRgba = new byte[CROP_SIZE * CROP_SIZE * 4];
            rgbBuffer = new byte[CROP_SIZE * CROP_SIZE * 3];

            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            initialized = true;
            logger.info("FoveatedCropper initialized (640×640 double-buffered FBO, async readback)");
        } catch (Exception e) {
            logger.error("FoveatedCropper init failed: " + e.getMessage());
        }
    }

    /**
     * Submit a crop request and read back the previous one in a single GL pass.
     *
     * Renders the new (quadrant, centroid) into the current FBO, then issues
     * glReadPixels against the OTHER FBO — the one the GPU already finished
     * during the previous call. The readback is non-blocking because the
     * pipeline has drained for that target.
     *
     * Must be called on the GL thread that owns {@code cameraTextureId}.
     *
     * @return Result whose bytes correspond to the PREVIOUS request's quadrant,
     *         or null on the very first call (nothing to read back yet).
     */
    public Result crop(int cameraTextureId, int quadrant, float centroidX, float centroidY) {
        if (!initialized || quadrant < 0 || quadrant >= 4) return null;

        float quadPixelX = (centroidX + 0.5f) * 32.0f;
        float quadPixelY = (centroidY + 0.5f) * 32.0f;

        float camNormX = quadPixelX / 320.0f;
        float camNormY = quadPixelY / 240.0f;

        float stripOffsetX = quadrantStripOffsetX[quadrant];

        float cropWidthNorm = (float) CROP_SIZE / stripWidth;
        float cropHeightNorm = (float) CROP_SIZE / stripHeight;

        float centerX = stripOffsetX + camNormX * 0.25f;
        float centerY = camNormY;

        float cropLeft = centerX - cropWidthNorm / 2.0f;
        float cropTop = centerY - cropHeightNorm / 2.0f;

        float camLeft = stripOffsetX;
        float camRight = stripOffsetX + 0.25f;
        cropLeft = Math.max(camLeft, Math.min(cropLeft, camRight - cropWidthNorm));
        cropTop = Math.max(0.0f, Math.min(cropTop, 1.0f - cropHeightNorm));

        int renderIdx = currentFbo;
        int readIdx = currentFbo ^ 1;

        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        // Render the CURRENT request to renderFbo. Don't wait for it.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[renderIdx]);
        GLES20.glViewport(0, 0, CROP_SIZE, CROP_SIZE);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTex, 0);
        GLES20.glUniform4f(uCropRect, cropLeft, cropTop, cropWidthNorm, cropHeightNorm);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        // Record the request so the next call's readback can attribute these
        // bytes to the right quadrant.
        pendingQuadrant[renderIdx] = quadrant;

        Result result = null;
        if (hasPreviousFrame && pendingQuadrant[readIdx] >= 0) {
            // Read back the PREVIOUS request's render target. The GPU has
            // already finished it — glReadPixels does not stall.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[readIdx]);
            readBuffer.clear();
            GLES20.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);

            // Bulk-copy the entire RGBA buffer in ONE JNI hop, then walk it as
            // a Java byte[] for the row pack + Y-flip. Previous version did
            // 1.2M ByteBuffer.get(int) JNI hops per crop.
            readBuffer.rewind();
            readBuffer.get(scratchRgba, 0, CROP_SIZE * CROP_SIZE * 4);

            byte[] src = scratchRgba;
            byte[] dst = rgbBuffer;
            final int rowRgbaBytes = CROP_SIZE * 4;
            int dstIdx = 0;
            for (int y = CROP_SIZE - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < CROP_SIZE; x++) {
                    int s = srcRow + (x << 2);
                    dst[dstIdx++] = src[s];
                    dst[dstIdx++] = src[s + 1];
                    dst[dstIdx++] = src[s + 2];
                }
            }
            result = new Result(dst, pendingQuadrant[readIdx], CROP_SIZE, CROP_SIZE);
        }

        // Swap which FBO is the "render target" for next call. The one we just
        // rendered into becomes the read target next time around.
        currentFbo ^= 1;
        if (!hasPreviousFrame) hasPreviousFrame = true;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        return result;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void release() {
        if (fboIds[0] >= 0 || fboIds[1] >= 0) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0);
            fboIds[0] = -1;
            fboIds[1] = -1;
        }
        if (fboTextures[0] >= 0 || fboTextures[1] >= 0) {
            GLES20.glDeleteTextures(2, fboTextures, 0);
            fboTextures[0] = -1;
            fboTextures[1] = -1;
        }
        if (program > 0) {
            GLES20.glDeleteProgram(program);
            program = -1;
        }
        hasPreviousFrame = false;
        currentFbo = 0;
        pendingQuadrant[0] = -1;
        pendingQuadrant[1] = -1;
        initialized = false;
        logger.info("FoveatedCropper released");
    }
}
