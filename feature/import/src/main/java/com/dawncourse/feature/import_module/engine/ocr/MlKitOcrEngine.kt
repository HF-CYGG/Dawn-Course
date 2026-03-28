package com.dawncourse.feature.import_module.engine.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 真实的 OCR 引擎实现 (基于 Google ML Kit 离线中文版)
 *
 * 具备离线可用、按需初始化的特点，完美符合 "本地优先" 原则。
 */
class MlKitOcrEngine : OcrEngine {

    // 懒加载初始化 Recognizer
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun initialize() {
        // ML Kit 内部在首次调用时会自动初始化模型
        // 此处可以留空，或进行简单的预热
        withContext(Dispatchers.IO) {
            // 预热：触发 lazy 初始化
            recognizer
        }
    }

    override suspend fun extractText(bitmap: Bitmap): List<TextBlock> {
        return withContext(Dispatchers.IO) {
            val safeBitmap = if (bitmap.height > 8000) {
                val scale = 8000f / bitmap.height
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), 8000, true)
            } else {
                bitmap
            }
            val preprocessed = preprocessBitmap(safeBitmap)
            extractTextFromChunk(preprocessed)
        }
    }

    /**
     * 内部方法：从单个图片块中提取文本
     */
    private suspend fun extractTextFromChunk(bitmap: Bitmap): List<TextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        return try {
            val visionText = suspendCancellableCoroutine<Text> { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (continuation.isActive) {
                            continuation.resume(text)
                        }
                    }
                    .addOnFailureListener { e ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
            }
            
            val textBlocks = mutableListOf<TextBlock>()
            
            // 遍历所有识别出的文本块
            for (block in visionText.textBlocks) {
                // 有些情况下我们需要更细粒度的行 (Line) 或元素 (Element)
                // 这里为了维持网格分析的精度，我们使用 Line 级别的数据作为我们业务的 TextBlock
                for (line in block.lines) {
                    val boundingBox = line.boundingBox
                    if (boundingBox != null) {
                        val ocrBox = OcrBoundingBox(
                            left = boundingBox.left,
                            top = boundingBox.top,
                            right = boundingBox.right,
                            bottom = boundingBox.bottom
                        )
                        textBlocks.add(
                            TextBlock(
                                text = line.text,
                                boundingBox = ocrBox,
                                // ML Kit V2 未暴露具体的 confidence，默认给 1.0f，后续通过启发式规则动态扣分
                                confidence = 1.0f 
                            )
                        )
                    }
                }
            }
            textBlocks
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun release() {
        recognizer.close()
    }

    private fun preprocessBitmap(bitmap: Bitmap): Bitmap {
        val normalized = normalizeBitmap(bitmap)
        val downscaled = downscaleForProcessing(normalized)
        val grayscale = toGrayscale(downscaled)
        val contrast = autoContrast(grayscale)
        val denoised = medianDenoise(contrast)
        val angle = estimateSkewAngle(denoised)
        return if (abs(angle) >= 0.3f) {
            rotateBitmap(denoised, -angle)
        } else {
            denoised
        }
    }

    private fun normalizeBitmap(bitmap: Bitmap): Bitmap {
        val config = runCatching { bitmap.config }.getOrNull() ?: Bitmap.Config.ARGB_8888
        if (config != Bitmap.Config.HARDWARE && bitmap.isMutable) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun downscaleForProcessing(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= 3000) return bitmap
        val scale = 3000f / maxSide
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, gray, gray, gray)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun autoContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hist = IntArray(256)
        for (p in pixels) {
            val v = Color.red(p)
            hist[v]++
        }
        val total = width * height
        val lowCount = (total * 0.01f).roundToInt()
        val highCount = (total * 0.99f).roundToInt()
        var acc = 0
        var low = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowCount) {
                low = i
                break
            }
        }
        acc = 0
        var high = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highCount) {
                high = i
                break
            }
        }
        val range = max(1, high - low)
        for (i in pixels.indices) {
            val v = Color.red(pixels[i])
            val nv = ((v - low) * 255 / range).coerceIn(0, 255)
            pixels[i] = Color.argb(255, nv, nv, nv)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun medianDenoise(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val src = IntArray(width * height)
        val dst = IntArray(width * height)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)
        val window = IntArray(9)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var k = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        window[k++] = Color.red(src[yy * width + xx])
                    }
                }
                window.sort()
                val m = window[4]
                dst[y * width + x] = Color.argb(255, m, m, m)
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, width, 0, 0, width, height)
        return out
    }

    private fun estimateSkewAngle(bitmap: Bitmap): Float {
        val maxSide = max(bitmap.width, bitmap.height)
        val targetScale = if (maxSide > 900) 900f / maxSide else 1f
        val scaled = if (targetScale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * targetScale).roundToInt().coerceAtLeast(1),
                (bitmap.height * targetScale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val binary = ByteArray(width * height)
        for (i in pixels.indices) {
            val v = Color.red(pixels[i])
            binary[i] = if (v < 128) 0 else 1
        }
        var bestAngle = 0f
        var bestScore = -1f
        var angle = -3f
        while (angle <= 3f) {
            val rad = Math.toRadians(angle.toDouble())
            val tan = kotlin.math.tan(rad)
            val bins = IntArray(height)
            for (y in 0 until height) {
                val rowIndex = y * width
                for (x in 0 until width) {
                    if (binary[rowIndex + x].toInt() == 0) {
                        val y2 = (y + x * tan).roundToInt()
                        if (y2 in 0 until height) {
                            bins[y2]++
                        }
                    }
                }
            }
            var score = 0f
            for (i in 1 until height) {
                val diff = bins[i] - bins[i - 1]
                score += diff * diff
            }
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += 0.5f
        }
        return bestAngle
    }

    private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated == bitmap) return bitmap
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rotated, 0f, 0f, null)
        if (rotated != out) rotated.recycle()
        return out
    }
}
