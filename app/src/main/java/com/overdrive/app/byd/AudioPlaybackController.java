package com.overdrive.app.byd;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.io.File;

/**
 * Plays a user-provided audio/video file (MP3 / WAV / MP4 / …) through the daemon's
 * {@link MediaPlayer}, routed to a chosen audio channel via {@link AudioAttributes}.
 *
 * <p>Used by the "Play audio" automation action and key-mapping action. The daemon
 * process holds a real app {@link Context} ({@link CameraDaemon#getAppContext()}), so
 * a framework MediaPlayer works here exactly as in an app — this is the interior
 * infotainment speaker path (STREAM_MUSIC etc.), distinct from the AVAS exterior
 * speaker (tone-only, MCU-hardcoded — see {@link AvasController}).
 *
 * <p><b>Single-player, replace-on-play.</b> One static MediaPlayer is reused: a new
 * play() stops+releases any current playback first, so rapid re-triggers can't stack
 * players or leak native handles. Playback uses SYNCHRONOUS {@code prepare()} +
 * {@code start()}: the caller runs on an HTTP-worker / keymap-fire POOL thread with no
 * Looper, so {@code prepareAsync()}'s onPrepared callback would post to a Looper that
 * never runs and the sound would prepare but never start (silent). prepare() blocks
 * only briefly on a local-file decode-init. The player self-releases on completion or
 * error (those listeners need no Looper — they only release).
 *
 * <p><b>File must exist and be readable by the daemon (UID 2000).</b> The path is
 * validated (exists + is a file + inside an allowed media root) before use so a
 * hand-edited automation can't point the player at an arbitrary system path.
 */
public final class AudioPlaybackController {

    private static final String TAG = "AudioPlayback";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // The single reused player + a monitor guarding its lifecycle. Guarded so a
    // play() and a completion callback (different threads) can't race the release.
    private static final Object LOCK = new Object();
    private static MediaPlayer player;
    // Audio focus currently held for playback (null = none). The infotainment head
    // unit routinely holds media focus (its own player / radio / nav), and on this
    // platform a MediaPlayer that starts WITHOUT requesting focus is mixed to
    // effectively silent — the observed "play sound does nothing" symptom. So we
    // request focus before starting and abandon it on stop/completion. Guarded by LOCK.
    private static Object audioFocusRequest; // AudioFocusRequest on API 26+, else null (legacy path)
    private static android.media.AudioManager.OnAudioFocusChangeListener focusListener;
    // On-screen SurfaceControl layer for video playback (null for audio-only). Reuses
    // the proven BsNativeLayer buffer-layer primitive at a z BELOW the blind-spot card
    // and camera-view, so a safety overlay is never occluded by a played video.
    private static com.overdrive.app.surveillance.BsNativeLayer videoLayer;
    // Video z-order: above app/content, but below the blind-spot card (MAX-1) and the
    // cluster speed badge (MAX). A played video must yield to those safety surfaces.
    private static final int VIDEO_Z = Integer.MAX_VALUE - 8;

    private AudioPlaybackController() {}

    /**
     * Map a channel name to the {@link AudioAttributes} usage that routes to that
     * infotainment stream. Mirrors the volume-channel mapping in the media handler.
     * Unknown → MEDIA.
     */
    private static int usageForChannel(String channel) {
        if (channel == null) return AudioAttributes.USAGE_MEDIA;
        switch (channel.trim().toLowerCase()) {
            case "navigation": return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case "voice":
            case "assistant":  return AudioAttributes.USAGE_ASSISTANT;
            case "phone":
            case "call":       return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case "alarm":      return AudioAttributes.USAGE_ALARM;
            case "notification":return AudioAttributes.USAGE_NOTIFICATION;
            case "system":     return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case "media":
            default:           return AudioAttributes.USAGE_MEDIA;
        }
    }

    /** The legacy stream type matching a channel, for the pre-Lollipop-style setter. */
    private static int streamForChannel(String channel) {
        if (channel == null) return AudioManager.STREAM_MUSIC;
        switch (channel.trim().toLowerCase()) {
            case "phone":
            case "call":       return AudioManager.STREAM_VOICE_CALL;
            case "alarm":      return AudioManager.STREAM_ALARM;
            case "notification":return AudioManager.STREAM_NOTIFICATION;
            case "system":     return AudioManager.STREAM_SYSTEM;
            case "navigation":
            case "voice":
            case "media":
            default:           return AudioManager.STREAM_MUSIC;
        }
    }

    // Volume floor (fraction of the stream max) below which a played sound would be
    // inaudible. If the target stream is under this, we raise it to the floor before
    // playing so a car sitting at volume 0/very-low still plays the alert. Chosen at
    // half-volume: clearly audible without being jarring.
    private static final float AUDIBLE_FLOOR_FRACTION = 0.5f;

