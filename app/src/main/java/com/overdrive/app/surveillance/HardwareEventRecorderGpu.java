package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import com.overdrive.app.logging.DaemonLogger;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.view.Surface;

import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HardwareEventRecorderGpu - MediaCodec encoder with Surface input for GPU pipeline.
 *
 * This encoder receives frames directly from GPU via Surface, enabling
 * zero-copy recording. Configured for 2560x1920 @ 15 FPS with adaptive bitrate.
 *
 * Key features:
 * - COLOR_FormatSurface input (GPU → Encoder)
 * - Sync frame request on event detection
 * - Adaptive bitrate (3-8 Mbps)
 * - File rotation and corruption protection
 * - Stream splitting (H.264 output → Disk + Network simultaneously)
 *
 * <h3>Lock ordering (read this before adding any new lock or call site)</h3>
 * Three locks are used by this class plus its sibling {@code GpuMosaicRecorder}.
 * Always acquire them in this order; releasing in reverse is fine but never
 * acquire a higher-numbered lock while already holding a lower-numbered one
 * in reverse:
 * <ol>
 *   <li><b>{@code GpuMosaicRecorder.recordingLock}</b> — outermost. Wraps the
 *       wrapper-level {@code recording} flag and the inner call to
 *       {@code triggerEventRecording}.</li>
 *   <li><b>{@code startStopLock}</b> — encoder-level start/stop. Wraps
 *       {@link #triggerEventRecording} and the public stop entry points
 *       (so a start cannot interleave with a stop on a different thread).
 *       The drainer/disk-writer threads do NOT take this lock.</li>
 *   <li><b>{@code muxerLock}</b> — innermost. Serializes muxer field access
 *       (writeSampleData, addTrack, start, stop, release, reassign).</li>
 * </ol>
 * Violating the order risks a deadlock if any path ever tries to acquire
 * {@code startStopLock} while already holding {@code muxerLock}, or
 * {@code recordingLock} while already holding {@code startStopLock}. Today
 * no path does, and the lock-ordering invariant exists to keep it that way.
 *
 * <p>Background threads (drainer at {@link #drainerThread}, disk writer at
 * {@link #diskWriterThread}, segment-rotator running on the drainer) only
 * touch {@code muxerLock}. They observe state changes to the volatile
 * {@code isWritingToFile} / {@code muxerStarted} flags written by the
 * start/stop paths, and never try to acquire the higher-level locks.
 */
public class HardwareEventRecorderGpu {
    private static final String TAG = "HWEncoderGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Callback interface for streaming H.264 packets.
     * Enables zero-overhead streaming by reusing encoder output.
     */
    public interface StreamCallback {
        /**
         * Called when SPS/PPS headers are available (codec config).
         * Must be sent to clients before any video frames.
         */
        void onSpsPps(ByteBuffer sps, ByteBuffer pps);
        
        /**
         * Called for each encoded H.264 frame.
         * 
         * @param h264Data Encoded frame data
         * @param info Buffer info (size, offset, timestamp, flags)
         */
        void onH264Packet(ByteBuffer h264Data, MediaCodec.BufferInfo info);
    }
    
    // Configuration
    private final int width;
    private final int height;
    private int fps;
    private int bitrate;
    private String codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;  // Default H.264
    
    // Encoder
    private MediaCodec encoder;
    private Surface inputSurface;
    
    // Muxer
    // SOTA: All muxer operations (writeSampleData, addTrack, start, stop, release,
    // and reassignment of the `muxer` reference) MUST be performed while holding
    // muxerLock. This makes muxer access fully serial across the drainer thread,
    // disk writer thread, rotator (drainer), and the close caller. Without this
    // lock, a concurrent writeSampleData against a stopping muxer corrupts the
    // moov atom and leaves a sized-but-unplayable .mp4 on disk — exactly the
    // failure mode that triggered this rewrite.
    private final Object muxerLock = new Object();
    private volatile MediaMuxer muxer;
    private volatile int trackIndex = -1;
    private volatile boolean muxerStarted = false;
    // Set true by the disk writer when it gives up after repeated I/O failures
    // (typically SD card unmount). The current segment's mdat is broken at that
    // point — the close/rotate paths consult this flag and refuse to rename
    // tempFile -> outputPath, so the user never sees a half-written .mp4 with the
    // final extension. Reset whenever a new disk writer instance starts.
    private volatile boolean writerAbortedCorrupt = false;
    private MediaFormat savedFormat = null;  // Save format for reuse
    
    // Circular buffer for pre-record
    // SOTA: Static buffer shared across encoder instances to avoid 23MB reallocation on reinit
    private static H264CircularBuffer sharedPreRecordBuffer;
    private static final Object bufferLock = new Object();
    private H264CircularBuffer preRecordBuffer;  // Reference to shared buffer
    // Volatile + accessed only under startStopLock for read-modify-write safety.
    // Concurrent triggerEventRecording calls (e.g., RecordingModeManager + the
    // deferred-format listener thread firing in the same window) used to both
    // pass the `if (isWritingToFile)` check and build two muxers, leaving two
    // .mp4.tmp files on disk with timestamps milliseconds apart. The lock
    // closes that window.
    private volatile boolean isWritingToFile = false;
    private final Object startStopLock = new Object();
    private long postRecordStopTime = 0;
    
    // SOTA: Async pre-record flush queue (eliminates blocking on motion trigger)
    // Packets are queued here and written by drainEncoder() on the GL thread.
    // Bounded to prevent OOM under SD-card stalls — at 30 fps a stalled SD
    // card would otherwise let this grow without bound.
    private final ConcurrentLinkedQueue<H264CircularBuffer.Packet> pendingFlushQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean flushInProgress = false;
    private volatile long actualPreRecordDurationMs = 0;  // Actual duration of flushed pre-record buffer

    // SOTA: Muxer write queue — decouples encoder dequeue from SD card I/O.
    // The encoder dequeue loop copies frame data and releases the encoder buffer
    // immediately, then pushes to this queue. A dedicated disk writer thread
    // polls the queue and writes to the muxer. This prevents SD card I/O stalls
    // (which can be 50-100ms during garbage collection) from blocking the encoder,
    // which would cause the GPU to stall and drop camera frames.
    //
    // Capacity reasoning (post-RC7): doubled from 300 to 600 because the
    // original ceiling produced eglSwap backpressure when an SD-card delete
    // burst (e.g. cleanup of 19 files / 118 MB observed in field logs) ran
    // alongside the encoder writes. With RC5/RC8 we now defer cleanup during
    // recording, but a real-world segment rotation (~50-200 ms muxer-stop
    // pause) plus periodic GC pauses can still produce 5-15 frame backlogs
    // at 30 fps. 600 entries × ~256 KB worst-case ≈ 150 MB ceiling — paid
    // only under sustained backpressure that the drop-policy will reduce
    // anyway. Memory cost is bounded by the pool's actual usage, not the
    // capacity, so steady-state RAM is unchanged.
    private static final int MUXER_WRITE_QUEUE_CAPACITY = 600;

    /**
     * Pooled muxer packet. Direct ByteBuffer allocation is the JNI hop that
     * stalls the drainer on Adreno+lowmem hardware (5–50 ms native heap walk
     * during a 76-packet pre-record flush). The pool reuses fixed-capacity
     * direct buffers and only grows when a packet exceeds every existing
     * pool slot — which is rare in steady state.
     *
     * Pool ownership: the drainer thread acquires (or grows) a packet via
     * {@link #acquireMuxerPacket}, copies encoded bytes into it, and pushes
     * to {@link #muxerWriteQueue}. The disk writer thread pulls, calls
     * {@link MediaMuxer#writeSampleData}, then returns the packet via
     * {@link #releaseMuxerPacket}. The drop-policy in offerMuxerPacket
     * also returns evicted packets to the pool.
     */
    private static final class MuxerPacket {
        ByteBuffer data;             // direct buffer, capacity == pool slot size
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int payloadSize;             // valid byte count inside data

        boolean isKeyFrame() {
            return (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        }

        /** Reset position/limit to expose the payload to MediaMuxer. */
        void rewindForWrite() {
            data.position(0);
            data.limit(payloadSize);
        }
    }

    // Lock-free pool. Per-packet ceiling for the live encoder output mirrors
    // the H264CircularBuffer's per-bitrate sizing — 1 MB hard cap covers
    // worst-case 10 Mbps H.265 IDRs at 2560×1920. Pool grows on miss but
    // never shrinks, bounding peak native heap to MUXER_WRITE_QUEUE_CAPACITY
    // packets × 1 MB = 600 MB worst case (paid only under sustained
    // backpressure; steady state is far smaller).
    private static final int MUXER_PACKET_CEILING = 1024 * 1024;
    private final java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> muxerPacketPool =
        new java.util.concurrent.ConcurrentLinkedDeque<>();

    private MuxerPacket acquireMuxerPacket(int requiredSize) {
        // Walk pool looking for a packet whose buffer is large enough.
        // ConcurrentLinkedDeque iteration is weakly consistent which is fine
        // here — worst case we miss a fitting slot and allocate. The pool
        // grows monotonically so misses become rare.
        java.util.Iterator<MuxerPacket> it = muxerPacketPool.iterator();
        while (it.hasNext()) {
            MuxerPacket p = it.next();
            if (p.data != null && p.data.capacity() >= requiredSize) {
                if (muxerPacketPool.remove(p)) {
                    return p;
                }
            }
        }
        // None fit — allocate a fresh packet sized to ceiling so future
        // packets reuse it without re-allocating.
        MuxerPacket fresh = new MuxerPacket();
        int cap = Math.max(requiredSize, Math.min(MUXER_PACKET_CEILING, requiredSize * 2));
        fresh.data = ByteBuffer.allocateDirect(cap);
        return fresh;
    }

    private void releaseMuxerPacket(MuxerPacket p) {
        if (p == null || p.data == null) return;
        p.data.clear();
        p.payloadSize = 0;
        p.info.set(0, 0, 0, 0);
        muxerPacketPool.offer(p);
    }

    private void fillMuxerPacket(MuxerPacket dst, ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
        dst.data.clear();
        src.position(srcInfo.offset);
        src.limit(srcInfo.offset + srcInfo.size);
        dst.data.put(src);
        dst.data.flip();
        dst.payloadSize = srcInfo.size;
        dst.info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
    }

    // Use Deque for drop-oldest semantics. Bounded capacity prevents unbounded
    // growth under SD-card backpressure. take() in the disk writer wakes
    // immediately on push — no 4 ms poll-loop latency.
    private final java.util.concurrent.LinkedBlockingDeque<MuxerPacket> muxerWriteQueue =
        new java.util.concurrent.LinkedBlockingDeque<>(MUXER_WRITE_QUEUE_CAPACITY);
    private final java.util.concurrent.atomic.AtomicLong muxerDropCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * In-flight finalizer count. Bumped at the head of finalizeOldSegmentAsync,
     * decremented in its finally block. closeEventRecording / release() drain
     * this to zero (with timeout) so the caller can be sure no background
     * thread is still holding a stale muxer or about to fire onFileSaved on
     * a torn-down pipeline.
     *
     * Without this guard, a stop+restart cycle within ~150 ms of a rotation
     * tick can race the finalizer's rename → onFileSaved into the new
     * encoder's lifecycle (RC-audit Finding R1).
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlightFinalizers =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final Object finalizerJoinLock = new Object();

    /**
     * Add a packet to the muxer write queue. If the queue is full, drop the
     * oldest non-keyframe packet to make room. If the queue is full and
     * everything in it is a keyframe (extreme stall), drop the new packet
     * unless it's also a keyframe (in which case drop the oldest keyframe).
     *
     * Logged every 30 drops so a chronic SD-card stall is visible in the
     * field instead of silently corrupting recordings.
     */
    private void offerMuxerPacket(MuxerPacket packet) {
        if (muxerWriteQueue.offer(packet)) {
            return;
        }
        // Queue full. Walk from the head looking for a non-keyframe to drop;
        // dropped packets are returned to the pool so their direct buffer is
        // reused immediately by the very packet trying to enter the queue.
        java.util.Iterator<MuxerPacket> it = muxerWriteQueue.iterator();
        MuxerPacket evicted = null;
        while (it.hasNext()) {
            MuxerPacket head = it.next();
            if (!head.isKeyFrame()) {
                it.remove();
                evicted = head;
                break;
            }
        }
        if (evicted == null) {
            // All entries are keyframes — drop the oldest. This only happens
            // under multi-second SD stalls; the recording will have a gap
            // but the daemon stays alive.
            evicted = muxerWriteQueue.pollFirst();
        }
        if (evicted != null) {
            releaseMuxerPacket(evicted);
        }
        // Now there's space.
        muxerWriteQueue.offer(packet);
        long n = muxerDropCount.incrementAndGet();
        if (n % 30 == 1) {
            logger.warn("Muxer write queue saturated — dropped " + n
                + " packets total. SD card likely stalled.");
        }
    }
    private volatile boolean diskWriterRunning = false;
    private Thread diskWriterThread;
    
    // SOTA: Background drainer thread (moves SD card I/O off GL thread)
    private volatile boolean drainerRunning = false;
    private Thread drainerThread;
    private static final int DRAIN_INTERVAL_MS = 16;  // ~60Hz cadence, matches frame arrival rate
    
    // SOTA: Flag to disable pre-record buffer for stream-only encoders
    private boolean usePreRecordBuffer = true;

    // FIX (Bug A): Initial pre-record buffer duration. Settable BEFORE init() so the
    // first allocation honours the user's saved value instead of the hardcoded 5s.
    // setPreRecordDuration() can still resize after init.
    private int preRecordDurationSeconds = 5;
    
    // Pre-allocated BufferInfo — reused every drain cycle to avoid per-frame allocation
    private final MediaCodec.BufferInfo reusableBufferInfo = new MediaCodec.BufferInfo();
    
    // Callback for when file is closed
    private Runnable fileClosedCallback;
    
    // Streaming
    private StreamCallback streamCallback;
    private boolean streamHeadersSent = false;
    
    // Recording state
    private boolean recording = false;
    private String outputPath;
    private File tempFile;
    private int recordedFrames = 0;
    private long firstFramePtsUs = -1;   // PTS of first frame written to muxer
    private long lastFramePtsUs = -1;    // PTS of last frame written to muxer
    
    // Segment rotation
    private long segmentStartTime = 0;
    private static final long SEGMENT_DURATION_MS = 2 * 60 * 1000;  // 2 minutes
    private int segmentNumber = 0;
    private String segmentBasePath = null;  // Base path for segment rotation (without .mp4)
    
    // Timing
    private long startTimeNs = 0;
    
    /**
     * Creates a GPU-compatible hardware encoder.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 6-8 Mbps)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }
    
    /**
     * Creates a GPU-compatible hardware encoder with codec selection.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 2-6 Mbps)
     * @param codecMimeType MIME type (MIMETYPE_VIDEO_AVC for H.264, MIMETYPE_VIDEO_HEVC for H.265)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate, String codecMimeType) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.codecMimeType = codecMimeType;
    }
    
    /**
     * Returns the configured frame rate (KEY_FRAME_RATE on the encoder format).
     * Used by the pipeline to detect FPS config drift.
     */
    public int getFps() {
        return fps;
    }

    /**
     * Sets the codec MIME type before initialization.
     * Must be called before init().
     *
     * @param mimeType MIMETYPE_VIDEO_AVC (H.264) or MIMETYPE_VIDEO_HEVC (H.265)
     */
    public void setCodecMimeType(String mimeType) {
        if (encoder != null) {
            logger.warn("Cannot change codec after initialization - restart required");
            return;
        }
        this.codecMimeType = mimeType;
        logger.info("Codec set to: " + (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265/HEVC" : "H.264/AVC"));
    }
    
    /**
     * Gets the current codec MIME type.
     */
    public String getCodecMimeType() {
        return codecMimeType;
    }
    
    /**
     * Checks if using H.265/HEVC codec.
     */
    public boolean isHevcCodec() {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMimeType);
    }
    
    /**
     * Initializes the encoder with Surface input.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        logger.info( String.format("Initializing: %dx%d @ %dfps, %d Mbps, codec=%s",
                width, height, fps, bitrate / 1_000_000,
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264"));
        
        // Create format with Surface input - use configured codec
        MediaFormat format = MediaFormat.createVideoFormat(codecMimeType, width, height);
        
        // CRITICAL: Use COLOR_FormatSurface for GPU input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);  // I-frame every 2 seconds

        // Bitrate mode left to encoder default (typically VBR). CBR was tried
        // but caused recordings to freeze 5-6s in on the BYD DiLink 5.0 H.265
        // encoder — the platform encoder doesn't honor the explicit
        // BITRATE_MODE_CBR cleanly and produces malformed bitstream that
        // stalls subsequent frames. Reverted.

        // Set max input size to prevent Qualcomm crashes
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);
        
        // Low latency hints (optional)
        try {
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception e) {
            // Ignore if not supported
        }

        // KEY_OPERATING_RATE pins the platform encoder's processing rate to
        // the configured fps. Without this, the SoC governor can briefly
        // downclock the encoder/GPU between frames, causing periodic 100-200ms
        // output gaps that propagate as eglSwap stalls (the encoder's input
        // pool fills behind a transiently-slowed encode pipeline).
        //
        // Setting this to fps ≥ KEY_FRAME_RATE tells the encoder "commit to
        // sustaining at least this throughput" — Qualcomm/Snapdragon platforms
        // honor this by holding the encoder hardware at full frequency for
        // the duration of the recording. Cost: marginally higher power; that
        // tradeoff is correct here because we already have the encoder
        // running continuously for the pre-record buffer.
        //
        // Available since API 23 (we're targeting min 28). Wrapped in try
        // so non-Qualcomm or older platforms gracefully ignore it.
        try {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
            logger.info("Encoder OPERATING_RATE pinned at " + fps);
        } catch (Throwable t) {
            logger.warn("Could not set OPERATING_RATE: " + t.getMessage());
        }
        
        // H.265 specific optimizations for Snapdragon 665
        if (codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            try {
                // Use Main profile for better compatibility
                format.setInteger(MediaFormat.KEY_PROFILE, 
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4);
                logger.info("H.265 profile set to Main/Level 4");
            } catch (Exception e) {
                logger.warn("Could not set H.265 profile: " + e.getMessage());
            }
        } else {
            // H.264: Use Baseline Profile for iOS Safari compatibility
            try {
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                logger.info("H.264 profile set to Baseline/Level 3.1 (iOS compatible)");
            } catch (Exception e) {
                logger.warn("Could not set H.264 profile: " + e.getMessage());
            }
        }
        
        // CRITICAL: All MediaCodec operations can block if hardware encoder is stuck
        // Wrap each operation with a timeout to prevent daemon freeze
        final MediaFormat finalFormat = format;
        final String finalCodecMimeType = codecMimeType;
        
        // Create encoder with timeout
        logger.info("Creating MediaCodec encoder...");
        final MediaCodec[] encoderResult = {null};
        final Exception[] createError = {null};
        Thread createThread = new Thread(() -> {
            try {
                encoderResult[0] = MediaCodec.createEncoderByType(finalCodecMimeType);
            } catch (Exception e) {
                createError[0] = e;
            }
        }, "EncoderCreate");
        createThread.start();
        try {
            createThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder create interrupted");
        }
        if (createThread.isAlive()) {
            logger.error("MediaCodec.createEncoderByType TIMEOUT - hardware encoder stuck");
            createThread.interrupt();
            throw new RuntimeException("Encoder create timeout - try restarting mediaserver");
        }
        if (createError[0] != null) {
            throw createError[0];
        }
        encoder = encoderResult[0];
        // Confirm the negotiated codec name on this device, not just our intent.
        // If the device-side codec selection silently downgraded HEVC→AVC (rare,
        // but possible if the platform encoder list rejects HEVC for our params),
        // this log line surfaces it instead of leaving the user to guess from
        // file sizes.
        try {
            String negotiatedName = encoder.getName();
            logger.info("MediaCodec encoder created (codec=" + finalCodecMimeType
                    + ", impl=" + negotiatedName + ")");
        } catch (Exception ignored) {
            logger.info("MediaCodec encoder created");
        }
        
        // Configure encoder with timeout
        logger.info("Configuring encoder...");
        final boolean[] configDone = {false};
        final Exception[] configError = {null};
        Thread configThread = new Thread(() -> {
            try {
                encoder.configure(finalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                configDone[0] = true;
            } catch (Exception e) {
                configError[0] = e;
            }
        }, "EncoderConfig");
        configThread.start();
        try {
            configThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder config interrupted");
        }
        if (!configDone[0]) {
            if (configThread.isAlive()) {
                logger.error("encoder.configure TIMEOUT - hardware encoder stuck");
                configThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Encoder configure timeout");
            }
            if (configError[0] != null) {
                throw configError[0];
            }
        }
        logger.info("Encoder configured");
        
        // Create input surface with timeout
        logger.info("Creating input surface...");
        final Surface[] surfaceResult = {null};
        final Exception[] surfaceError = {null};
        Thread surfaceThread = new Thread(() -> {
            try {
                surfaceResult[0] = encoder.createInputSurface();
            } catch (Exception e) {
                surfaceError[0] = e;
            }
        }, "EncoderSurface");
        surfaceThread.start();
        try {
            surfaceThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Surface create interrupted");
        }
        if (surfaceResult[0] == null) {
            if (surfaceThread.isAlive()) {
                logger.error("createInputSurface TIMEOUT - hardware encoder stuck");
                surfaceThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Surface create timeout");
            }
            if (surfaceError[0] != null) {
                throw surfaceError[0];
            }
        }
        inputSurface = surfaceResult[0];
        logger.info("Input surface created");
        
        // Start encoder with timeout
        logger.info("Starting encoder...");
        final Exception[] startError = {null};
        final boolean[] startDone = {false};
        
        Thread startThread = new Thread(() -> {
            try {
                encoder.start();
                startDone[0] = true;
            } catch (Exception e) {
                startError[0] = e;
            }
        }, "EncoderStart");
        
        startThread.start();
        try {
            startThread.join(10000); // 10 second timeout
        } catch (InterruptedException e) {
            logger.warn("Encoder start interrupted");
        }
        
        if (!startDone[0]) {
            if (startThread.isAlive()) {
                logger.error("Encoder start TIMEOUT after 10s - hardware encoder may be stuck");
                startThread.interrupt();
                // Try to release the encoder
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
                inputSurface = null;
                throw new RuntimeException("Encoder start timeout - hardware encoder busy or stuck");
            }
            if (startError[0] != null) {
                throw startError[0];
            }
        }
        logger.info("Encoder started");
        
        // SOTA: Reuse shared buffer across encoder instances (avoids 23MB allocation on reinit)
        // Only allocate for encoders that use pre-record (not stream-only encoders)
        if (usePreRecordBuffer) {
            synchronized (bufferLock) {
                int desiredSec = Math.max(1, preRecordDurationSeconds);
                // Pass the encoder's fps so the buffer pool is sized for
                // duration × fps + headroom. Without this, switching to
                // 30 fps recording exhausts the pool (sized for 15 fps) and
                // triggers emergency allocations under sustained load.
                if (sharedPreRecordBuffer == null) {
                    logger.info("Allocating NEW pre-record buffer (" + desiredSec
                        + " sec @ " + fps + "fps @ " + (bitrate / 1_000_000) + "Mbps)...");
                    sharedPreRecordBuffer = new H264CircularBuffer(desiredSec, fps, bitrate);
                } else {
                    long desiredUs = desiredSec * 1_000_000L;
                    // Reuse only when duration, fps AND bitrate match. The
                    // bitrate check matters because the per-packet ceiling
                    // is sized from bitrate at ctor time — a STANDARD-sized
                    // pool reused at MAX silently drops every I-frame
                    // (>525 KB at 10 Mbps H.265) and leaves the rolling
                    // window with zero keyframes, breaking pre-record.
                    boolean durationMismatch = sharedPreRecordBuffer.getMaxDurationUs() != desiredUs;
                    boolean fpsMismatch = sharedPreRecordBuffer.getSizedForFps() != Math.max(10, Math.min(30, fps));
                    boolean bitrateMismatch = sharedPreRecordBuffer.getSizedForBitrate() != Math.max(500_000, bitrate);
                    if (durationMismatch || fpsMismatch || bitrateMismatch) {
                        logger.info("Resizing existing pre-record buffer to "
                            + desiredSec + " sec @ " + fps + "fps @ "
                            + (bitrate / 1_000_000) + "Mbps (was "
                            + (sharedPreRecordBuffer.getMaxDurationUs() / 1_000_000L) + "s @ "
                            + sharedPreRecordBuffer.getSizedForFps() + "fps @ "
                            + (sharedPreRecordBuffer.getSizedForBitrate() / 1_000_000) + "Mbps)");
                        sharedPreRecordBuffer = new H264CircularBuffer(desiredSec, fps, bitrate);
                    } else {
                        logger.info("Reusing EXISTING pre-record buffer (Zero-Allocation)");
                        sharedPreRecordBuffer.clear();  // Clear old data but keep allocated memory
                    }
                }
                preRecordBuffer = sharedPreRecordBuffer;
            }
        } else {
            logger.info("Pre-record buffer disabled (stream-only mode)");
            preRecordBuffer = null;
        }

        // SOTA: Start background drainer thread (moves SD card I/O off GL thread)
        startDrainerThread();

        logger.info("Encoder initialized successfully"
                + (usePreRecordBuffer ? " (pre-record: " + Math.max(1, preRecordDurationSeconds) + " sec)" : " (stream-only)"));
    }
    
    /**
     * Updates the pre-record buffer size.
     * 
     * SOTA: Reuses existing buffer if same duration to avoid 23MB allocation.
     * Only recreates if duration actually changed.
     * 
     * @param durationSeconds New buffer duration in seconds
     */
    public void setPreRecordDuration(int durationSeconds) {
        int clamped = Math.max(1, durationSeconds);
        // FIX (Bug A): always remember the desired duration so that a later init()
        // (e.g. after pipeline reinit) allocates the correct size.
        this.preRecordDurationSeconds = clamped;
        synchronized (bufferLock) {
            if (sharedPreRecordBuffer != null) {
                long currentMaxDurationUs = clamped * 1_000_000L;
                // Reuse only when duration, fps AND bitrate match. See the
                // symmetric guard in HardwareEventRecorderGpu.init() for the
                // rationale on each axis. Bitrate matters because the
                // per-packet ceiling is sized from bitrate at ctor time —
                // a quality bump after this object was built would otherwise
                // start dropping I-frames silently.
                int safeFps = Math.max(10, Math.min(30, fps));
                int safeBitrate = Math.max(500_000, bitrate);
                boolean durationMismatch = sharedPreRecordBuffer.getMaxDurationUs() != currentMaxDurationUs;
                boolean fpsMismatch = sharedPreRecordBuffer.getSizedForFps() != safeFps;
                boolean bitrateMismatch = sharedPreRecordBuffer.getSizedForBitrate() != safeBitrate;
                if (durationMismatch || fpsMismatch || bitrateMismatch) {
                    logger.info("Pre-record buffer recreate: " + clamped + " sec @ "
                        + fps + "fps @ " + (bitrate / 1_000_000) + "Mbps (was "
                        + (sharedPreRecordBuffer.getMaxDurationUs() / 1_000_000L) + "s @ "
                        + sharedPreRecordBuffer.getSizedForFps() + "fps @ "
                        + (sharedPreRecordBuffer.getSizedForBitrate() / 1_000_000) + "Mbps)");
                    sharedPreRecordBuffer = new H264CircularBuffer(clamped, fps, bitrate);
                    preRecordBuffer = sharedPreRecordBuffer;
                } else {
                    logger.info("Pre-record buffer already at " + clamped + "s @ "
                        + safeFps + "fps @ " + (safeBitrate / 1_000_000)
                        + "Mbps, clearing only");
                    sharedPreRecordBuffer.clear();
                }
            }
        }
    }
    
    /**
     * Sets whether this encoder uses the pre-record buffer.
     * Should be set to false for stream-only encoders.
     * 
     * @param useBuffer true to use pre-record buffer, false for stream-only mode
     */
    public void setUsePreRecordBuffer(boolean useBuffer) {
        this.usePreRecordBuffer = useBuffer;
        if (!useBuffer) {
            logger.info("Pre-record buffer disabled (stream-only mode)");
        }
    }
    
    /**
     * Sets the streaming callback for H.264 packet distribution.
     * 
     * If the encoder has already output its format (SPS/PPS), the callback
     * will receive them immediately. This handles the case where a new
     * client connects after the encoder has already started.
     * 
     * @param callback Callback to receive H.264 packets
     */
    public void setStreamCallback(StreamCallback callback) {
        this.streamCallback = callback;
        this.streamHeadersSent = false;
        
        // If format already available, send SPS/PPS immediately
        // This handles late-joining clients after encoder has started
        if (callback != null && savedFormat != null) {
            try {
                ByteBuffer sps = savedFormat.getByteBuffer("csd-0");
                ByteBuffer pps = savedFormat.getByteBuffer("csd-1");
                if (sps != null && pps != null) {
                    callback.onSpsPps(sps.duplicate(), pps.duplicate());
                    streamHeadersSent = true;
                    logger.info("SPS/PPS sent immediately to new callback (late join)");
                }
            } catch (Exception e) {
                logger.error("Failed to send SPS/PPS to new callback", e);
            }
        }
        
        logger.info("Stream callback registered");
    }
    
    /**
     * Checks if the encoder format (SPS/PPS) is available.
     *
     * @return true if format is available, false otherwise
     */
    public boolean isFormatAvailable() {
        return savedFormat != null;
    }

    /**
     * One-shot listener invoked the first time the encoder publishes its
     * output format (SPS/PPS available). Set by GpuSurveillancePipeline so
     * deferred recordings can start as soon as the format is ready, without
     * waiting for the camera-probe callback that doesn't fire when probe
     * is disabled (validated camera config path on cold start).
     */
    public interface FormatAvailableListener {
        void onFormatAvailable();
    }

    private volatile FormatAvailableListener formatAvailableListener = null;

    /**
     * Listener fired when {@link #rotateSegment()} finalises an old segment
     * before opening a new one. Lets the surveillance engine flush hero
     * thumbnails + JSON sidecar against the segment's actual filename
     * (otherwise long events split across multiple .mp4 files would attach
     * all metadata to the FIRST segment, leaving subsequent segments as
     * unbadged plain MP4s in the recordings list).
     *
     * Fired AFTER the old segment is renamed from .tmp to its final .mp4
     * name. Safe to read the file from inside {@code onSegmentClosed}.
     * Fires on the encoder drainer thread; consumers should not block.
     */
    public interface SegmentListener {
        /**
         * @param closedSegment   the .mp4 file just renamed from .tmp,
         *                        or {@code null} if the rotation produced
         *                        no playable file (broken segment quarantined).
         * @param newSegment      the new .mp4 path (still pre-finalize, pending
         *                        bytes), so the engine knows what filename the
         *                        next stop / next rotation will land on.
         */
        void onSegmentClosed(java.io.File closedSegment, java.io.File newSegment);
    }

    private volatile SegmentListener segmentListener = null;

    public void setSegmentListener(SegmentListener listener) {
        this.segmentListener = listener;
    }

    public void setFormatAvailableListener(FormatAvailableListener listener) {
        this.formatAvailableListener = listener;
        // If format is already available when the listener is registered,
        // fire immediately so callers don't miss the edge.
        if (listener != null && savedFormat != null) {
            try { listener.onFormatAvailable(); }
            catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
            this.formatAvailableListener = null;
        }
    }
    
    /**
     * Waits for the encoder format to become available.
     * 
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if format became available, false if timeout
     */
    public boolean waitForFormat(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (savedFormat == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Removes the streaming callback.
     */
    public void clearStreamCallback() {
        this.streamCallback = null;
        this.streamHeadersSent = false;
        logger.info("Stream callback cleared");
    }
    
    /**
     * Gets the input surface for GPU rendering.
     * 
     * @return Surface that GPU should render to
     */
    public Surface getInputSurface() {
        return inputSurface;
    }
    
    /**
     * Triggers event recording with pre-record buffer flush.
     * 
     * SOTA: Non-blocking implementation. Pre-record packets are queued
     * and written by drainEncoder() on the GL thread, eliminating the
     * blocking I/O that caused video stutter on motion detection.
     * 
     * @param outputPath Path for the output MP4 file
     * @param postRecordDurationMs Post-record duration in milliseconds
     * @return true if started successfully, false otherwise
     */
    public boolean triggerEventRecording(String outputPath, long postRecordDurationMs) {
        // Hold startStopLock across the entire start path so two concurrent
        // callers can't both observe isWritingToFile == false and race ahead
        // to build two muxers. The work inside is dominated by a few mkdirs
        // and a MediaMuxer ctor (sub-100ms typically), so blocking another
        // start request for that long is acceptable — the alternative is the
        // duplicate-files-on-disk bug.
        synchronized (startStopLock) {
            if (isWritingToFile) {
                // Already recording, extend post-record duration
                postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;
                logger.info("Event extended - post-record timer reset to " + postRecordDurationMs + "ms");
                return true;
            }

        try {
            this.outputPath = outputPath;
            
            // Write to temp file during recording
            tempFile = new File(outputPath + ".tmp");
            
            // Ensure parent directory exists
            File parentDir = tempFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created && !parentDir.exists()) {
                    // Retry once after short delay (SD card may need time to be accessible)
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    created = parentDir.mkdirs();
                }
                if (created) {
                    logger.info("Created parent directory: " + parentDir.getAbsolutePath());
                    parentDir.setReadable(true, false);
                    parentDir.setWritable(true, false);
                    parentDir.setExecutable(true, false);
                } else if (!parentDir.exists()) {
                    logger.error("Failed to create parent directory: " + parentDir.getAbsolutePath());
                    return false;
                }
                // Directory exists (either created or already existed) - continue
            }
            
            // SOTA: clear any stale per-segment state from the previous
            // recording before the new muxer goes live. Without this, leftover
            // PTS/frame counters from a prior run would mislead the duration
            // computation in closeEventRecording.
            recordedFrames = 0;
            firstFramePtsUs = -1;
            lastFramePtsUs = -1;
            writerAbortedCorrupt = false;

            // Create muxer. Hold muxerLock so the disk writer never observes a
            // half-constructed muxer (e.g., started but trackIndex still -1).
            boolean muxerOk = false;
            synchronized (muxerLock) {
                try {
                    muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                    // If we have a saved format, use it immediately
                    if (savedFormat != null) {
                        trackIndex = muxer.addTrack(savedFormat);
                        muxer.start();
                        muxerStarted = true;
                        logger.info("Muxer started with saved format (track=" + trackIndex + ")");
                    }
                    muxerOk = true;
                } catch (Exception e) {
                    logger.error("MediaMuxer setup failed", e);
                    if (muxer != null) {
                        try { muxer.release(); } catch (Exception ignored) {}
                        muxer = null;
                    }
                    muxerStarted = false;
                    trackIndex = -1;
                }
            }
            if (!muxerOk) {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                tempFile = null;
                return false;
            }

            if (savedFormat != null) {
                // SOTA: Queue pre-record packets for async flush (NON-BLOCKING!)
                // drainEncoder() will write these on the GL thread
                List<H264CircularBuffer.Packet> preRecordPackets = preRecordBuffer.getPacketsForFlush();
                double preRecordDuration = preRecordPackets.isEmpty() ? 0 :
                    (preRecordPackets.get(preRecordPackets.size()-1).info.presentationTimeUs -
                     preRecordPackets.get(0).info.presentationTimeUs) / 1_000_000.0;

                // Store actual pre-record duration for timeline alignment
                actualPreRecordDurationMs = (long)(preRecordDuration * 1000);

                // Add all packets to the flush queue (instant, no I/O)
                pendingFlushQueue.addAll(preRecordPackets);
                flushInProgress = true;

                logger.info(String.format("Queued %d pre-record packets (%.1f sec) for async flush",
                        preRecordPackets.size(), preRecordDuration));
            }

            // Reset state
            startTimeNs = System.nanoTime();
            segmentStartTime = System.currentTimeMillis();  // Enable segment rotation for long events
            segmentNumber = 0;
            segmentBasePath = outputPath.replaceAll("\\.mp4$", "");  // Store base path for segment rotation
            postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;

            isWritingToFile = true;
            recording = true;  // Keep for compatibility

            logger.info(String.format("Event recording started: %s (codec=%s, bitrate=%d Mbps, post-record=%dms)",
                tempFile.getName(),
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                bitrate / 1_000_000,
                postRecordDurationMs));
            return true;

        } catch (Exception e) {
            logger.error("Failed to trigger event recording", e);
            // Best-effort cleanup so a partial init doesn't leave a muxer alive
            // referencing a now-orphaned tmp file.
            synchronized (muxerLock) {
                if (muxer != null) {
                    try { muxer.release(); } catch (Exception ignored) {}
                    muxer = null;
                }
                muxerStarted = false;
                trackIndex = -1;
            }
            if (tempFile != null && tempFile.exists()) tempFile.delete();
            tempFile = null;
            isWritingToFile = false;
            recording = false;
            return false;
        }
        } // end synchronized (startStopLock)
    }

    /**
     * Legacy method for compatibility - redirects to triggerEventRecording.
     */
    public boolean startRecording(String outputPath) {
        return triggerEventRecording(outputPath, 5000);  // Default 5 sec post-record
    }
    
    /**
     * Stops recording immediately or schedules post-record stop.
     *
     * <p>Held under {@code startStopLock} for symmetry with
     * {@link #triggerEventRecording}: a start cannot race a stop. The check
     * outside the lock is a cheap volatile read so callers don't pay the lock
     * cost when there's nothing to stop. The check inside the lock is the
     * authoritative one.
     *
     * @param immediate If true, stops immediately. If false, does nothing (timeout handled by caller)
     * @param postRecordDurationMs Post-record duration (ignored, kept for API compatibility)
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!isWritingToFile) {
            return;
        }
        synchronized (startStopLock) {
            if (!isWritingToFile) {
                // Another thread already finalised between the volatile read
                // above and our acquisition of the lock — nothing to do.
                return;
            }
            if (immediate) {
                closeEventRecording();
            }
            // Note: Post-record timeout is now handled by SurveillanceEngineGpu
            // The encoder just writes frames until explicitly told to stop
        }
    }
    
    /**
     * Closes the current event recording and finalizes the file.
     */
    private void closeEventRecording() {
        // CRITICAL FIX: Do NOT set isWritingToFile=false yet!
        // The drainer thread checks isWritingToFile to decide whether to write
        // frames to the muxer. Setting it false first causes the drainer to
        // dequeue frames from the encoder but SKIP writing them — losing the
        // last segment's frames on shutdown.
        //
        // Correct order:
        //   1. Wait for any in-flight rotation finalizers (audit Finding R1).
        //   2. Stop drainer thread (waits for current drain cycle to finish)
        //   3. Do one final synchronous drain WITH isWritingToFile still true
        //   4. THEN set isWritingToFile=false and close the muxer
        //
        // Step 1 prevents a finalizer from racing this close path: a rapid
        // stop within ~150 ms of a rotation tick used to fire onFileSaved
        // for the previous segment AFTER the close path had already torn
        // down the active recording, leaving cleanup in the wrong state.
        // 2-second budget is generous; finalizer should complete in <500 ms.
        if (!waitForFinalizers(2_000)) {
            logger.warn("closeEventRecording proceeding with finalizers still in flight");
        }

        recording = false;
        
        // Step 1: Stop drainer thread BEFORE touching the muxer.
        // The drainer may be in the middle of muxer.writeSampleData() — 
        // calling muxer.stop() concurrently corrupts the MP4 (broken moov atom).
        stopDrainerThread();
        
        // Step 2: Final synchronous drain — flush any frames still queued in
        // the encoder's output buffer. isWritingToFile is still true so these
        // frames WILL be written to the muxer.
        // FIX: Drain in a loop until the encoder is truly empty. A single call
        // to drainEncoderInternal() may not get all frames if the encoder is still
        // processing the last few input buffers. Loop with a short sleep to give
        // the hardware encoder time to finish encoding in-flight frames.
        try {
            for (int drainPass = 0; drainPass < 5; drainPass++) {
                int framesBefore = recordedFrames;
                drainEncoderInternal();
                int framesWritten = recordedFrames - framesBefore;
                if (framesWritten == 0 && drainPass > 0) {
                    break;  // Encoder is empty
                }
                if (framesWritten > 0 && drainPass < 4) {
                    // More frames were available — give encoder a moment to finish any in-flight
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warn("Final drain before close failed: " + e.getMessage());
        }
        
        // Step 3 + 4: under muxerLock, flush remaining queued packets into the
        // still-live muxer, then stop+release. Tracking stopOk lets us refuse
        // to rename a file whose moov was never written — that file would be
        // sized, named .mp4, and unplayable.
        boolean stopOk = false;
        synchronized (muxerLock) {
            MuxerPacket packet;
            int flushed = 0;
            while ((packet = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    try {
                        packet.rewindForWrite();
                        muxer.writeSampleData(trackIndex, packet.data, packet.info);
                        if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                        lastFramePtsUs = packet.info.presentationTimeUs;
                        recordedFrames++;
                        flushed++;
                    } catch (Exception e) {
                        logger.warn("Final flush write error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        releaseMuxerPacket(packet);
                        break;
                    }
                }
                releaseMuxerPacket(packet);
            }
            if (flushed > 0) {
                logger.info("Final muxer queue flush: " + flushed + " frames written");
            }

            // No more writers can race us now — flag the writer state OFF before
            // touching muxer.stop(). isWritingToFile is also cleared under the
            // lock so the upcoming format-change handler can't reopen the muxer.
            isWritingToFile = false;

            // Stop muxer (may throw if no frames were written, or if the
            // underlying file descriptor was severed by an SD-card unmount).
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Muxer stop error (may have had no frames): " + e.getMessage());
            } finally {
                muxerStarted = false;
            }

            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception e) {
                logger.warn("Muxer release error: " + e.getMessage());
            } finally {
                muxer = null;
                trackIndex = -1;
            }
        }

        // SOTA: Restart the drainer NOW — before the synchronous rename /
        // onFileSaved / Telegram dispatch below. The encoder is still alive and
        // the GL thread is still calling eglSwapBuffers every frame; without a
        // live drainer, the encoder output queue fills, then the input queue
        // fills, then eglSwapBuffers blocks for the entire duration of the
        // post-stop housekeeping (observed: 76 ms = 255 ms mosaic+swap stage
        // spike on the GL thread).
        //
        // Safe ordering: muxer is fully stopped+released, isWritingToFile is
        // false under muxerLock, and writeSampleData paths gate on those, so
        // the freshly-started drainer can only feed the pre-record circular
        // buffer + streaming until the next event triggers a new muxer.
        startDrainerThread();

        // Rename temp to final, quarantine if broken, or delete if empty.
        // SOTA: never promote a tempFile to a final .mp4 unless the muxer
        // actually finalized — that's the single rule that prevents the
        // "60 MB file that won't play" symptom.
        boolean recordingBroken = !stopOk || writerAbortedCorrupt;
        if (tempFile != null && tempFile.exists()) {
            if (!recordingBroken && recordedFrames > 0 && tempFile.length() > 1024) {
                File finalFile = new File(outputPath);
                if (tempFile.renameTo(finalFile)) {
                    // Use actual PTS range for accurate duration (not recordedFrames/fps
                    // which is misleading when pre-record frames are included)
                    float durationSec = (firstFramePtsUs >= 0 && lastFramePtsUs > firstFramePtsUs)
                            ? (lastFramePtsUs - firstFramePtsUs) / 1_000_000.0f
                            : recordedFrames / (float) fps;
                    logger.info(String.format("Event saved: %s (segment %d, %d frames, %.1f sec, %d KB, codec=%s, bitrate=%d Mbps)",
                            finalFile.getName(), segmentNumber, recordedFrames, durationSec, finalFile.length() / 1024,
                            codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                            bitrate / 1_000_000));

                    // Make file visible to events page and UI app
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                    } catch (Exception e) {
                        logger.warn("onFileSaved error: " + e.getMessage());
                    }

                    try {
                        TelegramNotifier.notifyVideoRecorded(
                                finalFile.getAbsolutePath(), null, (int) durationSec);
                    } catch (Exception e) {
                        logger.warn("Failed to emit video notification: " + e.getMessage());
                    }
                } else {
                    logger.error("Failed to rename temp file — deleting orphan");
                    tempFile.delete();
                }
            } else if (recordingBroken) {
                // Quarantine: keep evidence under a sidecar extension so the
                // recordings UI's *.mp4 listing doesn't pick it up. An
                // operator can still find it on disk for diagnostics.
                File broken = new File(outputPath + ".broken");
                if (!tempFile.renameTo(broken)) {
                    logger.warn("Quarantine rename failed; deleting broken tmp: " + tempFile.getName());
                    tempFile.delete();
                } else {
                    logger.warn("Quarantined broken recording (stopOk=" + stopOk
                            + ", writerAborted=" + writerAbortedCorrupt
                            + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                }
            } else {
                // Empty / sub-1KB recording — drop it silently.
                logger.warn("Deleting empty/corrupt temp file: " + tempFile.getName() +
                        " (frames=" + recordedFrames + ", size=" + tempFile.length() + ")");
                tempFile.delete();
            }
        }
        
        // Reset state
        recordedFrames = 0;
        firstFramePtsUs = -1;
        lastFramePtsUs = -1;
        postRecordStopTime = 0;
        segmentStartTime = 0;
        segmentNumber = 0;
        segmentBasePath = null;

        // Drainer was already restarted above (right after muxer release) so
        // the GL thread saw zero post-stop backpressure. No-op call here would
        // log "Drainer thread already running" — just rely on the early start.

        if (fileClosedCallback != null) {
            fileClosedCallback.run();
        }
    }
    
    /**
     * Legacy method for compatibility.
     */
    public void stopRecording() {
        stopEventRecording(true, 0);
    }
    
    /**
     * Sets callback for when file is closed.
     * 
     * @param callback Callback to run when file closes
     */
    public void setFileClosedCallback(Runnable callback) {
        this.fileClosedCallback = callback;
    }
    
    /**
     * Requests a sync frame (I-frame) immediately.
     * 
     * Used when an event is detected to ensure clean playback start.
     */
    public void requestSyncFrame() {
        if (encoder != null) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(params);
                logger.debug( "Sync frame requested");
            } catch (Exception e) {
                logger.error( "Failed to request sync frame", e);
            }
        }
    }
    
    /**
     * Changes the encoder bitrate dynamically.
     * 
     * @param newBitrate New bitrate in bps
     */
    public void setBitrate(int newBitrate) {
        if (encoder != null && newBitrate != bitrate) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
                encoder.setParameters(params);
                
                this.bitrate = newBitrate;
                logger.info( "Bitrate changed to: " + (newBitrate / 1_000_000) + " Mbps");
            } catch (Exception e) {
                logger.error( "Failed to change bitrate", e);
            }
        }
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Checks if currently writing to file.
     * 
     * @return true if actively writing to file, false otherwise
     */
    public boolean isWritingToFile() {
        return isWritingToFile;
    }
    
    /**
     * Gets the number of recorded frames.
     * 
     * @return Frame count
     */
    public int getRecordedFrames() {
        return recordedFrames;
    }
    
    /**
     * Get the actual duration of the pre-record buffer that was flushed.
     * This may be longer than the configured preRecordMs because the H.264
     * circular buffer starts from the nearest keyframe.
     */
    public long getActualPreRecordDurationMs() {
        return actualPreRecordDurationMs;
    }
    
    /**
     * Gets the current bitrate.
     * 
     * @return Bitrate in bps
     */
    public int getBitrate() {
        return bitrate;
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        // SOTA: Stop drainer thread first
        stopDrainerThread();

        if (recording) {
            stopRecording();
        }

        // Wait for any in-flight rotation finalizers AFTER stopRecording so
        // the close path's rename has already finished but rotation finalizers
        // (which run on independent threads) are joined. Otherwise the
        // pipeline tear-down can outpace a rename + onFileSaved that's still
        // in flight, leaving the StorageManager probe with a dangling encoder
        // ref. 3-second budget covers worst-case stop+release+rename.
        if (!waitForFinalizers(3_000)) {
            logger.warn("release() proceeding with finalizers still in flight");
        }
        
        if (encoder != null) {
            // Note: by the time we reach release(), stopRecording() above
            // has already finalized any active recording (drained and stopped
            // the muxer). Final-frame-loss is handled there. We just stop
            // and release the codec.
            try {
                encoder.stop();
            } catch (Exception e) {
                logger.error( "Error stopping encoder", e);
            }

            try {
                encoder.release();
            } catch (Exception e) {
                logger.error( "Error releasing encoder", e);
            }

            encoder = null;
        }
        
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        // Clear the shared pre-record buffer if THIS instance owned it. The
        // buffer is static + shared across encoder reinits to avoid 23MB
        // realloc, but stale packets from a previous codec/resolution must
        // not survive into the next event recording. Without this, a crash
        // path that skips stopRecording() leaves packets in the buffer that
        // the next instance will flush into its first event MP4.
        if (preRecordBuffer != null) {
            synchronized (bufferLock) {
                if (preRecordBuffer == sharedPreRecordBuffer) {
                    sharedPreRecordBuffer.clear();
                }
            }
            preRecordBuffer = null;
        }

        logger.info( "Released");
    }
    
    // ==================== SOTA: Background Drainer Thread ====================
    
    /**
     * Starts the background drainer thread.
     * This moves SD card I/O off the GL thread to prevent freezes.
     */
    private void startDrainerThread() {
        if (drainerRunning) {
            logger.warn("Drainer thread already running");
            return;
        }
        
        drainerRunning = true;
        drainerThread = new Thread(() -> {
            // Audit-driven: drainer is on the realtime-critical path. If it
            // gets scheduled out for >50ms, the encoder's output pool fills
            // and eglSwap on the GL thread backpressures. Bump Linux nice
            // priority to match the disk writer (FOREGROUND, -2). Without
            // this, drainer ran at default nice 0 and could be preempted by
            // any other normal-priority work.
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_FOREGROUND);
                logger.debug("Drainer thread nice set to FOREGROUND");
            } catch (Throwable t) {
                logger.warn("Drainer thread priority bump failed: " + t.getMessage());
            }
            logger.info("Encoder drainer thread started");
            while (drainerRunning) {
                try {
                    // Drain the encoder (SD card I/O happens here, not on GL thread)
                    drainEncoderInternal();
                    // Don't burn CPU - wait a tiny bit for new frames
                    Thread.sleep(DRAIN_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Drainer error: " + e.getMessage());
                }
            }
            logger.info("Encoder drainer thread stopped");
        }, "GpuEncoderDrainer");

        drainerThread.setPriority(Thread.NORM_PRIORITY);
        drainerThread.start();
        
        // Start disk writer thread (handles muxer I/O separately from encoder dequeue)
        startDiskWriterThread();
    }
    
    /**
     * Stops the background drainer thread.
     */
    private void stopDrainerThread() {
        drainerRunning = false;
        if (drainerThread != null) {
            try {
                drainerThread.interrupt();
                // SOTA: 2 s join matches the disk writer's join. The drainer can
                // be inside a single drainEncoderInternal() pass that takes
                // 100+ ms under SD-card pressure; the old 500 ms ceiling let
                // the close path move on while the drainer was still pushing
                // packets to the queue, racing the muxer.stop() call.
                drainerThread.join(2000);
            } catch (InterruptedException e) {
                // Ignore
            }
            drainerThread = null;
        }

        // Stop disk writer after drainer (drainer may still be pushing to the queue)
        stopDiskWriterThread();
    }
    
    /**
     * FORTIFY FIX: Stops the drainer thread before camera close.
     * 
     * The drainer thread calls MediaCodec.dequeueOutputBuffer() which internally
     * accesses the camera's SurfaceTexture buffer queue. If the camera is closed
     * (destroying the native mutex) while the drainer is mid-dequeue, we get:
     *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
     * 
     * This method stops the drainer and waits for it to fully exit before returning,
     * making it safe to close the camera afterwards.
     * 
     * Call restartDrainerAfterCameraClose() after the camera is reopened.
     */
    public void stopDrainerForCameraClose() {
        logger.info("Stopping drainer for camera close...");
        drainerRunning = false;
        if (drainerThread != null) {
            try {
                drainerThread.interrupt();
                drainerThread.join(1000);  // Wait up to 1 second for clean exit
                if (drainerThread.isAlive()) {
                    logger.warn("Drainer thread still alive after 1s — proceeding anyway");
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            drainerThread = null;
        }
        logger.info("Drainer stopped for camera close");
    }
    
    /**
     * Restarts the drainer thread after camera has been reopened.
     * Call this after startCamera() succeeds.
     */
    public void restartDrainerAfterCameraClose() {
        if (!drainerRunning) {
            startDrainerThread();
            logger.info("Drainer restarted after camera reopen");
        }
    }
    
    // ==================== SOTA: Disk Writer Thread ====================
    
    /**
     * Starts the disk writer thread that polls the muxer write queue
     * and writes to the SD card. This decouples SD card I/O from the
     * encoder dequeue loop, preventing I/O stalls from dropping frames.
     */
    private void startDiskWriterThread() {
        if (diskWriterRunning) return;

        diskWriterRunning = true;
        // Each disk writer instance starts with a clean abort flag. The flag is
        // only set when this writer hits the unrecoverable failure threshold; the
        // close/rotate paths read it to decide whether to keep or quarantine the
        // current tempFile.
        writerAbortedCorrupt = false;
        // SD-unmount detection: if writes start failing repeatedly, the underlying
        // file descriptor is dead (typical when BYD/Android unmounts the SD card
        // mid-recording). The MP4's moov atom is written only on stopRecording, so
        // continuing to drain into a broken FD produces an unrecoverable corrupt
        // file. Track consecutive write failures and abort the recording cleanly
        // once we cross a threshold — at least the MP4 prefix on disk has the
        // partial frames already written, and the user gets a clear log instead
        // of a silent corruption.
        final int[] consecutiveWriteFailures = {0};
        final int writeFailureAbortThreshold = 5;
        diskWriterThread = new Thread(() -> {
            // Disk writer is on the realtime-critical path. If it falls
            // behind, the muxer write queue saturates and eglSwap stalls
            // the GL thread → freeze+skip in the encoded MP4.
            //
            // Audit P2: THREAD_PRIORITY_DISPLAY (-4) is @hide and SecurityException
            // for non-system apps on most Android builds. THREAD_PRIORITY_FOREGROUND
            // (-2) is the public, non-restricted equivalent that nudges the
            // scheduler in our favor without requiring system-app status. The
            // achieved gap vs. cleanup threads (background, +10) is still ~12
            // nice points, which is what actually matters.
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_FOREGROUND);
                logger.debug("Disk writer thread nice set to FOREGROUND");
            } catch (Throwable t) {
                logger.warn("Disk writer thread priority bump failed: " + t.getMessage()
                    + " (continuing at default)");
            }
            logger.info("Disk writer thread started");
            // SOTA: take(50ms) instead of poll()+sleep(4ms). The blocking take
            // wakes the writer the instant the drainer pushes a packet, so the
            // queue stays shallow and the encoder's input surface never fills.
            // The 50 ms timeout is just a periodic liveness check so the loop
            // can observe diskWriterRunning=false during teardown.
            while (diskWriterRunning || !muxerWriteQueue.isEmpty()) {
                MuxerPacket packet = null;
                try {
                    packet = muxerWriteQueue.pollFirst(50,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (packet == null) continue;
                    // SOTA: serialize against rotateSegment / closeEventRecording.
                    // Without this lock, a concurrent muxer.stop() corrupts the
                    // moov atom and produces a sized-but-unplayable .mp4.
                    synchronized (muxerLock) {
                        if (muxerStarted && muxer != null) {
                            packet.rewindForWrite();
                            muxer.writeSampleData(trackIndex, packet.data, packet.info);
                            if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                            lastFramePtsUs = packet.info.presentationTimeUs;
                            recordedFrames++;
                            consecutiveWriteFailures[0] = 0;
                        }
                    }
                    releaseMuxerPacket(packet);
                    packet = null;
                } catch (InterruptedException e) {
                    // Drain remaining packets before exiting. We deliberately do
                    // NOT write here: by the time the writer is interrupted, the
                    // close/rotate path is about to (or has already) called
                    // muxer.stop(), so any further writeSampleData would corrupt
                    // the moov. The close path drains the queue itself under the
                    // lock before stopping the muxer.
                    if (packet != null) releaseMuxerPacket(packet);
                    break;
                } catch (Exception e) {
                    if (packet != null) releaseMuxerPacket(packet);
                    consecutiveWriteFailures[0]++;
                    logger.error("Disk writer error (#" + consecutiveWriteFailures[0]
                        + "): " + e.getMessage());
                    if (consecutiveWriteFailures[0] >= writeFailureAbortThreshold) {
                        logger.error("Aborting recording: " + writeFailureAbortThreshold
                            + " consecutive write failures (likely SD card unmounted). "
                            + "Partial file at " + (tempFile != null ? tempFile.getAbsolutePath() : "unknown")
                            + " will not be playable.");
                        // Mark the current segment corrupt so the close/rotate
                        // path quarantines it rather than promoting tempFile to
                        // outputPath. The user must never see a final .mp4
                        // filename for a file whose moov was never written.
                        writerAbortedCorrupt = true;
                        // Drain queue and recycle so the writer loop exits
                        // promptly without leaking pooled buffers.
                        MuxerPacket drained;
                        while ((drained = muxerWriteQueue.poll()) != null) {
                            releaseMuxerPacket(drained);
                        }
                        diskWriterRunning = false;
                        // Don't call stopRecording() from here — that's a heavyweight
                        // operation that touches state owned by other threads. Just
                        // exit the writer; the main pipeline's existing watchdog or
                        // the next user action will trigger cleanup.
                        break;
                    }
                }
            }
            logger.info("Disk writer thread stopped");
        }, "GpuDiskWriter");
        
        // Java-level priority: NORM so the JVM thread scheduler doesn't deprioritize
        // the writer relative to other normal-priority threads. The Linux-level
        // nice value (set inside the Runnable above via Process.setThreadPriority)
        // is what actually controls I/O scheduling on Android — the Java priority
        // is mostly advisory.
        diskWriterThread.setPriority(Thread.NORM_PRIORITY);
        diskWriterThread.start();
    }
    
    /**
     * Stops the disk writer thread, flushing any remaining packets.
     */
    private void stopDiskWriterThread() {
        diskWriterRunning = false;
        if (diskWriterThread != null) {
            try {
                diskWriterThread.interrupt();
                diskWriterThread.join(2000);  // Allow up to 2s for final flush
            } catch (InterruptedException e) {
                // Ignore
            }
            diskWriterThread = null;
        }
    }
    
    /**
     * Public drainEncoder() - now just a no-op since draining happens on background thread.
     * Kept for API compatibility with existing code that calls it.
     */
    public void drainEncoder() {
        // SOTA: Draining now happens on background thread, not GL thread
        // This method is kept for API compatibility but does nothing
    }
    
    /**
     * Internal drain method called by background thread.
     * Handles all encoder output and SD card I/O.
     */
    private void drainEncoderInternal() {
        if (encoder == null) {
            return;
        }
        
        // SOTA: Process queued pre-record packets first (flush all at once).
        // These packets are written to the SD card via the muxer, which does NOT
        // block the encoder's input surface. The original chunking was added to prevent
        // MediaCodec backpressure, but that was only an issue when flushing on the GL
        // thread. Now that draining happens on a background thread, writing all packets
        // in one pass is safe and ensures no PTS gap between pre-record and live frames.
        //
        // CRITICAL: Live frames must NOT be written until this flush completes.
        // The pre-record packets have older PTS values. If live frames (with current PTS)
        // are interleaved, the muxer sees non-monotonic timestamps and the MP4 is corrupt.
        // Pre-record packets are drained in one pass into muxerWriteQueue.
        // The earlier "staggered flush" experiment (FLUSH_PACKETS_PER_TICK)
        // had a real bug: while flushInProgress=true for ~150ms, live frames
        // produced by the encoder hit the `!flushInProgress` gate below
        // (line ~1853) and were silently dropped — manifesting as recordings
        // freezing 5-6s in. Reverted. The single-pass drain is safe because
        // muxerWriteQueue.offerMuxerPacket() handles backpressure via its
        // drop-non-keyframe policy, AND the disk writer is now FOREGROUND
        // priority so it drains the burst quickly.
        if (flushInProgress && muxerStarted) {
            int flushedCount = 0;
            H264CircularBuffer.Packet queuedPacket;
            while ((queuedPacket = pendingFlushQueue.poll()) != null) {
                MuxerPacket mp = acquireMuxerPacket(queuedPacket.info.size);
                fillMuxerPacket(mp, queuedPacket.data, queuedPacket.info);
                offerMuxerPacket(mp);
                flushedCount++;
            }
            if (flushedCount > 0) {
                logger.info("Async flush complete: " + flushedCount + " pre-record frames queued for disk write");
            }
            flushInProgress = false;
        }
        
        // Check if segment rotation needed (only when actively writing to file).
        // SOTA: rotation requires a live disk writer + drainer; if either is
        // shutting down (e.g., we're inside the synchronous final drain in
        // closeEventRecording) the rotation logic would deadlock or produce a
        // stranded muxer. Skip in that case.
        // Don't rotate if the disk writer has aborted on SD-card death. The
        // hot-swap path would build a new muxer with no consumer (writer
        // already exited), and queued packets would pile up in
        // muxerWriteQueue forever. Stop the recording cleanly instead.
        // (Audit Finding R3.)
        if (writerAbortedCorrupt && isWritingToFile) {
            logger.warn("Writer aborted — stopping recording, no rotation");
            isWritingToFile = false;
            recording = false;
            return;
        }
        if (isWritingToFile && segmentStartTime > 0 && drainerRunning && diskWriterRunning
                && !writerAbortedCorrupt) {
            long elapsed = System.currentTimeMillis() - segmentStartTime;
            if (elapsed >= SEGMENT_DURATION_MS) {
                logger.info("Segment duration reached (" + (elapsed / 1000) + "s), rotating to new file...");
                rotateSegment();
            }
        }
        
        MediaCodec.BufferInfo bufferInfo = reusableBufferInfo;

        while (true) {
            int outputBufferIndex;
            try {
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            } catch (Exception e) {
                // Encoder may have been released
                break;
            }
            
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;  // No more output available
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed - add track to muxer and send SPS/PPS to stream
                MediaFormat format = encoder.getOutputFormat();
                
                // Save format for reuse in subsequent recordings
                if (savedFormat == null) {
                    savedFormat = format;
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    logger.info("Saved encoder format for reuse (codec=" +
                        (mime != null && mime.contains("hevc") ? "H.265" : "H.264") + ")");

                    // Notify any waiter (one-shot). This is the canonical
                    // moment isFormatAvailable() flips false→true.
                    FormatAvailableListener l = formatAvailableListener;
                    if (l != null) {
                        formatAvailableListener = null;
                        try { l.onFormatAvailable(); }
                        catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
                    }
                }
                
                if (recording && !muxerStarted) {
                    synchronized (muxerLock) {
                        if (muxer != null && !muxerStarted) {
                            trackIndex = muxer.addTrack(format);
                            muxer.start();
                            muxerStarted = true;
                            logger.info("Muxer started (track=" + trackIndex + ")");
                        }
                    }
                }
                
                // Send SPS/PPS to streaming callback
                if (streamCallback != null && !streamHeadersSent) {
                    try {
                        ByteBuffer sps = format.getByteBuffer("csd-0");
                        ByteBuffer pps = format.getByteBuffer("csd-1");
                        if (sps != null && pps != null) {
                            streamCallback.onSpsPps(sps.duplicate(), pps.duplicate());
                            streamHeadersSent = true;
                            logger.info("SPS/PPS sent to stream");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to send SPS/PPS", e);
                    }
                }
                
            } else if (outputBufferIndex >= 0) {
                // Got encoded data
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    // ALWAYS add to circular buffer (for pre-record) - unless stream-only mode
                    if (usePreRecordBuffer && preRecordBuffer != null) {
                        preRecordBuffer.add(outputBuffer, bufferInfo);
                    }
                    
                    // PATH A: Write to disk (if event recording active)
                    // SOTA: Don't write to muxer directly — push to the muxer write queue.
                    // The disk writer thread handles the actual SD card I/O, preventing
                    // I/O stalls from blocking the encoder dequeue loop. Pooled
                    // packet avoids per-frame ByteBuffer.allocateDirect on the
                    // drainer thread (5–50 ms native heap stalls observed during
                    // pre-record flush bursts).
                    if (isWritingToFile && muxerStarted && !flushInProgress) {
                        MuxerPacket mp = acquireMuxerPacket(bufferInfo.size);
                        fillMuxerPacket(mp, outputBuffer, bufferInfo);
                        offerMuxerPacket(mp);
                    }
                    
                    // PATH B: Send to network (if streaming)
                    if (streamCallback != null && streamHeadersSent) {
                        try {
                            // Duplicate buffer to avoid interfering with muxer
                            ByteBuffer streamBuffer = outputBuffer.duplicate();
                            streamBuffer.position(bufferInfo.offset);
                            streamBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            streamCallback.onH264Packet(streamBuffer, bufferInfo);
                        } catch (Exception e) {
                            logger.error("Stream callback error", e);
                        }
                    }
                }
                
                // Release output buffer
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    }
    
    /**
     * Rotates to a new segment file.
     * 
     * Closes current file and starts new segment WITHOUT flushing pre-record buffer
     * (since we're continuing the same event, not starting a new one).
     */
    private void rotateSegment() {
        if (!isWritingToFile) {
            return;
        }

        // SOTA RC6: hot-swap segment rotation.
        //
        // Old design: stop the disk writer, drain queue, muxer.stop() (slow,
        // 50-200ms), open new muxer, restart writer. Total lock hold ≥100ms
        // during which no packet writes flow → muxerWriteQueue grows →
        // encoder eglSwap stalls → freeze+skip in the encoded MP4.
        //
        // New design: keep the disk writer running across the rotation. We:
        //   1. Compute the new path / tempFile and pre-construct the new
        //      MediaMuxer + addTrack on the SAME thread (the drainer thread
        //      that calls rotateSegment) but BEFORE acquiring muxerLock. The
        //      MediaMuxer constructor opens an fd; addTrack is metadata-only.
        //      Both are O(1) and don't block on existing writes.
        //   2. Briefly take muxerLock. Drain remaining queue into OLD muxer.
        //      Stash old muxer reference into a local. Atomically replace
        //      this.muxer with the new muxer; call new.start() inside the
        //      lock so the writer's next iteration sees a started muxer.
        //   3. Release muxerLock. Disk writer resumes immediately on the
        //      new muxer.
        //   4. Hand the old muxer + identity to a background worker thread
        //      that runs stop+release+rename. The 50-200ms cost happens
        //      OFF the disk-writer's critical path.
        //
        // Lock window: ~5-30ms (drain only) instead of 100-300ms. The disk
        // writer never stops/restarts. Encoder backpressure window collapses.

        logger.info("Rotating segment " + segmentNumber + " - hot-swap to new file");

        // Capture old segment identity for the background finalizer.
        final File oldTemp = tempFile;
        final String oldOutputPath = outputPath;
        final int oldSegmentNumber = segmentNumber;

        // === Step 1: pre-construct the new muxer OFF the lock ===
        segmentNumber++;
        String newPath = nextSegmentPath(segmentBasePath);
        File newTempFile = new File(newPath + ".tmp");
        MediaMuxer newMuxer;
        int newTrackIndex = -1;
        try {
            newMuxer = new MediaMuxer(newTempFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (savedFormat != null) {
                newTrackIndex = newMuxer.addTrack(savedFormat);
            } else {
                logger.error("Cannot rotate: savedFormat is null (encoder hasn't published format)");
                newMuxer.release();
                segmentNumber--;  // back out the bump
                return;
            }
        } catch (Exception e) {
            logger.error("Failed to pre-construct new segment muxer: " + e.getMessage(), e);
            if (newTempFile.exists()) newTempFile.delete();
            segmentNumber--;  // back out the bump
            return;
        }

        // === Step 2: brief lock window — drain, swap, start ===
        final MediaMuxer oldMuxer;
        final int oldTrackIndex;
        final int oldRecordedFrames;
        final long oldFirstPtsUs;
        final long oldLastPtsUs;
        boolean rotationOk = false;
        synchronized (muxerLock) {
            // Drain remaining queue into the OLD muxer. These packets have
            // PTS values that belong to the old segment; writing them to the
            // new muxer would break PTS monotonicity.
            MuxerPacket pkt;
            int drained = 0;
            while ((pkt = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    try {
                        pkt.rewindForWrite();
                        muxer.writeSampleData(trackIndex, pkt.data, pkt.info);
                        if (firstFramePtsUs < 0) firstFramePtsUs = pkt.info.presentationTimeUs;
                        lastFramePtsUs = pkt.info.presentationTimeUs;
                        recordedFrames++;
                        drained++;
                    } catch (Exception e) {
                        logger.warn("Rotation drain error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        releaseMuxerPacket(pkt);
                        break;
                    }
                }
                releaseMuxerPacket(pkt);
            }
            if (drained > 0) {
                logger.debug("Rotation drained " + drained + " queued frames into old segment");
            }

            // Stash the old muxer + its stats so the background finalizer
            // owns them. Anything we update on `this.*` from here on belongs
            // to the new segment.
            oldMuxer = muxer;
            oldTrackIndex = trackIndex;
            oldRecordedFrames = recordedFrames;
            oldFirstPtsUs = firstFramePtsUs;
            oldLastPtsUs = lastFramePtsUs;

            // Hot-swap: this.muxer now points at the new muxer. The disk
            // writer's next iteration will write to it.
            try {
                newMuxer.start();
                muxer = newMuxer;
                trackIndex = newTrackIndex;
                muxerStarted = true;
                tempFile = newTempFile;
                outputPath = newPath;
                // Reset per-segment counters AFTER capturing oldFirstPtsUs etc.
                // for the finalizer.
                recordedFrames = 0;
                firstFramePtsUs = -1;
                lastFramePtsUs = -1;
                segmentStartTime = System.currentTimeMillis();
                rotationOk = true;
            } catch (Exception e) {
                logger.error("Failed to start new segment muxer: " + e.getMessage(), e);
                // Restore the old muxer as the active one so the writer keeps
                // writing to a still-valid target. The old segment continues
                // until the next rotation attempt.
                try { newMuxer.release(); } catch (Exception ignored) {}
                if (newTempFile.exists()) newTempFile.delete();
                segmentNumber--;  // back out the bump
            }
        }

        if (!rotationOk) {
            return;
        }

        // === Step 3: writer is already on the new muxer. Request keyframe. ===
        // Without this, the new segment would start with P-frames referencing
        // an I-frame that lives in the old (now-stopped) file. Players would
        // render garbage until the next ~2-second I-frame interval.
        requestSyncFrame();
        logger.info("Segment " + segmentNumber + " started: " + newTempFile.getName());

        // === Step 4: hand the old muxer to a background finalizer ===
        // stop() takes 50-200ms (writes the moov atom). rename() is fast but
        // blocks on metadata. Both happen off the realtime-critical path.
        //
        // Snapshot writerAbortedCorrupt at rotation time. The live volatile
        // is shared across all in-flight finalizers; without snapshotting,
        // a transient SD-card hiccup that flips the flag during finalizer N's
        // stop() would also poison finalizer N+1's perfectly-fine segment
        // because both check the same live flag (audit Finding R2).
        finalizeOldSegmentAsync(oldMuxer, oldTemp, oldOutputPath,
                oldSegmentNumber, oldRecordedFrames, oldFirstPtsUs, oldLastPtsUs,
                writerAbortedCorrupt, new File(newPath));
    }

    /**
     * Background worker that runs muxer.stop() + release() + rename for the
     * old segment after the hot-swap. Idempotent rename failure handling and
     * SegmentListener notification mirror the synchronous path's logic.
     *
     * Exceptions inside this lambda are logged and swallowed — the rotation
     * has already succeeded for the new segment by the time we get here, so
     * a finalizer failure shouldn't crash the process.
     */
    private void finalizeOldSegmentAsync(final MediaMuxer oldMuxer,
                                         final File oldTemp, final String oldOutputPath,
                                         final int oldSegmentNumber, final int oldRecordedFrames,
                                         final long oldFirstPtsUs, final long oldLastPtsUs,
                                         final boolean wasAbortedAtRotation,
                                         final File newSegmentFile) {
        // Increment BEFORE constructing the thread so a close() that arrives
        // immediately after this method returns sees the in-flight count.
        inFlightFinalizers.incrementAndGet();
        Thread t = new Thread(() -> {
          try {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}

            boolean stopOk = false;
            try {
                if (oldMuxer != null) {
                    oldMuxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Old-segment muxer.stop error: " + e.getMessage());
            }
            try {
                if (oldMuxer != null) oldMuxer.release();
            } catch (Exception e) {
                logger.warn("Old-segment muxer.release error: " + e.getMessage());
            }

            // Use the snapshot taken at rotation time, NOT the live volatile.
            // Otherwise a transient SD-card hiccup that flips the live flag
            // during this finalizer's stop() would also poison the next
            // finalizer's perfectly-fine segment. (Audit Finding R2.)
            boolean segmentBroken = !stopOk || wasAbortedAtRotation;
            File finalisedSegment = null;
            if (oldTemp != null && oldTemp.exists()) {
                if (!segmentBroken && oldRecordedFrames > 0 && oldTemp.length() > 1024) {
                    File finalFile = new File(oldOutputPath);
                    if (oldTemp.renameTo(finalFile)) {
                        finalisedSegment = finalFile;
                        float durationSec = (oldFirstPtsUs >= 0 && oldLastPtsUs > oldFirstPtsUs)
                                ? (oldLastPtsUs - oldFirstPtsUs) / 1_000_000.0f
                                : oldRecordedFrames / (float) fps;
                        logger.info(String.format("Segment %d saved: %s (%d frames, %.1f sec, %d KB)",
                                oldSegmentNumber, finalFile.getName(), oldRecordedFrames,
                                durationSec, finalFile.length() / 1024));
                        try {
                            com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                        } catch (Exception e) {
                            logger.warn("onFileSaved error: " + e.getMessage());
                        }
                    } else {
                        logger.error("Failed to rename segment " + oldSegmentNumber + " — deleting orphan");
                        oldTemp.delete();
                    }
                } else if (segmentBroken) {
                    File broken = new File(oldOutputPath + ".broken");
                    if (!oldTemp.renameTo(broken)) {
                        logger.warn("Quarantine rename failed; deleting broken tmp: " + oldTemp.getName());
                        oldTemp.delete();
                    } else {
                        logger.warn("Quarantined broken segment " + oldSegmentNumber
                                + " (stopOk=" + stopOk + ", writerAborted=" + wasAbortedAtRotation
                                + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                    }
                } else {
                    logger.warn("Deleting empty segment " + oldSegmentNumber + " tmp file");
                    oldTemp.delete();
                }
            }

            // Notify the engine after the rename so consumers can read the
            // finalised file. Same listener as the synchronous path.
            SegmentListener listener = segmentListener;
            if (listener != null) {
                try {
                    listener.onSegmentClosed(finalisedSegment, newSegmentFile);
                } catch (Throwable th) {
                    logger.warn("SegmentListener error: " + th.getMessage());
                }
            }
          } finally {
            // Decrement and notify any close()/release() waiter. Must be in
            // finally so an exception inside the body still releases the
            // join. Without this, a buggy SegmentListener could lock the
            // pipeline shutdown forever.
            int remaining = inFlightFinalizers.decrementAndGet();
            if (remaining == 0) {
                synchronized (finalizerJoinLock) {
                    finalizerJoinLock.notifyAll();
                }
            }
          }
        }, "GpuSegmentFinalizer-" + oldSegmentNumber);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * Wait for any in-flight segment finalizers to complete. Bounded by
     * timeoutMs (returns false on timeout — caller must decide whether to
     * proceed anyway). Called from closeEventRecording and release() so a
     * rapid stop+restart can't race a still-running rename + onFileSaved.
     */
    private boolean waitForFinalizers(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (finalizerJoinLock) {
            while (inFlightFinalizers.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    logger.warn("waitForFinalizers timed out with "
                        + inFlightFinalizers.get() + " still in flight");
                    return false;
                }
                try {
                    finalizerJoinLock.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Flushes and closes muxer immediately.
     * 
     * Used when ACC state changes during recording to ensure
     * file is properly closed before shutdown.
     */
    public void flushAndClose() {
        if (recording) {
            logger.info( "Flushing and closing muxer (ACC state change)");
            stopRecording();
        }
    }
    
    // Track the current output file path for cleanup protection
    private static volatile String currentlyWritingPath = null;
    
    /**
     * Gets the path of the file currently being written to.
     * Used by cleanup to avoid deleting active files.
     */
    public String getCurrentOutputPath() {
        return outputPath;
    }

    /**
     * Build the path for the next rotated segment.
     *
     * <p>Input is the base path of the original recording (no extension), e.g.
     * {@code /sdcard/.../cam_20260513_140523}. The original filename is
     * {@code <prefix>_yyyyMMdd_HHmmss}; we drop the trailing timestamp and
     * append a fresh one so each segment is a self-describing
     * {@code <prefix>_yyyyMMdd_HHmmss.mp4}, never {@code _1}, {@code _2}, etc.
     *
     * <p>If the basename can't be parsed (unexpected format), falls back to
     * the legacy {@code <base>_<n>.mp4} naming so a single bad recording
     * doesn't lose its rotation.
     */
    private String nextSegmentPath(String basePath) {
        String fresh = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        try {
            int slash = basePath.lastIndexOf('/');
            String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "";
            String name = slash >= 0 ? basePath.substring(slash + 1) : basePath;
            // Strip the original _yyyyMMdd_HHmmss suffix (last two underscore
            // segments — date and time). Anything else is the prefix.
            int lastUnderscore = name.lastIndexOf('_');
            if (lastUnderscore > 0) {
                int prevUnderscore = name.lastIndexOf('_', lastUnderscore - 1);
                if (prevUnderscore > 0) {
                    String prefix = name.substring(0, prevUnderscore);
                    String candidate = dir + prefix + "_" + fresh + ".mp4";
                    // Same-second rotation (or pre-existing file) — disambiguate
                    // with a short suffix rather than overwriting.
                    if (new java.io.File(candidate).exists()
                            || candidate.equals(outputPath)) {
                        // Underscore (not dash) so the UI regexes in
                        // RecordingsApiHandler — CAM_PATTERN / EVENT_PATTERN /
                        // PROXIMITY_PATTERN, all `(?:_\d+)?` — accept the
                        // disambiguated filename. A dash made the segment
                        // invisible to the web UI, calendar, and storage stats.
                        candidate = dir + prefix + "_" + fresh + "_" + segmentNumber + ".mp4";
                    }
                    return candidate;
                }
            }
        } catch (Exception ignored) {}
        return basePath + "_" + segmentNumber + ".mp4";
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes) {
        cleanupOldSegments(directory, maxSizeBytes, null);
    }

    /**
     * Clean up orphaned .tmp files that were left behind by crashed recordings,
     * and reap *.broken quarantine sidecars produced by the close/rotate paths
     * when a muxer.stop() failed or the disk writer aborted. Files older than
     * 5 minutes are removed.
     */
    public static void cleanupOrphanedTmpFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) return;

        File[] orphans = directory.listFiles((dir, name) ->
                name.endsWith(".tmp") || name.endsWith(".broken"));
        if (orphans == null) return;

        long now = System.currentTimeMillis();
        for (File f : orphans) {
            long age = now - f.lastModified();
            if (age > 5 * 60 * 1000) { // Older than 5 minutes
                long size = f.length();
                if (f.delete()) {
                    logger.info("Cleaned orphan: " + f.getName() + " (" + (size / 1024)
                            + " KB, age=" + (age / 1000) + "s)");
                }
            }
        }
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     * @param activeRecorder Optional recorder to check for active file (null = no protection)
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes, HardwareEventRecorderGpu activeRecorder) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            return;
        }
        
        // Get the currently active file path (if any)
        String activeFilePath = null;
        String activeTempPath = null;
        if (activeRecorder != null && activeRecorder.isWritingToFile()) {
            activeFilePath = activeRecorder.outputPath;
            if (activeRecorder.tempFile != null) {
                activeTempPath = activeRecorder.tempFile.getAbsolutePath();
            }
        }
        
        // Calculate total size (excluding active files)
        long totalSize = 0;
        for (File file : files) {
            // Skip files currently being written
            String filePath = file.getAbsolutePath();
            if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                logger.debug("Skipping active file in size calculation: " + file.getName());
                continue;
            }
            // Skip temp files (*.tmp) - they're being written
            if (file.getName().endsWith(".tmp")) {
                logger.debug("Skipping temp file in size calculation: " + file.getName());
                continue;
            }
            totalSize += file.length();
        }
        
        // Delete oldest files if over limit
        if (totalSize > maxSizeBytes) {
            // Sort by last modified (oldest first)
            java.util.Arrays.sort(files, (f1, f2) -> 
                Long.compare(f1.lastModified(), f2.lastModified()));
            
            for (File file : files) {
                if (totalSize <= maxSizeBytes) {
                    break;
                }
                
                String filePath = file.getAbsolutePath();
                
                // CRITICAL: Never delete the file currently being written
                if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                    logger.warn("Skipping deletion of active file: " + file.getName());
                    continue;
                }
                
                // Skip temp files - they're being written
                if (file.getName().endsWith(".tmp")) {
                    logger.warn("Skipping deletion of temp file: " + file.getName());
                    continue;
                }
                
                // Skip very recent files (less than 5 seconds old) - may still be finalizing
                long fileAge = System.currentTimeMillis() - file.lastModified();
                if (fileAge < 5000) {
                    logger.warn("Skipping deletion of recent file (age=" + fileAge + "ms): " + file.getName());
                    continue;
                }
                
                long fileSize = file.length();
                if (file.delete()) {
                    totalSize -= fileSize;
                    long sidecarBytes = deleteSegmentSidecars(file);
                    logger.info("Deleted old segment: " + file.getName() +
                            " (" + (fileSize / 1024) + " KB"
                            + (sidecarBytes > 0 ? ", +" + (sidecarBytes / 1024) + " KB sidecars" : "")
                            + ")");
                } else {
                    logger.warn("Failed to delete file: " + file.getName());
                }
            }
        }
    }

    /**
     * Removes the sidecar files that accompany an .mp4 segment: JSON event
     * timeline, v3 hero JPEG, and per-actor thumbnails {@code thumb_<base>_a*.jpg}.
     *
     * Without this, the loop-rotation deletion only frees the .mp4's bytes —
     * sidecars accumulate as orphans because future passes continue to skip
     * non-.mp4 files. Returns the freed bytes.
     */
    private static long deleteSegmentSidecars(File mp4File) {
        // Drop the API-handler cache entry for this segment so /api/recordings
        // doesn't keep returning a phantom row for a file that's been rotated.
        try {
            com.overdrive.app.server.RecordingsApiHandler.invalidateRecordingCache(
                    mp4File.getAbsolutePath());
        } catch (Throwable ignored) {}

        File parent = mp4File.getParentFile();
        if (parent == null) return 0L;
        String mp4Name = mp4File.getName();
        if (!mp4Name.endsWith(".mp4")) return 0L;
        String base = mp4Name.substring(0, mp4Name.length() - 4);
        long freed = 0L;

        File jsonFile = new File(parent, base + ".json");
        if (jsonFile.exists()) {
            long s = jsonFile.length();
            if (jsonFile.delete()) freed += s;
        }
        File heroFile = new File(parent, base + ".jpg");
        if (heroFile.exists()) {
            long s = heroFile.length();
            if (heroFile.delete()) freed += s;
        }
        // Anchor with "_a" so sibling segment thumbs (e.g. <base>_2's actor
        // thumbs at "thumb_<base>_2_a*.jpg") aren't swept when this segment
        // is rotated out. ThumbnailBuffer always writes "thumb_<base>_a<id>...".
        final String perActorPrefix = "thumb_" + base + "_a";
        File[] perActor = parent.listFiles((d, name) ->
                name.startsWith(perActorPrefix) && name.endsWith(".jpg"));
        if (perActor != null) {
            for (File f : perActor) {
                long s = f.length();
                if (f.delete()) freed += s;
            }
        }
        return freed;
    }
}
