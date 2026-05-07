package com.longipinnatus.screentrans

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import java.util.BitSet
import java.util.Collections
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OcrEngine {
    private val TAG = OcrEngine::class.java.simpleName
    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var currentDetModel: String? = null
    private var currentRecModel: String? = null
    private var currentDetPath: String? = null
    private var currentRecPath: String? = null
    private var alphabet: List<String> = emptyList()

    private data class PreprocessedData(
        val buffer: FloatBuffer,
        val width: Int,
        val height: Int,
        val scaleX: Float,
        val scaleY: Float
    )

    fun init(context: Context, settings: AppSettings.SettingsData) {
        val detPath = resolveModelPath(context, settings.detModelType, settings.detCustomModelPath, "det_mobile.onnx")
        val recPath = resolveModelPath(context, settings.recModelType, settings.recCustomModelPath, "rec_mobile.onnx")

        if (ortEnv != null && detPath == currentDetPath && recPath == currentRecPath) {
            return
        }

        try {
            Log.i(TAG, "Initializing OcrEngine...")
            ortEnv = ortEnv ?: OrtEnvironment.getEnvironment()

            // Release existing sessions if we are re-initializing
            detSession?.close()
            recSession?.close()

            if (detPath.isEmpty() || recPath.isEmpty()) {
                Log.e(TAG, "Required models missing. Det: $detPath, Rec: $recPath")
                return
            }

            val sessionOptions = OrtSession.SessionOptions()
            detSession = ortEnv?.createSession(detPath, sessionOptions)
            recSession = ortEnv?.createSession(recPath, sessionOptions)

            currentDetModel = settings.detModelType
            currentRecModel = settings.recModelType
            currentDetPath = detPath
            currentRecPath = recPath

            LogManager.log(LogType.INFO, TAG, listOf(
                LogEntry("Status", "OcrEngine initialized"),
                LogEntry("DetModel", detPath.substringAfterLast("/")),
                LogEntry("RecModel", recPath.substringAfterLast("/"))
            ))

            if (alphabet.isEmpty()) {
                alphabet = listOf("blank") + context.assets.open("dict.txt").bufferedReader().readLines() + " "
            }
            Log.d(TAG, "OcrEngine initialized.")
        } catch (e: Exception) {
            LogManager.logException(TAG, "Initialization failed", e)
        }
    }

    fun release() {
        try {
            detSession?.close()
            detSession = null
            recSession?.close()
            recSession = null
            ortEnv?.close()
            ortEnv = null
        } catch (e: Exception) {
            Log.e(TAG, "Release failed", e)
        }
    }

    fun process(bitmap: Bitmap, settings: AppSettings.SettingsData): List<TextElement> {
        val preprocessed = preprocess(bitmap) ?: return emptyList()
        val boxes = detect(preprocessed, settings)
        
        val results = mutableListOf<TextElement>()
        for (box in boxes) {
            val isVert = when (settings.textOrientation) {
                AppSettings.TEXT_ORIENTATION_VERTICAL -> true
                AppSettings.TEXT_ORIENTATION_HORIZONTAL -> false
                else -> box.height() > box.width() * 1.5
            }
            
            val cropped = cropAndRescale(bitmap, box)
            val colors = extractColors(cropped)
            val finalInput = if (isVert) {
                val rotated = rotateBitmap(cropped)
                if (rotated !== cropped && cropped !== bitmap) cropped.recycle()
                rotated
            } else {
                cropped
            }

            val text = recognize(finalInput).trim()
            if (finalInput !== bitmap) finalInput.recycle()

            if (text.isNotEmpty()) {
                results.add(
                    TextElement(
                        text = text,
                        bounds = box,
                        isVertical = isVert,
                        textColor = colors.first,
                        backgroundColor = colors.second,
                        colorWeight = text.length
                    )
                )
            }
        }
        return results
    }

    private fun preprocess(bitmap: Bitmap): PreprocessedData? {
        val ratio = 960f / max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val detWidth = max(32, ((bitmap.width * ratio / 32.0).roundToInt() * 32))
        val detHeight = max(32, ((bitmap.height * ratio / 32.0).roundToInt() * 32))

        Log.d(TAG, "preprocess: image size=${detWidth}x${detHeight}, ratio=$ratio")

        if (detWidth <= 0 || detHeight <= 0) return null

        val scaledBitmap = bitmap.scale(detWidth, detHeight)
        try {
            val imgData = FloatBuffer.allocate(1 * 3 * detHeight * detWidth)
            val pixels = IntArray(detWidth * detHeight)
            scaledBitmap.getPixels(pixels, 0, detWidth, 0, 0, detWidth, detHeight)

            // Matches img_mode: BGR and NormalizeImage in YAML
            // PaddleOCR applies mean/std to B, G, R sequentially in BGR mode
            val means = floatArrayOf(0.485f, 0.456f, 0.406f)
            val stds = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (c in 0..2) {
                for (i in 0 until detHeight) {
                    for (j in 0 until detWidth) {
                        val pix = pixels[i * detWidth + j]
                        val v = when (c) {
                            0 -> pix and 0xFF               // Blue
                            1 -> (pix shr 8) and 0xFF       // Green
                            else -> (pix shr 16) and 0xFF   // Red
                        }
                        imgData.put((v / 255.0f - means[c]) / stds[c])
                    }
                }
            }
            imgData.rewind()
            
            // det outputs in PaddleOCR DB are often the same size as input tensor.
            return PreprocessedData(
                buffer = imgData,
                width = detWidth,
                height = detHeight,
                scaleX = bitmap.width.toFloat() / detWidth,
                scaleY = bitmap.height.toFloat() / detHeight
            )
        } finally {
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        }
    }

    private fun detect(data: PreprocessedData, settings: AppSettings.SettingsData): List<Rect> {
        val env = ortEnv ?: return emptyList()
        val det = detSession ?: return emptyList()

        val boxes = mutableListOf<Rect>()
        try {
            val inputName = det.inputNames.iterator().next()
            OnnxTensor.createTensor(env, data.buffer, longArrayOf(1, 3, data.height.toLong(), data.width.toLong())).use { inputTensor ->
                det.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
                    val outputTensor = results[0] as OnnxTensor
                    val shape = outputTensor.info.shape
                    val outH = shape[2].toInt()
                    val outW = shape[3].toInt()
                    val probMap = FloatArray(outH * outW)
                    outputTensor.floatBuffer.get(probMap)

                    val visited = BitSet(outH * outW)
                    val scaleX = data.scaleX * (data.width.toFloat() / outW)
                    val scaleY = data.scaleY * (data.height.toFloat() / outH)

                    for (i in 0 until outH) {
                        for (j in 0 until outW) {
                            val idx = i * outW + j
                            if (probMap[idx] > settings.pixelThresh && !visited.get(idx)) {
                                var minI = i; var maxI = i
                                var minJ = j; var maxJ = j
                                var sumScore = 0f; var count = 0
                                val q: Queue<Int> = LinkedList()
                                q.add(idx)
                                visited.set(idx)
                                while (q.isNotEmpty()) {
                                    val curr = q.remove()
                                    count++
                                    val ci = curr / outW; val cj = curr % outW
                                    sumScore += probMap[curr]
                                    minI = min(minI, ci); maxI = max(maxI, ci)
                                    minJ = min(minJ, cj); maxJ = max(maxJ, cj)
                                    val neighbors = arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
                                    for (d in neighbors) {
                                        val ni = ci + d.first; val nj = cj + d.second
                                        if (ni in 0 until outH && nj in 0 until outW) {
                                            val nIdx = ni * outW + nj
                                            if (probMap[nIdx] > settings.pixelThresh && !visited.get(nIdx)) {
                                                visited.set(nIdx); q.add(nIdx)
                                            }
                                        }
                                    }
                                }
                                val avgScore = sumScore / count
                                if (avgScore > settings.boxThresh && count > 10) {
                                    val boxW = (maxJ - minJ).toFloat()
                                    val boxH = (maxI - minI).toFloat()
                                    val offset = (boxW * boxH * settings.unclipRatio) / (2 * (boxW + boxH))
                                    
                                    val left = ((minJ - offset) * scaleX).toInt()
                                    val top = ((minI - offset) * scaleY).toInt()
                                    val right = ((maxJ + offset) * scaleX).toInt()
                                    val bottom = ((maxI + offset) * scaleY).toInt()

                                    if (left < 0 || top < 0 || right > (data.width * scaleX) || bottom > (data.height * scaleY)) {
                                        Log.w(TAG, "extractBoxes: Box out of bounds")
                                    }

                                    boxes.add(Rect(left, top, right, bottom))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
        }
        return boxes
    }

    private fun recognize(bitmap: Bitmap): String {
        val env = ortEnv ?: return ""
        val rec = recSession ?: return ""
        val targetH = 48
        val ratio = targetH.toFloat() / bitmap.height
        val targetW = max(targetH, (bitmap.width * ratio).toInt())
        val scaled = bitmap.scale(targetW, targetH)
        
        try {
            val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)
            val pixels = IntArray(targetW * targetH)
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            for (c in 0..2) {
                for (i in 0 until targetH) {
                    for (j in 0 until targetW) {
                        val pix = pixels[i * targetW + j]
                        val v = when (c) {
                            0 -> pix and 0xFF              // Blue (Matches BGR)
                            1 -> (pix shr 8) and 0xFF      // Green
                            else -> (pix shr 16) and 0xFF  // Red
                        }
                        imgData.put((v / 255.0f - 0.5f) / 0.5f)
                    }
                }
            }
            imgData.rewind()

            val inputName = rec.inputNames.iterator().next()
            OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, targetH.toLong(), targetW.toLong())).use { inputTensor ->
                rec.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
                    val outputTensor = results.asSequence()
                        .map { it.value as OnnxTensor }
                        .firstOrNull { it.info.shape.size == 3 }
                        ?: (results[0] as OnnxTensor)

                    val recShape = outputTensor.info.shape
                    val seqLen = recShape[1].toInt()
                    val dictSize = recShape[2].toInt()
                    val outputArr = FloatArray(seqLen * dictSize)
                    outputTensor.floatBuffer.get(outputArr)
                    val sb = StringBuilder()
                    var lastIdx = -1
                    for (i in 0 until seqLen) {
                        var maxVal = -100f; var maxIdx = -1
                        for (j in 0 until dictSize) {
                            val v = outputArr[i * dictSize + j]
                            if (v > maxVal) { maxVal = v; maxIdx = j }
                        }
                        if (maxIdx > 0 && maxIdx < alphabet.size && maxIdx != lastIdx) {
                            sb.append(alphabet[maxIdx])
                        }
                        lastIdx = maxIdx
                    }
                    return sb.toString()
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun extractColors(bitmap: Bitmap): Pair<Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return Pair(Color.WHITE, Color.BLACK)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Quantize color to group similar shades (bits reduction)
        fun quantize(color: Int): Int {
            val r = Color.red(color) and 0xF0
            val g = Color.green(color) and 0xF0
            val b = Color.blue(color) and 0xF0
            return Color.rgb(r, g, b)
        }

        // Estimate background color from the edges
        val edgeColors = mutableListOf<Int>()
        for (i in 0 until width) {
            edgeColors.add(pixels[i])                        // Top
            edgeColors.add(pixels[(height - 1) * width + i]) // Bottom
        }
        for (i in 0 until height) {
            edgeColors.add(pixels[i * width])                // Left
            edgeColors.add(pixels[i * width + width - 1])    // Right
        }

        // Use quantized mode for background to handle noise/gradients
        val quantizedBg = edgeColors.groupBy { quantize(it) }.maxByOrNull { it.value.size }?.key ?: Color.BLACK
        val bgColor = edgeColors.filter { quantize(it) == quantizedBg }
            .groupBy { it }.maxByOrNull { it.value.size }?.key ?: quantizedBg
        
        Log.d(TAG, "extractColors: BG Detected: ${Integer.toHexString(bgColor)} (Quantized: ${Integer.toHexString(quantizedBg)})")

        // Estimate text color: look for the most prominent color different from background
        val bgR = Color.red(bgColor); val bgG = Color.green(bgColor); val bgB = Color.blue(bgColor)
        val foregroundPixels = mutableListOf<Int>()
        
        for (pixel in pixels) {
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val dist = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
            if (dist > 120) { // Threshold for being "foreground"
                foregroundPixels.add(pixel)
            }
        }
        
        Log.d(TAG, "extractColors: Foreground pixel count: ${foregroundPixels.size}/${pixels.size}")

        val textColor = if (foregroundPixels.isNotEmpty()) {
            // Group foreground pixels into quantized buckets
            val buckets = foregroundPixels.groupBy { quantize(it) }
            // Get top 3 most frequent color buckets to avoid being skewed by outliers
            val topBuckets = buckets.entries.sortedByDescending { it.value.size }.take(3)
            // From these frequent buckets, pick the one with maximum contrast to background
            val bestQuantizedFg = topBuckets.maxByOrNull { (qColor, _) ->
                val r = Color.red(qColor); val g = Color.green(qColor); val b = Color.blue(qColor)
                abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
            }?.key ?: Color.WHITE

            foregroundPixels.filter { quantize(it) == bestQuantizedFg }
                .groupBy { it }.maxByOrNull { it.value.size }?.key ?: bestQuantizedFg
        } else {
            val fallback = if (bgR + bgG + bgB > 382) Color.BLACK else Color.WHITE
            Log.d(TAG, "extractColors: No foreground found, using fallback: ${Integer.toHexString(fallback)}")
            fallback
        }
        
        return Pair(textColor, bgColor)
    }

    private fun resolveModelPath(context: Context, type: String, customPath: String, defaultModelName: String): String {
        if (type == AppSettings.MODEL_TYPE_CUSTOM && customPath.isNotEmpty()) {
            if (java.io.File(customPath).exists()) return customPath
            Log.w(TAG, "Custom model not found at $customPath, falling back to $defaultModelName")
        }

        val file = java.io.File(context.cacheDir, defaultModelName)
        if (!file.exists()) {
            try {
                context.assets.open(defaultModelName).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve model $defaultModelName: $e")
                return ""
            }
        }
        return file.absolutePath
    }

    private fun cropAndRescale(original: Bitmap, rect: Rect): Bitmap {
        val x = max(0, rect.left); val y = max(0, rect.top)
        val width = min(rect.width(), original.width - x)
        val height = min(rect.height(), original.height - y)
        if (width <= 0 || height <= 0) return createBitmap(1, 1)
        return Bitmap.createBitmap(original, x, y, width, height)
    }

    private fun rotateBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(-90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