    /**
     * Make sure the target stream isn't muted/near-zero before playing, so a played
     * sound is actually heard on a car left at volume 0 — the one audibility hole the
     * reference apps leave open (they never raise volume before playing). Only ever
     * RAISES to the floor; never lowers a user's higher setting. Caller holds LOCK.
     * Best-effort: any AudioManager error is swallowed (playback still proceeds).
     */
    private static void ensureAudibleLocked(android.media.AudioManager am, String channel) {
        try {
            int stream = streamForChannel(channel);
            int max = am.getStreamMaxVolume(stream);
            if (max <= 0) return;
            int cur = am.getStreamVolume(stream);
            int floor = Math.max(1, Math.round(max * AUDIBLE_FLOOR_FRACTION));
            if (cur < floor) {
                am.setStreamVolume(stream, floor, 0);
                logger.info("ensureAudible: raised stream " + stream + " " + cur + "→" + floor + "/" + max);
            }
        } catch (Throwable t) {
            logger.debug("ensureAudible failed (continuing): " + t.getMessage());
        }
    }

    /**
     * Request audio focus so playback is actually audible over whatever the head unit
     * is currently playing. Caller must hold {@link #LOCK}. Best-effort: focus denial
     * does not block playback (some firmwares always deny yet still mix the stream), it
     * just improves the odds the sound is heard. {@code loop} → GAIN (hold until we
     * stop); one-shot → GAIN_TRANSIENT_MAY_DUCK (duck others briefly). Abandons any
     * focus we already held first so a rapid re-trigger doesn't leak a request.
     */
    private static void requestAudioFocusLocked(String channel, boolean loop) {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) return;
        android.media.AudioManager am = (android.media.AudioManager)
                ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        ensureAudibleLocked(am, channel);
        abandonAudioFocusLocked(am);
        try {
            int gain = loop ? android.media.AudioManager.AUDIOFOCUS_GAIN
                            : android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
            // No-op listener: we don't pause on transient loss (a short alert should
            // just play through), but AudioManager requires a listener reference to
            // request/abandon symmetrically.
            focusListener = focusChange -> { /* best-effort playback; ignore changes */ };
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(gain)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(usageForChannel(channel))
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                int r = am.requestAudioFocus(req);
                audioFocusRequest = req;
                logger.info("audio focus requested (api26, gain=" + gain + ") -> " + r);
            } else {
                int r = am.requestAudioFocus(focusListener, streamForChannel(channel), gain);
                audioFocusRequest = Boolean.TRUE; // sentinel: legacy focus held
                logger.info("audio focus requested (legacy, gain=" + gain + ") -> " + r);
            }
        } catch (Throwable t) {
            logger.warn("requestAudioFocus failed (continuing anyway): " + t.getMessage());
        }
    }

    /** Abandon any audio focus we hold. Caller must hold {@link #LOCK}. Idempotent. */
    private static void abandonAudioFocusLocked(android.media.AudioManager am) {
        if (audioFocusRequest == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && audioFocusRequest instanceof android.media.AudioFocusRequest) {
                am.abandonAudioFocusRequest((android.media.AudioFocusRequest) audioFocusRequest);
            } else if (focusListener != null) {
                am.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        } finally {
            audioFocusRequest = null;
            focusListener = null;
        }
    }

    /** Abandon focus using a freshly-resolved AudioManager. Caller must hold LOCK. */
    private static void abandonAudioFocusLocked() {
        if (audioFocusRequest == null) return;
        Context ctx = CameraDaemon.getAppContext();
        android.media.AudioManager am = (ctx != null)
                ? (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE) : null;
        if (am != null) abandonAudioFocusLocked(am);
        else { audioFocusRequest = null; focusListener = null; } // no AM — just drop refs
    }

    /**
     * Start playing {@code path} on {@code channel} (audio only, no loop). Kept for
     * callers that don't need loop/video.
     */
    public static boolean play(String path, String channel) {
        return play(path, channel, false);
    }

    /**
     * Start playing {@code path} on {@code channel}, optionally looping. Returns true
     * if playback was successfully STARTED (queued for async prepare); false if the
     * file is missing/unreadable/outside the allowed roots or the player couldn't be
     * set up. Any currently-playing file is stopped first.
     *
     * <p>This is the AUDIO path (no video surface): an MP4's audio track plays, its
     * picture does not. To show video on screen the caller sets a video surface via
     * {@link #playOnSurface} (SurfaceControl lane) instead.
     */
    public static boolean play(String path, String channel, boolean loop) {
        return start(path, channel, loop, null);
    }

    /**
     * Start playing with an explicit video {@code surface} (a Surface bound to the
     * on-screen SurfaceControl lane). The picture renders to that surface; audio
     * still routes to {@code channel}. Used by the "show video on screen" path.
     */
    public static boolean playOnSurface(String path, String channel, boolean loop, android.view.Surface surface) {
        return start(path, channel, loop, surface);
    }

    /**
     * Play a video file with its PICTURE shown on the head-unit screen (and audio on
     * {@code channel}). Creates an on-screen SurfaceControl layer via the proven
     * {@link com.overdrive.app.surveillance.BsNativeLayer} primitive (at a z below the
     * blind-spot card so a safety overlay is never occluded), points the MediaPlayer's
     * video at that layer's Surface, and — once the video size is known — centres and
     * scales the layer to fit the screen preserving aspect ratio. Falls back to
     * audio-only if the layer can't be created (e.g. display unavailable ACC-off).
     */
    public static boolean playVideoOnScreen(String path, String channel, boolean loop) {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) { logger.warn("playVideoOnScreen: no context"); return false; }

        // Validate up front so we don't build a layer for a bad file.
        File f = new File(path == null ? "" : path);
        if (!isAllowedMediaFile(f)) {
            logger.warn("playVideoOnScreen: refused path: " + path);
            return false;
        }

        synchronized (LOCK) {
            releaseLocked(); // stop any current audio/video + tear down old layer
            // Grab audio focus so the video's audio is audible over the head unit's media.
            requestAudioFocusLocked(channel, loop);

            // Buffer at the full head-unit panel size; setGeometry scales/places the
            // dest rect, and the MediaPlayer writes video frames straight into it.
            android.graphics.Point panel = com.overdrive.app.surveillance.BsNativeLayer.displaySize(ctx);
            int bw = panel.x > 0 ? panel.x : 1920;
            int bh = panel.y > 0 ? panel.y : 1080;
            com.overdrive.app.surveillance.BsNativeLayer layer =
                    new com.overdrive.app.surveillance.BsNativeLayer(bw, bh, "OverdriveVideo", VIDEO_Z);
            if (!layer.create()) {
                logger.warn("playVideoOnScreen: layer create failed — falling back to audio-only");
                // Audio-only fallback (still respects loop/channel).
                return start(path, channel, loop, null);
            }
            android.view.Surface surface = layer.getSurface();
            if (surface == null) {
                layer.release();
                return start(path, channel, loop, null);
            }
            videoLayer = layer;

            final int panelW = bw, panelH = bh;
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usageForChannel(channel))
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                try { mp.setAudioStreamType(streamForChannel(channel)); } catch (Throwable ignored) {}
                mp.setDataSource(f.getAbsolutePath());
                mp.setLooping(loop);
                mp.setSurface(surface);
                if (!loop) mp.setOnCompletionListener(p -> releaseAsyncSafe(p));
                mp.setOnErrorListener((p, what, extra) -> {
                    logger.warn("playVideoOnScreen: error what=" + what + " extra=" + extra);
                    releaseAsyncSafe(p);
                    return true;
                });
                // SYNCHRONOUS prepare + start (see the audio path in start() for why:
                // this pool thread has no Looper, so prepareAsync()'s onPrepared would
                // never fire → prepared-but-silent). After a blocking prepare() the video
                // size is known immediately, so we fit the layer and start inline —
                // no listener/Looper dependency.
                mp.prepare();
                int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
                if (videoLayer == layer) { // still ours (not stopped/replaced mid-prepare)
                    if (vw > 0 && vh > 0) {
                        float scale = Math.min(panelW / (float) vw, panelH / (float) vh);
                        int dw = Math.max(1, Math.round(vw * scale));
                        int dh = Math.max(1, Math.round(vh * scale));
                        int dx = (panelW - dw) / 2, dy = (panelH - dh) / 2;
                        videoLayer.setGeometry(dx, dy, dw, dh);
                    } else {
                        videoLayer.setGeometry(0, 0, panelW, panelH);
                    }
                }
                mp.start();
                player = mp;
                logger.info("playVideoOnScreen: started '" + f.getName() + "' loop=" + loop);
                return true;
            } catch (Throwable t) {
                logger.warn("playVideoOnScreen: setup failed: " + t.getMessage());
                try { mp.release(); } catch (Throwable ignored) {}
                if (player == mp) player = null;
                if (videoLayer == layer) { layer.release(); videoLayer = null; }
                // Setup threw before player was assigned → releaseLocked() won't run.
                // Abandon the focus grabbed above so the head unit's media isn't left
                // paused/ducked indefinitely.
                abandonAudioFocusLocked();
                return false;
            }
        }
    }

    /** Shared setup for audio-only and video-on-surface playback. */
    private static boolean start(String path, String channel, boolean loop, android.view.Surface surface) {
        if (path == null || path.trim().isEmpty()) {
            logger.warn("play: empty path");
            return false;
        }
        File f = new File(path);
        if (!isAllowedMediaFile(f)) {
            logger.warn("play: refused path (missing/unreadable/outside media roots): " + path);
            return false;
        }
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            logger.warn("play: no app context");
            return false;
        }
        synchronized (LOCK) {
            // Tear down any existing playback first (replace-on-play).
            releaseLocked();
            // Grab audio focus so the sound is audible over the head unit's own media.
            requestAudioFocusLocked(channel, loop);
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usageForChannel(channel))
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                // Also set the legacy stream for older routing paths (harmless when
                // attributes are honoured; ensures volume tracks the right slider).
                try { mp.setAudioStreamType(streamForChannel(channel)); } catch (Throwable ignored) {}
                mp.setDataSource(f.getAbsolutePath());
                mp.setLooping(loop);
                if (surface != null) {
                    try { mp.setSurface(surface); } catch (Throwable t) {
                        logger.warn("play: setSurface failed (audio-only): " + t.getMessage());
                    }
                }
                // When looping, onCompletion never fires (the player restarts), so the
                // player lives until an explicit stop()/replace. When not looping, self-
                // release on completion.
                if (!loop) mp.setOnCompletionListener(p -> releaseAsyncSafe(p));
                mp.setOnErrorListener((p, what, extra) -> {
                    logger.warn("play: MediaPlayer error what=" + what + " extra=" + extra);
                    releaseAsyncSafe(p);
                    return true; // handled — onCompletion won't also fire
                });
                // SYNCHRONOUS prepare + start. This runs on an HTTP-worker / keymap-fire
                // pool thread that has NO Looper, so prepareAsync()'s onPrepared callback
                // would be posted to a Looper that never runs → the player prepares but
                // start() is never called → silent playback (volume changes, no sound —
                // the reported bug). The OEM reference plays the same way: prepare()
                // then start(), no listener. prepare() blocks only this worker for the
                // brief local-file decode-init; a bad file throws IOException, caught
                // below. On-completion/error listeners don't need a Looper (they only
                // release), so they're safe to keep.
                mp.prepare();
                mp.start();
                player = mp;
                logger.info("play: started '" + f.getName() + "' channel=" + channel
                        + " loop=" + loop + " video=" + (surface != null));
                return true;
            } catch (Throwable t) {
                logger.warn("play: setup failed: " + t.getMessage());
                try { mp.release(); } catch (Throwable ignored) {}
                if (player == mp) player = null;
                // Setup threw BEFORE player was assigned, so releaseLocked() won't run
                // and the focus we grabbed above would leak — leaving the head unit's
                // own media paused/ducked indefinitely. Abandon it here.
                abandonAudioFocusLocked();
                return false;
            }
        }
    }

    /** Stop and release any current playback. Idempotent. */
    public static void stop() {
        synchronized (LOCK) {
            releaseLocked();
        }
        logger.info("stop: playback stopped");
    }

    /** Release the current player + any on-screen video layer under the lock. Caller
     *  must hold {@link #LOCK}. */
    private static void releaseLocked() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Throwable ignored) {
                // stop() throws IllegalStateException if not started — safe to ignore.
            }
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        if (videoLayer != null) {
            try { videoLayer.release(); } catch (Throwable ignored) {}
            videoLayer = null;
        }
        // Give focus back to whatever the head unit was playing.
        abandonAudioFocusLocked();
    }

    /**
     * Release a specific player from a completion/error callback without deadlocking
     * on the monitor the callback may already be nested under. Only clears the static
     * slot if it still points at this instance (a newer play() may have replaced it),
     * and tears down the on-screen video layer alongside it.
     */
    private static void releaseAsyncSafe(MediaPlayer p) {
        synchronized (LOCK) {
            try { p.release(); } catch (Throwable ignored) {}
            if (player == p) {
                player = null;
                if (videoLayer != null) {
                    try { videoLayer.release(); } catch (Throwable ignored) {}
                    videoLayer = null;
                }
                // One-shot finished/errored and it's still the current player →
                // release focus back to the head unit.
                abandonAudioFocusLocked();
            }
        }
    }

    /**
     * Guard the file path: it must be an existing regular file, readable, and live
     * under one of the allowed media roots (the app's own external files dir, the
     * shared Music/Download dirs, or the recordings tree). This keeps a hand-crafted
     * automation from asking the daemon (UID 2000) to open an arbitrary path.
     */
    private static boolean isAllowedMediaFile(File f) {
        try {
            if (f == null || !f.exists() || !f.isFile() || !f.canRead()) return false;
            String canon = f.getCanonicalPath();
            for (String root : ALLOWED_ROOTS) {
                if (canon.startsWith(root)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // Media roots the daemon may play from. External storage (user-dropped files) +
    // the app's own dirs. All world-readable locations reachable by UID 2000.
    private static final String[] ALLOWED_ROOTS = {
            "/storage/emulated/0/",
            "/sdcard/",
            "/data/local/tmp/",
            "/mnt/",
    };
}
