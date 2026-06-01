package com.overdrive.app.streaming;

import android.opengl.GLES20;
import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import android.opengl.GLES11Ext;
import android.opengl.EGLSurface;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GpuStreamScaler - GPU-based downscaler for H.264 streaming.
 * 
 * Renders camera texture to a smaller resolution for efficient streaming.
 * Works in parallel with GpuMosaicRecorder - both sample the same source texture.
 * 
 * Typical usage:
 * - Recording: 2560×1920 @ 15fps (high quality)
 * - Streaming: 1280×960 @ 10fps (bandwidth-optimized)
 * 
 * GPU cost: <1% (texture sampling is nearly free)
 */
public class GpuStreamScaler {
    private static final String TAG = "GpuStreamScaler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // EGL and OpenGL state
    private EGLCore eglCore;
    private EGLSurface encoderSurface;
    private Surface encoderInputSurface;
    
    // OpenGL program and locations
    private int programId;
    private int uCameraTexLocation;
    private int uViewModeLocation;
    private int uApaModeLocation;
    private int uTexMatrixLocation;
    private int uApplyManualYFlipLocation;
    // Per-role producer corners + flip flags. Set per-frame from the
    // pipeline so DiLink 4's non-canonical layout (e.g. Variant A with
    // Right at producer BR, Rear at producer TR, etc.) routes correctly.
    private int uProducerForFrontLocation;
    private int uProducerForRightLocation;
    private int uProducerForRearLocation;
    private int uProducerForLeftLocation;
    private int uFlipForFrontLocation;
    private int uFlipForRightLocation;
    private int uFlipForRearLocation;
    private int uFlipForLeftLocation;
    // Red-overlay suppression strength: 0.0 = passthrough, 1.0 = active.
    // Mirrors GpuMosaicRecorder's uRedMaskStrength so the live stream is
    // free of HAL "calibration failed" chrome on uncalibrated DiLink 4 cars.
    private int uRedMaskStrengthLocation = -1;
    private int uApaCenterInsetLocation = -1;
    private volatile float apaCenterInset = 0.0f;
    private volatile boolean redMaskEnabled = false;
    private int aPositionLocation;
    private int aTexCoordLocation;

    // Producer-corner remap + per-role X/Y flip flags. Order matches
    // {Front, Right, Rear, Left}. Default = canonical 2x2; pipeline calls
    // setProducerCornerMap / setFlipFlags on DiLink 4 to override with the
    // car's actual layout. UI thread writes, GL thread reads.
    private final float[] producerCornerMap = {
        0.00f, 0.00f,
        0.50f, 0.00f,
        0.00f, 0.50f,
        0.50f, 0.50f
    };
    private final float[] flipFlags = {
        0f, 0f,  0f, 0f,  0f, 0f,  0f, 0f
    };
    private final Object producerCornerMapLock = new Object();

