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

    // OEM Dashcam (view-6) sampler — bound to texture unit 1 when an OEM
    // pipeline is active and the user picked DVR. uOemTexMatrix carries
    // the SurfaceTexture transform from the OEM camera (HAL Y-flip + crop)
    // so the sample stays correctly oriented.
    private int uOemTexLocation = -1;
    private int uOemTexMatrixLocation = -1;
    private int uOemActiveLocation = -1;
    private final float[] oemTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };

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
    // Reusable scratch buffers consumed inside drawFrame. The setters on
    // the JS / app thread write into producerCornerMap / flipFlags under
    // the lock; drawFrame copies into these scratch arrays under the same
    // lock and then uploads. NO per-frame allocation.
    private final float[] scratchCornerMap = new float[8];
    private final float[] scratchFlipFlags = new float[8];
    // Dirty bit — gates the eight glUniform2f calls. Setters flip it on,
    // drawFrame consumes after upload. With the layout written once at
    // start the typical case is a single upload.
    private volatile boolean producerCornerDirty = true;

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
    // Also forwards the raw aTexCoord as vUnit so the fragment shader can
    // sample non-pano sources (OEM dashcam) without composing pano's
    // texture matrix on top.
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform float uApplyManualYFlip;\n" +
        "varying vec2 vTexCoord;\n" +
        "varying vec2 vUnit;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vUnit = aTexCoord;\n" +
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
        uOemTexLocation = GLES20.glGetUniformLocation(programId, "uOemTex");
        uOemTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uOemTexMatrix");
        uOemActiveLocation = GLES20.glGetUniformLocation(programId, "uOemActive");

        GlUtil.checkGlError("glGetLocation");

        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

        // ONE-SHOT: bind texture-unit 1 to the GLES sampler `uOemTex` and
        // leave it bound. drawFrame previously rebound this every frame to
        // dodge an Adreno green-flash on first program use; binding once
        // here costs nothing and removes ~3 GL calls + an active-unit
        // ping-pong (TEXTURE1 → TEXTURE0) from the steady-state hot path.
        if (uOemTexLocation >= 0) {
            eglCore.makeCurrent(encoderSurface);
            GLES20.glUseProgram(programId);
            GLES20.glUniform1i(uOemTexLocation, 1);
            // Don't actually bind a real texture here — drawFrame's first
            // OEM-active path will. We only set the sampler unit assignment
            // (which is a program-state property, not a context-state
            // property). On Adreno the green-flash mitigation is the
            // `uOemActive` gate in the shader, not the bind.
        }

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
        
        // Quasi-static uniforms — only re-upload when a setter flagged
        // a change. uTexMatrix is exempt (camera transform changes every
        // frame, written by setTextureMatrix below).
        // CAS-claim BEFORE the reads so a setter racing into a fresh
        // dirty=true on a subsequent frame replays correctly. Mirrors
        // GpuMosaicRecorder.apaModeUniformDirty.compareAndSet pattern.
        if (uniformsDirty.compareAndSet(true, false)) {
            GLES20.glUniform1i(uViewModeLocation, currentViewMode);
            GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
            if (uApplyManualYFlipLocation >= 0) {
                // Legacy → manual Y-flip; DiLink 4 → matrix handles it.
                GLES20.glUniform1f(uApplyManualYFlipLocation,
                    cameraLayout == 3 ? 0.0f : 1.0f);
            }
            if (uRedMaskStrengthLocation >= 0) {
                GLES20.glUniform1f(uRedMaskStrengthLocation, redMaskEnabled ? 1.0f : 0.0f);
            }
            if (uApaCenterInsetLocation >= 0) {
                GLES20.glUniform1f(uApaCenterInsetLocation, apaCenterInset);
            }
        }
        if (uTexMatrixLocation >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, currentTexMatrix, 0);
        }
        if (uProducerForFrontLocation >= 0 && producerCornerDirty) {
            // Setters flip producerCornerDirty=true; we consume here under
            // the same lock so the scratch copy is consistent with the
            // setter's atomic writes. After the eight uniform uploads we
            // clear the dirty bit. Subsequent frames skip the lock + copy
            // + uniform uploads — at frame rate that's ~32B + 8 uniform
            // calls per draw saved.
            float[] m = scratchCornerMap;
            float[] f = scratchFlipFlags;
            synchronized (producerCornerMapLock) {
                System.arraycopy(producerCornerMap, 0, m, 0, 8);
                System.arraycopy(flipFlags, 0, f, 0, 8);
                producerCornerDirty = false;
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
        // OEM dashcam binding for view 6.
        //   - View 0..4 with no OEM: skip everything in this block. The
        //     fragment shader's `uViewMode == 6 && uOemActive == 1` gate
        //     ignores texture unit 1 entirely, so an unbound sampler is fine
        //     in steady state. uOemActive is uploaded once on bind/unbind.
        //   - On bind/unbind transition (oemBindingDirty=true): re-bind the
        //     texture id, write uOemActive, and re-upload uOemTexMatrix.
        //   - Per-frame while bound: only re-upload the matrix (it's the
        //     only thing that changes per frame). No glActiveTexture ping-
        //     pong, no rebind, no uniform writes for unchanged uOemActive.
        // Treat OEM as "not ready" until OEM has actually published its
        // first transform matrix snapshot. Otherwise the first frame
        // after bindOemSource samples the OEM EXTERNAL_OES texture with
        // the identity matrix declared at field init — producing a
        // garbled or upside-down frame for ~33 ms before OEM's render
        // loop publishes its real matrix. Pin uOemActive=0 until snap
        // arrives, then re-flip oemBindingDirty so the next frame picks
        // up the real bind+matrix.
        float[] snap = oemTexMatrixSnapshot;
        boolean oemReady = oemSourceActive
            && oemSurfaceTexture != null
            && oemTextureId != 0
            && snap != null;
        if (uOemTexLocation >= 0 && oemBindingDirty.compareAndSet(true, false)) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            if (oemReady) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oemTextureId);
            } else {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            if (uOemActiveLocation >= 0) {
                GLES20.glUniform1i(uOemActiveLocation, oemReady ? 1 : 0);
            }
            // If OEM is still warming (snap not yet published), force the
            // dirty bit back true so the next frame re-evaluates without
            // requiring a setter call.
            if (!oemReady && oemSourceActive) {
                oemBindingDirty.set(true);
            }
        }
        if (oemReady && uOemTexMatrixLocation >= 0) {
            // OEM ownership of the SurfaceTexture lives in OEM's render
            // loop; we just sample. snap is non-null here by the
            // oemReady gate above.
            System.arraycopy(snap, 0, oemTexMatrix, 0, 16);
            GLES20.glUniformMatrix4fv(uOemTexMatrixLocation, 1, false, oemTexMatrix, 0);
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
     * @param mode 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left,
     *             5=Raw (legacy debug passthrough — pano strip), 6=OEM Dashcam
     */
    public void setViewMode(int mode) {
        if (mode < 0 || mode > 6) return;
        if (mode == this.currentViewMode) return;   // idempotent — no upload needed
        this.currentViewMode = mode;
        this.uniformsDirty.set(true);
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
        logger.info("Stream view mode set to " + mode + " (" + modeNames[mode] + ")");
    }

    /**
     * Bind the OEM Dashcam camera texture as the secondary source. View
     * mode 6 ({@code uViewMode==6}) then samples {@code uOemTex} instead
     * of the AVM mosaic. The texture id MUST be valid in the EGL share-
     * group of THIS scaler's context — wire it via {@link
     * com.overdrive.app.camera.EGLCore#createShared} at OEM pipeline start.
     *
     * <p>The SurfaceTexture stays attached to OEM's GL context — only OEM's
     * render loop calls {@code updateTexImage}. The scaler simply samples
     * the texture handle (cross-context shared via the EGL share group)
     * and reads the most recent transform matrix that OEM published into
     * {@link #publishOemTexMatrix}.
     */
    public void bindOemSource(int oemTextureId, android.graphics.SurfaceTexture oemSt) {
        this.oemTextureId = oemTextureId;
        this.oemSurfaceTexture = oemSt;
        this.oemSourceActive = true;
        this.oemBindingDirty.set(true);
        logger.info("OEM source bound to streamScaler (tex=" + oemTextureId + ")");
    }

    public void unbindOemSource() {
        this.oemSourceActive = false;
        this.oemTextureId = 0;
        this.oemSurfaceTexture = null;
        // Drop the snapshot — without this, the OEM ping-pong matrix buffer
        // remains reachable from the scaler indefinitely, pinning OEM's
        // pipeline graph if the scaler outlives an OEM teardown.
        this.oemTexMatrixSnapshot = null;
        this.oemBindingDirty.set(true);
        logger.info("OEM source unbound from streamScaler");
    }

    /** EGLCore behind this scaler; OEM pipeline shares its context with
     *  this so texture handles cross over. Returns null when scaler isn't
     *  initialised or has been released. */
    public EGLCore getEglCore() { return eglCore; }

    private volatile int oemTextureId = 0;
    private volatile android.graphics.SurfaceTexture oemSurfaceTexture;
    private volatile boolean oemSourceActive = false;
    // Dirty bit — flipped on bindOemSource / unbindOemSource. drawFrame
    // claims via compareAndSet(true,false) BEFORE consuming oemSourceActive,
    // so a racing setter (unbind during a draw) re-flips dirty=true and
    // the next frame replays. Same lost-update fix as uniformsDirty
    // (R3 #2) — this one was missed.
    private final java.util.concurrent.atomic.AtomicBoolean oemBindingDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    // Dirty bit for the ~5 quasi-static uniforms (uViewMode, uApaMode,
    // uApplyManualYFlip, uRedMaskStrength, uApaCenterInset). drawFrame
    // claims via compareAndSet(true,false) BEFORE the upload. A setter
    // racing during the upload re-sets dirty=true and the next frame
    // replays — without CAS, the naked-volatile clear-after pattern
    // would clobber a setter's flag and silently drop the new state.
    // Mirrors GpuMosaicRecorder.apaModeUniformDirty.
    private final java.util.concurrent.atomic.AtomicBoolean uniformsDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Per-frame OEM tex matrix, published from OEM's GL thread after
    // every updateTexImage. Volatile reference flip is enough — the
    // scaler treats it as a snapshot and arraycopies into its own
    // working buffer. Producer allocates a fresh float[16] per publish
    // and never touches the buffer again, so a torn read across the
    // 16-float / 2-cache-line span can't happen.
    private volatile float[] oemTexMatrixSnapshot;

    /** Publish the OEM SurfaceTexture's transform matrix. Called from the
     *  OEM pipeline's GL thread immediately after updateTexImage. The
     *  reference is volatile so the scaler GL thread sees the latest
     *  publish. Caller MUST allocate a fresh buffer per publish — the
     *  scaler holds the reference until the next publish replaces it,
     *  so any post-publish mutation by the producer would be a torn
     *  read on the consumer side. */
    public void publishOemTexMatrix(float[] matrix) {
        if (matrix != null && matrix.length >= 16) {
            this.oemTexMatrixSnapshot = matrix;
        }
    }
    
    /**
     * Gets the current view mode.
     */
    public int getViewMode() {
        return currentViewMode;
    }
    
    // setApaMode(boolean) intentionally NOT exposed.
    // It only mapped to cameraLayout∈{0,1} and would silently downgrade
    // a DiLink 4 stream's cameraLayout=3 (per-quadrant rearrange + flip
    // path in the shader) to 0 or 1 — emitting the raw producer mosaic
    // to the H.264 stream. Callers must use setCameraLayout(int) which
    // preserves layout=3 verbatim.

    public void setCameraLayout(int layout) {
        if (layout == this.cameraLayout) return;   // idempotent
        this.cameraLayout = layout;
        this.uniformsDirty.set(true);
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
            producerCornerDirty = true;
        }
    }

    /**
     * Atomic combined setter for both producer corners and flip flags.
     * Prefer this over the split setProducerCornerMap+setFlipFlags pair —
     * a drawFrame landing between the two writes can render a frame
     * with the new corners and the OLD flips (or vice versa), producing
     * one wrong-orientation mosaic frame per init. Single lock + single
     * dirty flip removes the window.
     */
    public void setProducerLayout(float[] frontC, float[] rightC,
                                  float[] rearC, float[] leftC,
                                  float[] frontF, float[] rightF,
                                  float[] rearF, float[] leftF) {
        if (frontC == null || rightC == null || rearC == null || leftC == null
                || frontF == null || rightF == null || rearF == null || leftF == null
                || frontC.length < 2 || rightC.length < 2 || rearC.length < 2 || leftC.length < 2
                || frontF.length < 2 || rightF.length < 2 || rearF.length < 2 || leftF.length < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            producerCornerMap[0] = frontC[0]; producerCornerMap[1] = frontC[1];
            producerCornerMap[2] = rightC[0]; producerCornerMap[3] = rightC[1];
            producerCornerMap[4] = rearC[0];  producerCornerMap[5] = rearC[1];
            producerCornerMap[6] = leftC[0];  producerCornerMap[7] = leftC[1];
            flipFlags[0] = frontF[0]; flipFlags[1] = frontF[1];
            flipFlags[2] = rightF[0]; flipFlags[3] = rightF[1];
            flipFlags[4] = rearF[0];  flipFlags[5] = rearF[1];
            flipFlags[6] = leftF[0];  flipFlags[7] = leftF[1];
            producerCornerDirty = true;
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
            producerCornerDirty = true;
        }
    }

    /**
     * Enables or disables the GL red-overlay suppression on the live stream.
     * Mirrors GpuMosaicRecorder.setRedMaskEnabled. Off by default.
     */
    /** APA center inset (esco APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        float clamped = Math.max(0.0f, Math.min(0.20f, inset));
        if (Float.compare(clamped, this.apaCenterInset) == 0) return;   // idempotent
        this.apaCenterInset = clamped;
        this.uniformsDirty.set(true);
    }

    public void setRedMaskEnabled(boolean enabled) {
        if (enabled == this.redMaskEnabled) return;   // idempotent
        this.redMaskEnabled = enabled;
        this.uniformsDirty.set(true);
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
        // Drop OEM cross-context refs before anything else so any concurrent
        // OEM render-loop tick that races with our release sees the
        // unbound state and skips its publish/sample call entirely.
        try { unbindOemSource(); } catch (Throwable ignored) {}

        // glDeleteProgram requires a current EGL context. If we can't
        // make our encoder surface current (encoderSurface null because
        // of a partial init failure, or eglCore already released by a
        // sibling teardown), we MUST skip the delete and log the leak —
        // calling glDeleteProgram against a not-current context is a
        // silent no-op on Adreno (program leaks until process exit).
        if (programId != 0) {
            boolean contextReady = false;
            if (eglCore != null && encoderSurface != null) {
                try {
                    eglCore.makeCurrent(encoderSurface);
                    contextReady = true;
                } catch (Throwable t) {
                    logger.warn("release: makeCurrent failed: " + t.getMessage());
                }
            }
            if (contextReady) {
                try { GlUtil.deleteProgram(programId); } catch (Throwable ignored) {}
            } else {
                logger.warn("release: skipping glDeleteProgram (programId="
                    + programId + ", eglCore=" + (eglCore != null)
                    + ", encoderSurface=" + (encoderSurface != null)
                    + ") — leaking until process exit");
            }
            programId = 0;
        }

        if (encoderSurface != null && eglCore != null) {
            try { eglCore.destroySurface(encoderSurface); } catch (Throwable ignored) {}
            encoderSurface = null;
        }
        // Drop the EGLCore reference so getEglCore() (consumed by the
        // OEM pipeline's setParentEglCore wiring) doesn't return a
        // stale handle to a torn-down context. The scaler doesn't own
        // the EGLCore lifecycle (PanoramicCameraGpu does), so we don't
        // call release() on it — just null the field.
        eglCore = null;
        // Stale OEM source pointer won't reach the released sampler now
        // that drawFrame can never run again, but null defensively so
        // any stray getter / log shows the post-release state.
        encoderInputSurface = null;

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
            "uniform samplerExternalOES uOemTex;\n" +
            "uniform mat4 uOemTexMatrix;\n" +
            "uniform int uOemActive;\n" +
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
            "varying vec2 vUnit;\n" +
            "void main() {\n" +
            "    // View 6 with OEM bound: sample the OEM camera external\n" +
            "    // texture using its own transform matrix on the\n" +
            "    // un-transformed unit-quad coord (vUnit). The matrix\n" +
            "    // returned by SurfaceTexture.getTransformMatrix already\n" +
            "    // encodes the producer's Y-flip — applying a manual\n" +
            "    // 1.0 - vUnit.y on top double-flips and lands the feed\n" +
            "    // upside-down. Sample with the raw vUnit.\n" +
            "    if (uViewMode == 6 && uOemActive == 1) {\n" +
            "        vec2 oemTc = (uOemTexMatrix * vec4(vUnit, 0.0, 1.0)).xy;\n" +
            "        gl_FragColor = texture2D(uOemTex, oemTc);\n" +
            "        return;\n" +
            "    }\n" +
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
