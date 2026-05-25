package com.overdrive.app.ai

import android.content.Context
import com.overdrive.app.logging.DaemonLogger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO Detection Result
 */
data class Detection(
    val classId: Int,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * YOLO11n TensorFlow Lite Detector — CPU-only (XNNPACK).
 *
 * **Why CPU and not GPU on this hardware.** The Snapdragon 662 / Adreno 610
 * is a unified-memory SoC: the H.265 hardware encoder, the Adreno GPU's
 * compute units, and the CPU all share one DDR4 memory controller. When
 * YOLO ran via the TFLite GPU delegate concurrently with the surveillance
 * recording pipeline, OpenCL kernels saturated Adreno's compute units AND
 * the memory bus simultaneously. The encoder's per-frame input fetch + ref
 * frame access lost bandwidth, which manifested as 200–300 ms eglSwapBuffers
 * stalls on the encoder GL thread — visible in recordings as freeze+skip
 * during event windows where YOLO was busy.
 *
 * Tier 1+2 (separate AI-lane GL thread, PBO async readback) eliminated
 * GL-pipeline contention but did NOT touch the underlying memory-bandwidth
 * contention because YOLO inference itself was still GPU-bound. The only
 * physical bypass is to move inference off the GPU entirely.
 *
 * **Why XNNPACK 4-thread.** TFLite's CPU backend ships XNNPACK by default
 * (since 2.5). On ARM it dispatches to NEON SIMD kernels, and at 4 threads
 * the inference cost on this hardware is ~200–300 ms vs ~50–80 ms via GPU
 * delegate. That is well within {@code AI_COOLDOWN_MS = 500 ms}, so the
 * trigger pathway sees no regression. The 150–200 ms additional latency is
 * invisible to the user-facing trigger contract.
 *
 * **Thread isolation strategy (Android 10/11/12 portable).** The
 * {@code aiExecutor} thread that calls into this detector runs at
 * {@code Process.THREAD_PRIORITY_BACKGROUND} (nice +10). XNNPACK's worker
 * pthreads inherit this nice value at spawn time. The encoder/drainer
 * threads run at {@code THREAD_PRIORITY_FOREGROUND} (nice -2), giving a
 * 12-point CFS priority gradient — the encoder side wins scheduler
 * contention by ~10× weight regardless of which cores either thread lands
 * on. On Android 10 the priority demotion ALSO confines these threads to
 * the {@code background} cpuset (cores 0-3, A53 silver cluster); on
 * Android 11+ EAS scheduling can migrate them under load, but the CFS
 * gradient alone is what's portable and what actually keeps the encoder
 * fed. The aiExecutor ALSO re-applies {@code THREAD_PRIORITY_BACKGROUND}
 * at task entry as a defense against EAS migration that may otherwise
 * reset the thread's priority class on long-lived executors.
 *
 * **Why not NNAPI.** Field-tested on this hardware: ~538 of ~546 ops fall
 * through to XNNPACK on CPU anyway (the NNAPI driver only accelerates a
 * handful of ops). Effective inference time ≈ pure CPU mode minus a small
 * dispatch overhead — no benefit, more code surface, more failure modes.
 *
 * SOTA Implementation properties retained from prior version:
 * - Native C++ ImageProcessor (SIMD-accelerated bilinear resize + normalize)
 * - Pre-allocated buffers (zero GC churn)
 * - Cache-friendly output parsing
 * - Height filter before NMS
 * - Ghost filter (max 50 detections)
 */
class YoloDetector(private val context: Context) {

    private val logger = DaemonLogger.getInstance("YoloDetector")

    private var interpreter: Interpreter? = null

    // Monitor that mutually excludes inference (interp.run) from
    // close() / re-init. Without it, a UI/IPC-thread close() can free
    // the native TFLite interpreter while aiExecutor is mid-detect,
    // causing a SIGSEGV in tensorflowlite_jni. The Java-side null
    // snapshot in the engine guards null-deref but not use-after-free
    // inside the native run.
    private val interpLock = Any()

    // SOTA: Pre-allocate all buffers to avoid GC
    private var inputImageBuffer: TensorImage? = null
    private var outputBuffer: ByteBuffer? = null

    // Reusable shaped input buffer. Re-create only when image dimensions
    // change (rare — quadrant size is fixed at startup). Without this,
    // every detect() allocated a fresh TensorBuffer + ByteBuffer.wrap →
    // ~1 MB short-lived garbage per inference, contradicting the class's
    // "zero GC churn" promise. Class-field allocation + dim guard runs
    // O(1) when dims match.
    private var shapedBufferW: Int = -1
    private var shapedBufferH: Int = -1
    private var shapedBuffer: TensorBuffer? = null
    private var floatOutput: FloatArray? = null

    // Model configuration
    private val modelPath = "models/yolo11n.tflite"
    private val inputSize = 640

    // INT8 / FP32 model auto-detection. The Android side stays compatible
    // with both yolo11n.tflite variants (FP32 default, INT8 produced by
    // dev/quantize_yolo_int8.py) — init() inspects the loaded interpreter
    // and routes preprocessing accordingly. There is no per-detect()
    // overhead from this; the routing decision is cached.
    //
    // FP32 path: ImageProcessor does Resize + Normalize(0..1); output is
    //   already float, no dequant needed.
    // INT8 path: ImageProcessor does Resize only (the int8 input tensor's
    //   embedded scale/zero_point handles the [0,255] -> int8 mapping
    //   inside the interpreter); output is int8 and must be dequantized
    //   to float via (raw - zeroPoint) * scale before parseOutput.
    //
    // outputIsQuantized governs the output post-processing path. For
    // YOLOv11n int8 export the Ultralytics pipeline emits a single output
    // tensor with shape [1, 84, 8400] of dtype UINT8 with non-trivial
    // (scale, zero_point) — same shape as FP32 so parseOutput's iteration
    // is unchanged after dequant.
    private var inputIsQuantized = false
    private var outputIsQuantized = false
    private var outputScale = 0f
    private var outputZeroPoint = 0
    private var int8OutputBuffer: ByteArray? = null  // raw output for int8 path

    // SOTA: Native C++ image processor (SIMD-accelerated bilinear resize
    // + optional normalize). Built lazily in init() once we know the
    // input tensor dtype.
    private var imageProcessor: ImageProcessor? = null
    
    // COCO class IDs
    companion object {
        const val CLASS_PERSON = 0
        const val CLASS_BICYCLE = 1
        const val CLASS_CAR = 2
        const val CLASS_MOTORCYCLE = 3
        const val CLASS_AIRPLANE = 4
        const val CLASS_BUS = 5
        const val CLASS_TRAIN = 6
        const val CLASS_TRUCK = 7
        const val CLASS_BOAT = 8
        const val CLASS_BIRD = 14
        const val CLASS_CAT = 15
        const val CLASS_DOG = 16
        const val CLASS_HORSE = 17
        const val CLASS_SHEEP = 18
        const val CLASS_COW = 19
        const val CLASS_ELEPHANT = 20
        const val CLASS_BEAR = 21
        const val CLASS_ZEBRA = 22
        const val CLASS_GIRAFFE = 23
    }
    
    /**
     * Initialize the detector. CPU-only (XNNPACK 4-thread). See class
     * doc for the rationale on why GPU/NNAPI tiers were removed.
     */
    fun init(): Boolean {
        try {
            // Load TFLite's CPU JNI library explicitly — daemon-mode
            // processes don't always run JVM-side static linking
            // automatically. tensorflowlite_gpu_jni is intentionally not
            // loaded; the GPU delegate is no longer a dependency.
            try {
                System.loadLibrary("tensorflowlite_jni")
                logger.info("TFLite native library loaded (CPU-only)")
            } catch (e: UnsatisfiedLinkError) {
                logger.error("Failed to load TFLite native library: ${e.message}")
                return false
            }

            val modelFile = FileUtil.loadMappedFile(context, modelPath)

            // CPU XNNPACK, 4 threads. Worker pthreads inherit nice +10
            // from the calling aiExecutor thread; the 12-point CFS gradient
            // versus the encoder/drainer (nice -2) keeps the encoder fed
            // even when YOLO threads happen to land on the same physical
            // core.
            try {
                val cpuOptions = Interpreter.Options()
                cpuOptions.setNumThreads(4)
                interpreter = Interpreter(modelFile, cpuOptions)
                interpreter!!.allocateTensors()
            } catch (e: Exception) {
                logger.error("Failed to initialize TFLite CPU interpreter: ${e.message}", e)
                return false
            }

            // Auto-detect FP32 vs INT8 model. Probe input tensor 0 + output
            // tensor 0 dtype. yolo11n's standard export uses FLOAT32; the
            // dev/quantize_yolo_int8.py script produces a UINT8/UINT8 variant.
            val interp = interpreter!!
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            val inputDtype = inputTensor.dataType()
            val outputDtype = outputTensor.dataType()
            inputIsQuantized = (inputDtype == DataType.UINT8 || inputDtype == DataType.INT8)
            outputIsQuantized = (outputDtype == DataType.UINT8 || outputDtype == DataType.INT8)
            if (outputIsQuantized) {
                val q = outputTensor.quantizationParams()
                outputScale = q.scale
                outputZeroPoint = q.zeroPoint
            }

            // Build the preprocessing pipeline that matches the model's
            // expected input dtype:
            //   - FP32 model: resize + normalize (0..255 -> 0.0..1.0)
            //   - INT8 model: resize only; the interpreter's embedded
            //     input quantization params handle the uint8 -> int8 mapping
            //     internally with no host-side normalize step.
            inputImageBuffer = TensorImage(DataType.UINT8)
            imageProcessor = if (inputIsQuantized) {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .build()
            } else {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            }

            // Pre-allocate output buffer sized to the actual tensor dtype.
            // FP32: 4 bytes/element. INT8/UINT8: 1 byte/element.
            val outputElements = 84 * 8400
            val outputBytes = outputElements * if (outputIsQuantized) 1 else 4
            outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
            if (outputIsQuantized) {
                int8OutputBuffer = ByteArray(outputElements)
            }

            val mode = if (inputIsQuantized && outputIsQuantized) "INT8"
                       else if (!inputIsQuantized && !outputIsQuantized) "FP32"
                       else "MIXED($inputDtype/$outputDtype)"
            logger.info("CPU XNNPACK initialized (4 threads, $mode model, " +
                    (if (outputIsQuantized) "outScale=$outputScale outZp=$outputZeroPoint, " else "") +
                    "encoder-isolated via nice gradient)")

            logger.info("Model loaded successfully ($mode)")
            return true
        } catch (e: Exception) {
            logger.error("Failed to load model: ${e.message}", e)
            return false
        }
    }
    
    /**
     * SOTA Detection with native C++ preprocessing
     * 
     * @param rgbData RGB888 byte array (vertically flipped for OpenGL)
     * @param width Image width
     * @param height Image height
     * @param confThreshold Confidence threshold
     * @param detectPerson Detect person class
     * @param detectCar Detect vehicle classes
     * @param detectAnimal Detect animal classes
     * @param detectBike Detect bicycle/motorcycle
     * @param minRelativeHeight Minimum object height relative to QUADRANT (SOTA: 15% rule)
     *                          This is applied per-quadrant in 2x2 mosaic grid
     */
    fun detect(
        rgbData: ByteArray,
        width: Int,
        height: Int,
        confThreshold: Float = 0.25f,
        detectPerson: Boolean = true,
        detectCar: Boolean = true,
        detectAnimal: Boolean = false,
        detectBike: Boolean = false,
        minRelativeHeight: Float = 0.15f  // SOTA: 15% of QUADRANT height (~5m for person)
    ): List<Detection> {

        // FIX (Bug B): if the caller has disabled every detectable class, skip the
        // entire inference path. This is the belt-and-braces defence behind the
        // engine's aiEnabled gate and ensures any future caller path benefits too.
        if (!detectPerson && !detectCar && !detectAnimal && !detectBike) {
            return emptyList()
        }

        if (width <= 0 || height <= 0) return emptyList()

        // CRITICAL: Color channel handling
        // GpuDownscaler outputs RGB from OpenGL (RGBA_8888 with A dropped)
        // The data is already in RGB format - NO SWAP NEEDED
        // Image is now correctly oriented (vertical flip applied in GpuDownscaler)
        val processedData = rgbData  // Use directly - already RGB from GpuDownscaler

        // Synchronize against close(). Inside the lock we're guaranteed the
        // native interpreter is alive for the duration of run(). Lock cost
        // on the single-thread aiExecutor is uncontended steady-state; the
        // brief contention with close() is fine — close happens rarely
        // (toggle off, daemon shutdown).
        val output: FloatArray
        synchronized(interpLock) {
            val interp = interpreter ?: return emptyList()

            // Reuse the shaped TensorBuffer across calls. Re-allocate only on
            // dimension change (rare). Same for the float output array.
            var sb = shapedBuffer
            if (sb == null || shapedBufferW != width || shapedBufferH != height) {
                sb = TensorBuffer.createFixedSize(intArrayOf(height, width, 3), DataType.UINT8)
                shapedBuffer = sb
                shapedBufferW = width
                shapedBufferH = height
            }
            sb.loadBuffer(ByteBuffer.wrap(processedData))

            inputImageBuffer!!.load(sb)

            // SOTA: Process with native C++ ops. Pipeline differs by model
            // dtype: FP32 path normalizes 0..255 -> 0.0..1.0; INT8 path is
            // resize-only and the interpreter's input quantization handles
            // the uint8 mapping internally.
            val tensorImage = imageProcessor!!.process(inputImageBuffer)

            // Run inference (CPU XNNPACK). interp.run() blocks until the
            // last layer is computed; there's no async/queue model on the
            // CPU backend (unlike the previous GPU delegate).
            outputBuffer!!.rewind()
            interp.run(tensorImage.buffer, outputBuffer)
            outputBuffer!!.rewind()

            var fo = floatOutput
            if (fo == null || fo.size != 84 * 8400) {
                fo = FloatArray(84 * 8400)
                floatOutput = fo
            }

            if (outputIsQuantized) {
                // INT8 output path: bulk-copy the byte tensor to a Java
                // byte[] in one JNI hop, then dequantize to float in
                // Java loop. Dequant: f = (raw - zeroPoint) * scale.
                // Cost: 84*8400 = 705,600 multiplications (~3-5 ms on
                // Cortex-A53), still much cheaper than the FP32 model's
                // larger XNNPACK kernel set inside interp.run().
                val raw = int8OutputBuffer!!
                outputBuffer!!.get(raw, 0, raw.size)
                // For UINT8 outputs, raw value is in [0, 255]; for INT8,
                // ByteBuffer.get returns signed [-128, 127] which is
                // already the correct interpretation. The interpreter's
                // quantization params encode which dtype was used.
                val scale = outputScale
                val zp = outputZeroPoint
                val outDtype = interp.getOutputTensor(0).dataType()
                if (outDtype == DataType.UINT8) {
                    var i = 0
                    while (i < raw.size) {
                        // Unsigned read: raw[i] is a Java signed byte; mask
                        // with 0xFF to get the [0, 255] value the model
                        // produced.
                        fo[i] = ((raw[i].toInt() and 0xFF) - zp) * scale
                        i++
                    }
                } else {
                    var i = 0
                    while (i < raw.size) {
                        fo[i] = (raw[i].toInt() - zp) * scale
                        i++
                    }
                }
            } else {
                // FP32 output path: bulk-copy from direct ByteBuffer to the
                // Java float[] in one JNI call.
                outputBuffer!!.asFloatBuffer().get(fo)
            }
            output = fo
        }

        return parseOutput(
            output, width, height, confThreshold,
            detectPerson, detectCar, detectAnimal, detectBike, minRelativeHeight
        )
    }
    
    /**
     * SOTA: Cache-friendly output parsing
     * 
     * Optimized memory access pattern to minimize cache misses.
     * Processes output in channel-major order to keep memory accesses sequential.
     */
    private fun parseOutput(
        output: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        confThreshold: Float,
        detectPerson: Boolean,
        detectCar: Boolean,
        detectAnimal: Boolean,
        detectBike: Boolean,
        minRelativeHeight: Float
    ): List<Detection> {
        
        val detections = mutableListOf<Detection>()
        val numBoxes = 8400
        val numClasses = 80
        
        val scaleX = imgWidth.toFloat() / inputSize
        val scaleY = imgHeight.toFloat() / inputSize
        
        var maxConfSeen = 0f
        var maxConfClass = -1
        
        // SOTA: Cache-friendly parsing
        // Pre-extract box coordinates (sequential memory access)
        val boxes = FloatArray(numBoxes * 4)
        for (i in 0 until numBoxes) {
            boxes[i * 4 + 0] = output[i]                    // cx
            boxes[i * 4 + 1] = output[numBoxes + i]         // cy
            boxes[i * 4 + 2] = output[2 * numBoxes + i]     // w
            boxes[i * 4 + 3] = output[3 * numBoxes + i]     // h
        }
        
        // Parse detections with cache-friendly access
        for (i in 0 until numBoxes) {
            val cx = boxes[i * 4 + 0]
            val cy = boxes[i * 4 + 1]
            val w = boxes[i * 4 + 2]
            val h = boxes[i * 4 + 3]
            
            // Find best class (still strided, but only once per box)
            var bestConf = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val conf = output[(4 + c) * numBoxes + i]
                if (conf > bestConf) {
                    bestConf = conf
                    bestClass = c
                }
            }
            
            if (bestConf > maxConfSeen) {
                maxConfSeen = bestConf
                maxConfClass = bestClass
            }
            
            if (bestConf < confThreshold) continue
            
            // Class filtering
            val wantedClass = when {
                detectPerson && bestClass == CLASS_PERSON -> true
                detectCar && bestClass in listOf(CLASS_CAR, CLASS_BUS, CLASS_TRUCK, 
                    CLASS_TRAIN, CLASS_BOAT, CLASS_AIRPLANE, CLASS_MOTORCYCLE) -> true
                detectBike && bestClass == CLASS_BICYCLE -> true
                detectAnimal && bestClass in CLASS_BIRD..CLASS_GIRAFFE -> true
                else -> false
            }
            
            if (!wantedClass) continue
            
            // Convert to image coordinates
            // Model outputs normalized coords [0-1], convert to pixels first
            val cx_px = cx * inputSize
            val cy_px = cy * inputSize
            val w_px = w * inputSize
            val h_px = h * inputSize
            
            // Then scale to actual image size
            val objX = ((cx_px - w_px / 2) * scaleX).toInt().coerceIn(0, imgWidth)
            val objY = ((cy_px - h_px / 2) * scaleY).toInt().coerceIn(0, imgHeight)
            val objW = (w_px * scaleX).toInt().coerceIn(0, imgWidth - objX)
            val objH = (h_px * scaleY).toInt().coerceIn(0, imgHeight - objY)
            
            // SOTA: Quadrant-Relative Distance Filter (for 2x2 mosaic grids)
            // The 15% rule applies to the CAMERA's view, not the full mosaic
            // In a 2x2 grid, each quadrant is 50% of total height/width
            
            // Determine which quadrant the object center is in
            val centerX = objX + objW / 2
            val centerY = objY + objH / 2
            
            // Quadrant dimensions (half of total for 2x2 grid)
            val quadrantHeight = imgHeight / 2
            val quadrantWidth = imgWidth / 2
            
            // Calculate relative dimensions against the QUADRANT
            val relativeHeightToQuadrant = objH.toFloat() / quadrantHeight
            val relativeWidthToQuadrant = objW.toFloat() / quadrantWidth
            
            // Apply class-specific thresholds (SOTA: automotive lens standards)
            // Person: 1.7m tall - use HEIGHT (15% rule)
            // Car: 1.4m tall but 1.8m wide - use WIDTH (cars are wide, not tall!)
            // Bike: smaller profile - use HEIGHT with lower threshold
            val passesDistanceFilter = when (bestClass) {
                CLASS_PERSON -> relativeHeightToQuadrant >= minRelativeHeight  // 15% height = ~5m
                
                CLASS_CAR, CLASS_BUS, CLASS_TRUCK, CLASS_TRAIN -> {
                    // SOTA FIX: Cars are WIDE (1.8m) not tall (1.4m)
                    // A car at 5m is ~20% of quadrant WIDTH
                    // Use width-based filter for vehicles
                    relativeWidthToQuadrant >= (minRelativeHeight * 1.33f)  // 20% width
                }
                
                CLASS_BICYCLE, CLASS_MOTORCYCLE -> {
                    // Bikes are narrow and short - use height with lower threshold
                    relativeHeightToQuadrant >= (minRelativeHeight * 0.7f)  // ~10% height
                }
                
                else -> relativeHeightToQuadrant >= minRelativeHeight
            }
            
            if (!passesDistanceFilter) continue
            
            detections.add(Detection(bestClass, bestConf, objX, objY, objW, objH))
        }
        
        // Apply NMS
        val filtered = nms(detections, 0.45f)
        
        // SOTA: Ghost filter (max 50 detections)
        val final = if (filtered.size > 50) {
            logger.warn("Ghost filter: ${filtered.size} > 50, clearing")
            emptyList()
        } else {
            filtered
        }
        
        // Log class distribution
        val personCount = final.count { it.classId == CLASS_PERSON }
        val carCount = final.count { it.classId in listOf(CLASS_CAR, CLASS_BUS, CLASS_TRUCK, CLASS_TRAIN, CLASS_BOAT, CLASS_AIRPLANE, CLASS_MOTORCYCLE) }
        val bikeCount = final.count { it.classId == CLASS_BICYCLE }
        val animalCount = final.count { it.classId in CLASS_BIRD..CLASS_GIRAFFE }
        
        // FIX: Log the max confidence from KEPT detections, not from all 8400 raw boxes.
        // Previously, maxConfSeen/maxConfClass tracked the highest confidence across ALL
        // boxes including those that failed the class filter and confidence threshold.
        // This caused the log to report "person=1 (max_conf=0.660 class=2)" — the person
        // was the kept detection, but class=2 (car) was the highest raw box confidence.
        // The EventTimelineCollector uses the same class IDs from the Detection objects
        // (which are correct), but the misleading log made it look like a bug.
        var bestKeptConf = 0f
        var bestKeptClass = -1
        for (det in final) {
            if (det.confidence > bestKeptConf) {
                bestKeptConf = det.confidence
                bestKeptClass = det.classId
            }
        }
        
        logger.info("Detected ${final.size} objects: person=$personCount car=$carCount bike=$bikeCount animal=$animalCount (max_conf=${"%.3f".format(bestKeptConf)} class=$bestKeptClass)")
        
        return final
    }
    
    /**
     * Non-Maximum Suppression
     */
    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.size <= 1) return detections
        
        val sorted = detections.sortedByDescending { it.confidence }
        val results = mutableListOf<Detection>()
        
        for (det in sorted) {
            var keep = true
            for (res in results) {
                if (det.classId == res.classId && iou(det, res) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) results.add(det)
        }
        
        return results
    }
    
    /**
     * Calculate Intersection over Union
     */
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)
        
        val interW = max(0, x2 - x1)
        val interH = max(0, y2 - y1)
        val interArea = interW * interH
        
        val area1 = a.w * a.h
        val area2 = b.w * b.h
        val unionArea = area1 + area2 - interArea
        
        return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        // Acquiring interpLock blocks until any in-flight detect() releases it.
        // Without this, freeing the native interpreter mid-run would SIGSEGV
        // inside tensorflowlite_jni.
        synchronized(interpLock) {
            interpreter?.close()
            interpreter = null
            // Drop the reused buffers too — they'll be re-allocated on next init().
            shapedBuffer = null
            shapedBufferW = -1
            shapedBufferH = -1
            // floatOutput is shape-independent; safe to keep pooled.
        }
    }
}