    // SurfaceTexture transform matrix, written from the camera GL thread
    // immediately before drawFrame. Identity until the first publish.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    
    // View mode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw
    private volatile int currentViewMode = 0;
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=APA, 2=3-cam
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Configuration
    private final int outputWidth;
    private final int outputHeight;
    private final float[] quadrantStripOffsetX;
    private final String fragmentShader;
    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f, 0.50f, 0.00f, 0.25f
    };
    
    // Fullscreen quad vertices
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    
    // Texture coordinates — UN-flipped V (V=0 at bottom of texture). The
    // vertex shader applies a manual Y-flip on legacy (uTexMatrix=identity)
    // and skips it on DiLink 4 where the SurfaceTexture matrix already
    // includes the producer's Y-flip.
    private static final float[] TEX_COORDS = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    // Vertex shader with conditional manual Y-flip (matches recorder).
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform float uApplyManualYFlip;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vec2 src = aTexCoord;\n" +
        "    if (uApplyManualYFlip > 0.5) src.y = 1.0 - src.y;\n" +
        "    vTexCoord = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n" +
        "}\n";
    
    /** Fragment shader is profile-baked at construction via
     *  {@link #buildFragmentShader(float[])}. Single-view modes use the four
     *  per-quadrant offsets so streaming a single direction picks the slice
     *  that the user mapped to that role. */

    public GpuStreamScaler(int outputWidth, int outputHeight) {
        this(outputWidth, outputHeight, null);
    }

    /**
     * @param quadrantStripOffsetX Per-role X offsets in 5120-wide 4-strip
     *     (legacy HAL). {Front, Right, Rear, Left}.
     */
    public GpuStreamScaler(int outputWidth, int outputHeight,
                           float[] quadrantStripOffsetX) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX);
    }
    
    public int getWidth() { return outputWidth; }
    public int getHeight() { return outputHeight; }
    
    /**
     * Initializes the stream scaler.
     * 
     * @param eglCore EGL context manager
     * @param encoder Hardware encoder for streaming
     */
    public void init(EGLCore eglCore, HardwareEventRecorderGpu encoder) {
        this.eglCore = eglCore;
        
        // Get encoder's input surface
        encoderInputSurface = encoder.getInputSurface();
        if (encoderInputSurface == null) {
            throw new RuntimeException("Stream encoder input surface is null");
        }
        
        // Create EGL surface from encoder surface
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);
        
        // Compile shaders (profile-baked)
        programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uViewModeLocation = GLES20.glGetUniformLocation(programId, "uViewMode");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        uTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uApplyManualYFlipLocation = GLES20.glGetUniformLocation(programId, "uApplyManualYFlip");
        uProducerForFrontLocation = GLES20.glGetUniformLocation(programId, "uProducerForFront");
        uProducerForRightLocation = GLES20.glGetUniformLocation(programId, "uProducerForRight");
        uProducerForRearLocation  = GLES20.glGetUniformLocation(programId, "uProducerForRear");
        uProducerForLeftLocation  = GLES20.glGetUniformLocation(programId, "uProducerForLeft");
        uFlipForFrontLocation = GLES20.glGetUniformLocation(programId, "uFlipForFront");
        uFlipForRightLocation = GLES20.glGetUniformLocation(programId, "uFlipForRight");
        uFlipForRearLocation  = GLES20.glGetUniformLocation(programId, "uFlipForRear");
        uFlipForLeftLocation  = GLES20.glGetUniformLocation(programId, "uFlipForLeft");
        uRedMaskStrengthLocation = GLES20.glGetUniformLocation(programId, "uRedMaskStrength");
        uApaCenterInsetLocation = GLES20.glGetUniformLocation(programId, "uApaCenterInset");
        
        GlUtil.checkGlError("glGetLocation");
        
        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
        
        logger.info("GpuStreamScaler initialized: " + outputWidth + "×" + outputHeight);
    }
    
    /**
     * Renders a frame to the stream encoder.
     * 
     * @param cameraTextureId Camera texture ID
     */
    public void drawFrame(int cameraTextureId) {
        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);
        
        // Set viewport
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        
        // Clear
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Use shader
        GLES20.glUseProgram(programId);
        
        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        
        // Set view mode (0=Mosaic, 1-4=Single camera, 5=Raw)
        GLES20.glUniform1i(uViewModeLocation, currentViewMode);
        GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
        if (uTexMatrixLocation >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, currentTexMatrix, 0);
        }
        if (uApplyManualYFlipLocation >= 0) {
            // Legacy → manual Y-flip; DiLink 4 → matrix handles it.
            GLES20.glUniform1f(uApplyManualYFlipLocation,
                cameraLayout == 3 ? 0.0f : 1.0f);
        }
        if (uProducerForFrontLocation >= 0) {
            float[] m = new float[8];
            float[] f = new float[8];
            synchronized (producerCornerMapLock) {
                System.arraycopy(producerCornerMap, 0, m, 0, 8);
                System.arraycopy(flipFlags, 0, f, 0, 8);
            }
            GLES20.glUniform2f(uProducerForFrontLocation, m[0], m[1]);
            GLES20.glUniform2f(uProducerForRightLocation, m[2], m[3]);
            GLES20.glUniform2f(uProducerForRearLocation,  m[4], m[5]);
            GLES20.glUniform2f(uProducerForLeftLocation,  m[6], m[7]);
            if (uFlipForFrontLocation >= 0) {
                GLES20.glUniform2f(uFlipForFrontLocation, f[0], f[1]);
                GLES20.glUniform2f(uFlipForRightLocation, f[2], f[3]);
                GLES20.glUniform2f(uFlipForRearLocation,  f[4], f[5]);
                GLES20.glUniform2f(uFlipForLeftLocation,  f[6], f[7]);
            }
        }
        if (uRedMaskStrengthLocation >= 0) {
            GLES20.glUniform1f(uRedMaskStrengthLocation, redMaskEnabled ? 1.0f : 0.0f);
        }
        if (uApaCenterInsetLocation >= 0) {
            GLES20.glUniform1f(uApaCenterInsetLocation, apaCenterInset);
        }

        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        
        // Submit to encoder
        eglCore.swapBuffers(encoderSurface);
    }
    
    /**
     * Sets the view mode for streaming.
     * 
     * @param mode 0=Mosaic (2x2 grid), 1=Front(cam4), 2=Right(cam3), 3=Rear(cam1), 4=Left(cam2), 5=Raw strip
     */
    public void setViewMode(int mode) {
        if (mode >= 0 && mode <= 5) {
            this.currentViewMode = mode;
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw"};
            logger.info("Stream view mode set to " + mode + " (" + modeNames[mode] + ")");
        }
    }
    
    /**
     * Gets the current view mode.
     */
    public int getViewMode() {
        return currentViewMode;
    }
    
    /**
     * Sets APA mode / camera layout.
     * 0=4-camera, 1=APA passthrough, 2=3-camera
     */
    public void setApaMode(boolean apa) {
        this.cameraLayout = apa ? 1 : 0;
    }
    
    public void setCameraLayout(int layout) {
        this.cameraLayout = layout;
    }

    /**
     * Sets the per-role producer corner XY map. Each pair is the top-left of
     * the role's 0.5×0.5 sub-rect inside the producer surface, in {Front,
     * Right, Rear, Left} order. Default = canonical 2x2; pipeline overrides
     * with Variant A constants on DiLink 4.
     */
    public void setProducerCornerMap(float[] front, float[] right,
                                     float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            producerCornerMap[0] = front[0]; producerCornerMap[1] = front[1];
            producerCornerMap[2] = right[0]; producerCornerMap[3] = right[1];
            producerCornerMap[4] = rear[0];  producerCornerMap[5] = rear[1];
            producerCornerMap[6] = left[0];  producerCornerMap[7] = left[1];
        }
    }

    /**
     * Sets the per-role X/Y flip flags ({xFlip, yFlip} ∈ {0,1}). Applied
     * inside the role's local 0.5×0.5 region. {Front, Right, Rear, Left}.
     */
    public void setFlipFlags(float[] front, float[] right,
                             float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            flipFlags[0] = front[0]; flipFlags[1] = front[1];
            flipFlags[2] = right[0]; flipFlags[3] = right[1];
            flipFlags[4] = rear[0];  flipFlags[5] = rear[1];
            flipFlags[6] = left[0];  flipFlags[7] = left[1];
        }
    }

    /**
     * Enables or disables the GL red-overlay suppression on the live stream.
     * Mirrors GpuMosaicRecorder.setRedMaskEnabled. Off by default.
     */
    /** APA center inset (esco APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        this.apaCenterInset = Math.max(0.0f, Math.min(0.20f, inset));
    }

    public void setRedMaskEnabled(boolean enabled) {
        this.redMaskEnabled = enabled;
    }

    /**
     * Publishes the SurfaceTexture transform matrix the next drawFrame will
     * upload to uTexMatrix. Called from the camera GL thread immediately
     * after consumeSurfaceTextureFrame; same thread as drawFrame so a plain
     * copy is safe (no synchronization needed).
     */
    public void setTextureMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length < 16) return;
        System.arraycopy(matrix4x4, 0, currentTexMatrix, 0, 16);
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        if (programId != 0) {
            GlUtil.deleteProgram(programId);
            programId = 0;
        }
        
        if (encoderSurface != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
        }
        
        logger.info("GpuStreamScaler released");
    }

    private static float[] normalizeOffsets(float[] quadrantStripOffsetX) {
        if (quadrantStripOffsetX == null || quadrantStripOffsetX.length != 4) {
            return DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        }
        return quadrantStripOffsetX.clone();
    }

    /**
     * Build the stream fragment shader with per-quadrant offsets baked in.
     * stripOffsets order: {Front, Right, Rear, Left} for legacy 4-strip HAL.
     * cornerOffsets order: {fX,fY, rX,rY, bX,bY, lX,lY} for 2x2-native HAL.
     * Single-view modes (uViewMode 1-4) pick the slice mapped to that role;
     * uViewMode 0 = mosaic; 5 = raw.
     */
    private static String buildFragmentShader(float[] offsets) {
        // uApaMode > 2.5 = DiLink 4 / 2x2-native HAL.
        //   uViewMode == 0 → rearrange the 2x2 into canonical Front=TL,
        //                    Right=TR, Rear=BL, Left=BR with per-role flips,
        //                    matching the recorder's mosaic output.
        //   uViewMode 1..4 → sample that role's producer corner with flip
        //                    applied within the local 0.5×0.5 region.
        //   uViewMode == 5 → raw passthrough (debug).
        // The legacy 4-strip math stays for uApaMode <= 2.5 paths.
        return String.format(Locale.US,
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTex;\n" +
            "uniform int uViewMode;\n" +
            "uniform float uApaMode;\n" +
            "uniform vec2 uProducerForFront;\n" +
            "uniform vec2 uProducerForRight;\n" +
            "uniform vec2 uProducerForRear;\n" +
            "uniform vec2 uProducerForLeft;\n" +
            "uniform vec2 uFlipForFront;\n" +
            "uniform vec2 uFlipForRight;\n" +
            "uniform vec2 uFlipForRear;\n" +
            "uniform vec2 uFlipForLeft;\n" +
            "uniform float uRedMaskStrength;\n" +
            "uniform float uApaCenterInset;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec2 samplePos;\n" +
            "    float frontOffset = %.5ff;\n" +
            "    float rightOffset = %.5ff;\n" +
            "    float rearOffset  = %.5ff;\n" +
            "    float leftOffset  = %.5ff;\n" +
            "    if (uApaMode > 2.5 && uViewMode == 0) {\n" +
            "        // DiLink 4 mosaic: rearrange the HAL's 2x2 into the\n" +
            "        // canonical Front=TL / Right=TR / Rear=BL / Left=BR\n" +
            "        // arrangement with per-role X/Y flip applied inside the\n" +
            "        // role's 0.5×0.5 slot. Mirrors GpuMosaicRecorder so the\n" +
            "        // live stream matches the recording.\n" +
            "        bool inRight = (vTexCoord.x >= 0.5 && vTexCoord.y <  0.5);\n" +
            "        bool inRear  = (vTexCoord.x <  0.5 && vTexCoord.y >= 0.5);\n" +
            "        bool inLeft  = (vTexCoord.x >= 0.5 && vTexCoord.y >= 0.5);\n" +
            "        vec2 localOffset = vec2(0.0);\n" +
            "        if (inRight) localOffset = vec2(0.5, 0.0);\n" +
            "        else if (inRear) localOffset = vec2(0.0, 0.5);\n" +
            "        else if (inLeft) localOffset = vec2(0.5, 0.5);\n" +
            "        vec2 local = vTexCoord - localOffset;\n" +
            "        vec2 producerCorner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (inRight) { producerCorner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (inRear)  { producerCorner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (inLeft)  { producerCorner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        vec2 sampledLocal = local;\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = producerCorner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else if (uApaMode > 2.5 && uViewMode >= 1 && uViewMode <= 4) {\n" +
            "        // DiLink 4 single-direction view: pick the role's\n" +
            "        // producer corner + flip and stretch to the output.\n" +
            "        // Front=1, Right=2, Rear=3, Left=4.\n" +
            "        vec2 corner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (uViewMode == 2) { corner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (uViewMode == 3) { corner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (uViewMode == 4) { corner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        vec2 sampledLocal = vec2(vTexCoord.x * 0.5, vTexCoord.y * 0.5);\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = corner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else if (uViewMode == 5) {\n" +
            "        samplePos = vTexCoord;\n" +
            "    } else if (uApaMode > 1.5) {\n" +
            "        if (uViewMode == 1) { samplePos = vec2(0.75 + vTexCoord.x * 0.25, vTexCoord.y); }\n" +
            "        else if (uViewMode == 3) { samplePos = vec2(vTexCoord.x * 0.25, vTexCoord.y); }\n" +
            "        else if (uViewMode == 2 || uViewMode == 4) { samplePos = vec2(0.25 + vTexCoord.x * 0.5, vTexCoord.y); }\n" +
            "        else {\n" +
            "            if (vTexCoord.x < 0.5) {\n" +
            "                float lx = vTexCoord.x * 0.5;\n" +
            "                float ly = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "                if (vTexCoord.y < 0.5) { samplePos = vec2(lx + 0.75, ly); }\n" +
            "                else { samplePos = vec2(lx, ly); }\n" +
            "            } else {\n" +
            "                samplePos = vec2(0.25 + (vTexCoord.x - 0.5) * 0.5, vTexCoord.y);\n" +
            "            }\n" +
            "        }\n" +
            "    } else if (uApaMode > 0.5) {\n" +
            "        samplePos = vTexCoord;\n" +
            "    } else if (uViewMode == 0) {\n" +
            "        vec2 gridPos = step(0.5, vTexCoord);\n" +
            "        float stripOffsetX;\n" +
            "        if (gridPos.x < 0.5) {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
            "        } else {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
            "        }\n" +
            "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
            "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
            "    } else {\n" +
            "        float startX = frontOffset;\n" +
            "        if (uViewMode == 2) startX = rightOffset;\n" +
            "        else if (uViewMode == 3) startX = rearOffset;\n" +
            "        else if (uViewMode == 4) startX = leftOffset;\n" +
            "        samplePos = vec2(startX + (vTexCoord.x * 0.25), vTexCoord.y);\n" +
            "    }\n" +
            "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
            com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
            "    gl_FragColor = src;\n" +
            "}\n",
            offsets[0], offsets[1], offsets[2], offsets[3]);
    }
}
