package com.overdrive.app.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.animation.PathInterpolator
import java.io.File

/**
 * SOTA single-camera zoom over the AVM 2x2 mosaic.
 *
 * The recordings are written by [com.overdrive.app.surveillance.GpuMosaicRecorder]
 * as a single MP4 containing a 2x2 grid of the four AVM cameras:
 *
 *     Front (TL) | Right (TR)
 *     -----------+-----------
 *      Rear (BL) | Left  (BR)
 *
 * There is no per-camera stream on disk. To play "just the front camera in
 * fullscreen" we keep the same MP4 and apply a TextureView matrix that
 * scales 2x around the relevant quadrant corner — the user sees a single
 * camera at native source resolution (1280×960 Seal / 1280×720 Tang) with
 * zero re-encode, full audio, working scrub.
 *
 * Why TextureView (not VideoView): VideoView wraps SurfaceView whose
 * compositor-blitted surface ignores View transforms. TextureView routes
 * the producer through the SurfaceTexture path so [setTransform] applies
 * to every frame. Trade-off: TextureView burns a few MB more GPU memory
 * than SurfaceView (one extra texture ping-pong). At 2560×1920 @ 30 fps
 * this is negligible compared to the encoder side of the device.
 *
 * Lifecycle: this view owns a [MediaPlayer]. The player is created lazily
 * once the SurfaceTexture is available AND a URI has been set; calling
 * [setVideoURI] before the surface is ready stashes the URI and re-runs
 * setup when [onSurfaceTextureAvailable] fires. The player is fully
 * released in [stopPlayback] / on detach.
 */
class ZoomableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    /**
     * Note on casing: enum values are UPPERCASE for Kotlin idiom and
     * SharedPreferences storage; the web counterpart in events.js uses
     * lowercase ('all', 'front', ...) for CSS class compatibility. The
     * two surfaces don't share storage, so the casing split is a local
     * convention only — if we ever cross-correlate (analytics events,
     * shared logs), normalize at the boundary.
     */
    enum class Quadrant {
        ALL,        // No zoom — full mosaic
        FRONT,      // Top-left
        RIGHT,      // Top-right
        REAR,       // Bottom-left
        LEFT        // Bottom-right
    }

    private var mediaPlayer: MediaPlayer? = null
    // Strong ref to the producer Surface so the finalizer doesn't collect
    // it while MediaPlayer still has a soft reference. On the BYD
    // head-unit (Android 10 / DiLink) the platform's aggressive GC
    // reaps an inline-constructed Surface mid-prepare — visible as
    // "A resource failed to call release" in logcat — which breaks the
    // producer queue and leaves playback frozen with audio running.
    // Holding it here keeps it alive for the full player lifetime;
    // releasePlayer() releases it explicitly.
    private var producerSurface: Surface? = null
    private var pendingUri: Uri? = null
    private var surfaceReady: Boolean = false
    private var prepared: Boolean = false

    // Snapshot of the last-known playback position. Captured before the
    // SurfaceTexture is destroyed (on background, rotation, etc.) so the
    // re-prepared player can seek back to where the user left off — the
    // alternative is restarting every clip from frame zero whenever the
    // view detaches, which is the regression VideoView callers feared.
    private var pendingSeekMs: Int = 0
    // Whether the next onPrepared should auto-start. The fragment's
    // onPause runs *before* the SurfaceTexture is destroyed and unconditionally
    // pauses the MediaPlayer, so reading isPlaying at destroy-time always
    // sees false. Instead we provide [snapshotPlayingState] so the host
    // can capture the user's intent in its own onPause handler before
    // calling pause(); the host then reads [shouldAutoResume] in its
    // prepared listener to decide whether to start().
    private var resumeOnPrepare: Boolean = true
    // True once [snapshotPlayingState] has captured the host's intent for
    // the current prepare cycle. Cleared after each onPrepared fires.
    // When the SurfaceTexture is destroyed without an onPause path
    // (DialogFragment overlay, picture-in-picture, system permission
    // dialog), we fall back to capturing isPlaying directly in
    // onSurfaceTextureDestroyed so a paused-by-user clip doesn't
    // silently resume on surface re-create.
    private var hostSnapshotted: Boolean = false

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private var quadrant: Quadrant = Quadrant.ALL
    private var transformAnimator: ValueAnimator? = null

    // Tracks whether the producer has queued at least one frame to the
    // SurfaceTexture. On the BYD head-unit (Android 10 / DiLink),
    // calling setTransform() BEFORE the first frame lands silently
    // breaks the texture binding — MediaPlayer.start() succeeds, audio
    // ticks, but no frames ever paint. We defer the very first
    // applyTransform() until onSurfaceTextureUpdated fires, which is
    // the OS's signal that the queue is alive. Subsequent transforms
    // (quadrant zoom, size changes) are safe because the binding is
    // established.
    private var firstFrameSeen: Boolean = false

    // Reused per-frame matrix. applyTransform runs once per animator tick
    // (~14 calls per gesture) plus on size changes; allocating fresh would
    // be ~harmless but the reuse pattern keeps the GL/UI thread quiet.
    private val scratchMatrix = Matrix()

    // Listener pass-through — VideoView API parity for the fragment.
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null

    /**
     * Notified when the user double-taps the video. The receiver decides
     * what to do (typical: zoom into the tapped quadrant, or reset to ALL
     * if already zoomed). Coordinates are passed in view-local space so
     * the receiver can map them back to a [Quadrant].
     */
    fun interface OnDoubleTapListener {
        fun onDoubleTap(targetQuadrant: Quadrant)
    }

    private var doubleTapListener: OnDoubleTapListener? = null

    /**
     * GestureDetector handles single-tap → click (for the chrome auto-hide
     * toggle) and double-tap → quadrant zoom. We can't use the standard
     * setOnClickListener path alone because it fires on every UP event,
     * including the first tap of a double-tap — that would make the
     * chrome flicker on every zoom gesture.
     *
     * The detector consumes the down event so the first finger-up gets
     * 300ms to either become a confirmed single-tap (onSingleTapConfirmed)
     * or a double-tap (onDoubleTap). Either way, no accidental click is
     * delivered to the host view.
     */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = quadrantAtPoint(e.x, e.y)
            doubleTapListener?.onDoubleTap(target)
            return true
        }
    })

    init {
        surfaceTextureListener = this
        // No setBackground*() calls — TextureView throws
        // UnsupportedOperationException on every form of background drawable
        // (the producer surface IS the texture, there's no slot for a
        // separate background). The black letterbox we want around the
        // video comes from the parent layout's android:background="#000000"
        // in fragment_video_player.xml, which is what the user sees during
        // the brief "surface detached" window between clips anyway.
        isClickable = true
    }

    @SuppressWarnings("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnDoubleTapListener(l: OnDoubleTapListener?) {
        doubleTapListener = l
    }

    /**
     * Map a view-local touch point to a [Quadrant]. When already zoomed,
     * double-tap is "reset to ALL". When zoomed out the texture fills
     * the entire view edge-to-edge so a clean halves split is correct.
     */
    private fun quadrantAtPoint(x: Float, y: Float): Quadrant {
        if (quadrant != Quadrant.ALL) return Quadrant.ALL
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return Quadrant.ALL
        val left = x < vw / 2f
        val top = y < vh / 2f
        return when {
            top && left -> Quadrant.FRONT
            top && !left -> Quadrant.RIGHT
            !top && left -> Quadrant.REAR
            else -> Quadrant.LEFT
        }
    }

    // --------- VideoView-shaped surface ---------

    fun setVideoURI(uri: Uri) {
        pendingUri = uri
        prepared = false
        firstFrameSeen = false
        // A new clip starts at zero. Distinct from the surface-recreate
        // path, which preserves pendingSeekMs to resume in place.
        pendingSeekMs = 0
        resumeOnPrepare = true
        hostSnapshotted = false
        // Tear down any prior player BEFORE we reset state — releasing
        // after-the-fact would race with the new prepare.
        releasePlayer()
        if (surfaceReady) startPreparing()
    }

    fun start() {
        val mp = mediaPlayer ?: run {
            Log.w(TAG, "start() with no mediaPlayer (surfaceReady=$surfaceReady prepared=$prepared)")
            return
        }
        Log.d(TAG, "start() prepared=$prepared isPlaying=${try { mp.isPlaying } catch (_: Exception) { "?" }}")
        if (prepared) {
            try { mp.start() } catch (e: IllegalStateException) {
                Log.e(TAG, "start() IllegalStateException", e)
            }
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (prepared && mp.isPlaying) {
            try { mp.pause() } catch (_: IllegalStateException) {}
        }
    }

    fun stopPlayback() {
        releasePlayer()
        prepared = false
        pendingUri = null
        videoWidth = 0
        videoHeight = 0
        firstFrameSeen = false
    }

    val isPlaying: Boolean
        get() = try { mediaPlayer?.isPlaying == true } catch (_: IllegalStateException) { false }

    val currentPosition: Int
        get() = try {
            if (prepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (_: IllegalStateException) { 0 }

    val duration: Int
        get() = try {
            if (prepared) mediaPlayer?.duration ?: 0 else 0
        } catch (_: IllegalStateException) { 0 }

    fun seekTo(ms: Int) {
        if (prepared) {
            try { mediaPlayer?.seekTo(ms) } catch (_: IllegalStateException) {}
        }
    }

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) {
        preparedListener = l
    }

    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) {
        completionListener = l
    }

    fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) {
        errorListener = l
    }

    /**
     * Capture the current playing state into [resumeOnPrepare] so the
     * next prepare cycle (after a surface re-create) restores it. The
     * host should call this from its own `onPause` BEFORE calling
     * [pause] — otherwise the snapshot always reads false.
     */
    fun snapshotPlayingState() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        try {
            resumeOnPrepare = mp.isPlaying
            hostSnapshotted = true
        } catch (_: IllegalStateException) {
            resumeOnPrepare = false
            hostSnapshotted = true
        }
    }

    /**
     * Whether the next prepare-after-surface-recreate should auto-resume
     * playback. True for first-time prepare (autoplay) and for surface
     * recreates where the user was playing; false when the host paused
     * before backgrounding. Cleared after each prepare cycle.
     */
    fun shouldAutoResume(): Boolean = resumeOnPrepare

    /**
     * Manually program the next prepare cycle's seek + resume policy.
     * Used by hosts that survive config changes (rotation) — they
     * stash the playhead in onSaveInstanceState and call this on the
     * recreated view BEFORE / right after [setVideoURI] so the new
     * MediaPlayer seeks to the saved position instead of frame zero.
     */
    fun primeResume(positionMs: Int, autoResume: Boolean) {
        // coerceAtLeast(0) instead of `if (positionMs > 0)` — the explicit
        // overwrite (even with 0) keeps semantics symmetric: primeResume
        // is the authoritative voice for the next prepare cycle, and
        // callers shouldn't have to know that 0 is a "soft" value.
        pendingSeekMs = positionMs.coerceAtLeast(0)
        resumeOnPrepare = autoResume
        hostSnapshotted = true
    }

    // --------- Quadrant zoom ---------

    /**
     * Switch the visible quadrant. Animates the texture transform over
     * 240ms with a Material decelerate curve so the camera change is
     * legible (a hard jump misreads as a glitch). No-op if [target]
     * already matches the current quadrant.
     *
     * Pre-layout calls (width or height == 0): we update the field
     * eagerly so subsequent state-readers see the new value, but skip
     * the animator. [onSizeChanged] picks up the field once layout
     * completes and paints the matching matrix on the first frame.
     */
    fun setQuadrant(target: Quadrant, animate: Boolean = true) {
        if (target == quadrant) return
        val from = quadrant
        quadrant = target
        if (width <= 0 || height <= 0) {
            // Pre-layout: user clicks aren't physically possible (the
            // chrome buttons aren't laid out either), so animate=true here
            // can only come from a programmatic restore. We've already
            // updated the field; onSurfaceTextureUpdated will paint it as
            // a snap once the first frame is queued.
            return
        }
        // Pre-first-frame: don't apply the matrix yet (the BYD head-unit (Android 10) bug —
        // setTransform before producer queues a buffer breaks the binding
        // and freezes playback). Field is updated, so the first
        // applyTransform from onSurfaceTextureUpdated will paint the new
        // quadrant directly.
        if (!firstFrameSeen) {
            return
        }
        if (!animate) {
            applyTransform(progress = 1f, from = target, to = target)
            return
        }
        transformAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = MATERIAL_EMPHASIZED_DECELERATE
            addUpdateListener { applyTransform(it.animatedValue as Float, from, target) }
        }
        transformAnimator = anim
        anim.start()
    }

    fun getQuadrant(): Quadrant = quadrant

    // --------- Internals ---------

    private fun startPreparing() {
        val uri = pendingUri ?: return
        val texture = surfaceTexture ?: return
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            // Use MediaPlayer's default audio attributes (USAGE_MEDIA +
            // CONTENT_TYPE_MOVIE). The previous attempt to use
            // USAGE_ASSISTANCE_SONIFICATION to mix with the radio caused
            // a mid-prepare stream-type reassignment (visible as
            // "reassignAudioAttributes streamType=3 → streamType=1" in
            // logcat) which on the BYD/DiLink audio stack stalled the
            // codec — playback "stuck" with audio system registered but
            // no frames delivered.
            mp.setDataSource(context, uri)
            // Hold a strong ref so the GC doesn't collect this Surface
            // while MediaPlayer is using it (see producerSurface field
            // comment for the the BYD head-unit (Android 10) ramifications).
            val surface = Surface(texture)
            producerSurface = surface
            mp.setSurface(surface)
            mp.setOnPreparedListener { player ->
                prepared = true
                videoWidth = player.videoWidth
                videoHeight = player.videoHeight
                Log.d(TAG, "onPrepared: dims=${videoWidth}x${videoHeight} viewSize=${width}x${height}")
                // DO NOT call applyTransform here. On the BYD head-unit (Android 10), calling
                // setTransform() before the first frame is queued silently
                // breaks the SurfaceTexture binding — start() succeeds but
                // no frames paint. The first applyTransform fires from
                // onSurfaceTextureUpdated once the producer is alive.
                // Restore playback position when this prepare follows a
                // SurfaceTexture re-create (background → resume). Skip
                // when the seek would be at zero — start-of-clip seeks
                // are wasteful and on some BYD-era MediaPlayer builds
                // emit a stuttery first frame. We don't start() here —
                // the host's prepared listener owns the auto-play decision
                // (it also has to apply mute first to avoid a louder-
                // than-expected first frame).
                if (pendingSeekMs > 0) {
                    try { player.seekTo(pendingSeekMs) } catch (_: IllegalStateException) {}
                }
                preparedListener?.onPrepared(player)
                // Reset the resume flags now that we've handed off to the
                // host. Subsequent surface-destroy events will repopulate.
                // hostSnapshotted resets too so the next destroy path can
                // snapshot fresh — either via the host's onPause hook or
                // via our own fallback in onSurfaceTextureDestroyed.
                pendingSeekMs = 0
                resumeOnPrepare = true
                hostSnapshotted = false
            }
            mp.setOnCompletionListener { player ->
                completionListener?.onCompletion(player)
            }
            mp.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                prepared = false
                errorListener?.onError(player, what, extra) ?: false
            }
            mp.setOnInfoListener { _, what, extra ->
                Log.d(TAG, "MediaPlayer info: what=$what extra=$extra")
                false
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                    videoWidth = w
                    videoHeight = h
                    // Same gate as onPrepared — applyTransform only after
                    // the first frame is on the surface. If frames are
                    // already flowing (size-change mid-clip, rare), it's
                    // safe to repaint now.
                    if (firstFrameSeen) {
                        applyTransform(progress = 1f, from = quadrant, to = quadrant)
                    }
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            errorListener?.onError(mp, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        transformAnimator?.cancel()
        transformAnimator = null
        val mp = mediaPlayer
        if (mp != null) {
            try { mp.setOnPreparedListener(null) } catch (_: Exception) {}
            try { mp.setOnCompletionListener(null) } catch (_: Exception) {}
            try { mp.setOnErrorListener(null) } catch (_: Exception) {}
            try { mp.setOnVideoSizeChangedListener(null) } catch (_: Exception) {}
            try { mp.setOnInfoListener(null) } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
        // Release the Surface AFTER the player so MediaPlayer's internal
        // teardown doesn't touch a freed producer. Tracker field cleared
        // unconditionally so a partial-init failure doesn't leave a
        // dangling reference around.
        try { producerSurface?.release() } catch (_: Exception) {}
        producerSurface = null
    }

    /**
     * Compose the active matrix for this frame.
     *
     * Edge-to-edge fill — exact events.html parity (object-fit: fill on
     * the wrapper, transform-origin at view corners):
     *
     *   - ALL view: identity matrix. The TextureView's producer fills
     *     the view bounds by default; aspect mismatch between view and
     *     source is absorbed as a slight stretch (same as events.html
     *     when the wrapper aspect doesn't perfectly match the source —
     *     and it always slightly doesn't due to host chrome rounding).
     *   - Quadrant zoom: postScale(2, 2) around the VIEW's corner —
     *     pivots (0,0), (W,0), (0,H), (W,H). The corner stays anchored,
     *     the opposite half slides off-screen, and the visible quadrant
     *     fills the entire view edge-to-edge with no black bars.
     *
     * No fit-center base layer: that adds parent-letterbox bands on
     * the off-axis (which is the visible black gap the user called out
     * as "not occupying all the space"). The trade-off is a small
     * horizontal stretch on aspect-mismatched views — acceptable, and
     * matches events.html's CSS-fill behavior.
     *
     * Quadrant transitions interpolate scale + pivot together over
     * [progress] (Material 3 emphasized decelerate, set by the animator).
     */
    private fun applyTransform(progress: Float, from: Quadrant, to: Quadrant) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // View-corner pivots — same convention as events.html
        // (.zoom-front → 0% 0%, .zoom-right → 100% 0%, etc).
        val (px0, py0) = pivotFor(from, vw, vh)
        val (px1, py1) = pivotFor(to, vw, vh)
        val s0 = if (from == Quadrant.ALL) 1f else 2f
        val s1 = if (to == Quadrant.ALL) 1f else 2f
        val scale = s0 + (s1 - s0) * progress
        val px = px0 + (px1 - px0) * progress
        val py = py0 + (py1 - py0) * progress

        val matrix = scratchMatrix
        matrix.reset()
        if (scale != 1f) matrix.postScale(scale, scale, px, py)
        setTransform(matrix)
        invalidate()
    }

    /**
     * Corner pivot for a quadrant in VIEW-local coords.
     * ALL = view center, FRONT = top-left, RIGHT = top-right,
     * REAR = bottom-left, LEFT = bottom-right.
     */
    private fun pivotFor(q: Quadrant, vw: Float, vh: Float): Pair<Float, Float> = when (q) {
        Quadrant.ALL -> vw / 2f to vh / 2f
        Quadrant.FRONT -> 0f to 0f
        Quadrant.RIGHT -> vw to 0f
        Quadrant.REAR -> 0f to vh
        Quadrant.LEFT -> vw to vh
    }

    /*
     * Note on aspect-correct sizing: events.html sets the wrapper's
     * aspect-ratio inline on loadedmetadata so corner zoom fills cleanly.
     * The Android equivalent (override onMeasure + requestLayout from
     * onPrepared) was tried and reverted: on the BYD head-unit (Android 10) (BYD head-unit
     * floor) any setMeasuredDimension change AFTER the SurfaceTexture is
     * bound to a MediaPlayer surface destroys the SurfaceTexture, which
     * tears down our Surface, and onSurfaceTextureDestroyed releases the
     * MediaPlayer → playback stalls on click.
     *
     * Solution: compensate inside the matrix instead. applyTransform
     * scales the texture to source-aspect within the view bounds (so
     * a 4:3 mosaic in a wider slot is rendered as a centered 4:3 rect
     * with parent-FrameLayout black bands either side), then applies
     * the corner-pivot zoom relative to THAT rect. Same visible result
     * as the web side, no SurfaceTexture churn.
     */

    // --------- SurfaceTextureListener ---------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
        surfaceReady = true
        // Constrain the SurfaceTexture's default buffer size to the view's
        // pixel size. Without this, BYD-era SurfaceTexture allocates
        // buffers at the producer's native dimensions (2560×1920 = ~30MB
        // each × 3 buffers), and on a 770×541 view the head-unit's GPU
        // sometimes fails to allocate the third buffer silently — producer
        // queue stalls without an error event.
        try {
            surface.setDefaultBufferSize(width, height)
        } catch (_: Exception) { /* best effort */ }
        // Kick the TextureView matrix binding with the identity matrix.
        // On some the BYD head-unit (Android 10) builds (BYD head-unit included) the
        // TextureView won't pump frames from its SurfaceTexture to its
        // hardware layer until setTransform has been called at least
        // once — even with the identity matrix. Without this, MediaPlayer
        // produces frames into the SurfaceTexture forever and they never
        // make it onto the screen.
        try {
            setTransform(scratchMatrix.apply { reset() })
        } catch (_: Exception) { /* best effort */ }
        if (pendingUri != null && mediaPlayer == null) startPreparing()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: ${width}x${height}")
        // Same first-frame gate — pre-frame setTransform breaks the texture
        // binding on the BYD head-unit (Android 10). After the first frame this is safe and
        // important (parent layout may resize the view at any time).
        if (firstFrameSeen) {
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed (prepared=$prepared)")
        // Snapshot the playhead BEFORE releasing so the re-prepared player
        // can seek back to where the user left off.
        //
        // resumeOnPrepare: the host captures this via [snapshotPlayingState]
        // in its onPause (which sets [hostSnapshotted]). For transient
        // surface destroys without onPause (DialogFragment overlay,
        // picture-in-picture, system permission dialog) the host hasn't
        // set the flag — fall back to reading isPlaying ourselves so a
        // paused-by-user clip doesn't silently resume on surface re-create.
        val mp = mediaPlayer
        if (mp != null && prepared) {
            try {
                pendingSeekMs = mp.currentPosition
                if (!hostSnapshotted) {
                    resumeOnPrepare = mp.isPlaying
                }
            } catch (_: IllegalStateException) {
                // Transitioned out of a valid state during teardown;
                // explicitly zero so the next prepare doesn't carry a
                // stale seek from an earlier clip.
                pendingSeekMs = 0
                if (!hostSnapshotted) resumeOnPrepare = false
            }
        }
        surfaceReady = false
        releasePlayer()
        return true  // safe to release the texture
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Fires every time the producer queues a frame. The first one is
        // the OS's signal that the texture binding is alive, which is
        // when it's finally safe to apply our matrix transform on Android
        // 7.1. Doing it earlier silently breaks playback (start() works,
        // audio runs, but no frames paint — see firstFrameSeen comment).
        if (!firstFrameSeen) {
            firstFrameSeen = true
            Log.d(TAG, "first frame queued — applying transform")
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Same first-frame gate. onSizeChanged fires during initial layout
        // BEFORE the producer queues anything, so the unconditional call
        // we used to make here was the original instance of the bug.
        if (firstFrameSeen) {
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "ZoomableVideoView"
        // Material 3 emphasized decelerate curve.
        private val MATERIAL_EMPHASIZED_DECELERATE =
            PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    }
}
