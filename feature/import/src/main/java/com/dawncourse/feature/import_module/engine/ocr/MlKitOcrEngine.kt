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
import kotlin.math.sqrt
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

    private var processedBitmap: Bitmap? = null

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
            processedBitmap = preprocessed
            extractTextFromChunk(preprocessed)
        }
    }

    fun getProcessedBitmap(): Bitmap? {
        return processedBitmap
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
        val contrastScore = calculateContrastScore(denoised)
        val enhanced = if (contrastScore < 40f) {
            val binary = adaptiveThreshold(denoised)
            val closed = morphClose(binary)
            // 对低对比度图像也应用轻度锐化
            unsharpMask(closed, 0.5f)
        } else {
            // 对高对比度图像应用更强的锐化
            unsharpMask(denoised, 1.0f)
        }
        val angle = estimateSkewAngle(enhanced)
        return if (abs(angle) >= 0.3f) {
            rotateBitmap(enhanced, -angle)
        } else {
            enhanced
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

    private fun calculateContrastScore(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var sum = 0f
        var minValue = 255f
        var maxValue = 0f
        for (p in pixels) {
            val v = Color.red(p).toFloat()
            sum += v
            minValue = min(minValue, v)
            maxValue = max(maxValue, v)
        }
        val mean = sum / pixels.size
        var variance = 0f
        for (p in pixels) {
            val v = Color.red(p) - mean
            variance += v * v
        }
        val std = kotlin.math.sqrt(variance / pixels.size)
        // 综合考虑标准差和动态范围
        val dynamicRange = maxValue - minValue
        return std * 0.7f + dynamicRange * 0.3f
    }

    private fun unsharpMask(bitmap: Bitmap, strength: Float = 1.0f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val src = IntArray(width * height)
        val blur = IntArray(width * height)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        acc += Color.red(src[yy * width + xx])
                        count++
                    }
                }
                val avg = acc / count
                blur[y * width + x] = avg
            }
        }
        val outPixels = IntArray(width * height)
        for (i in src.indices) {
            val v = Color.red(src[i])
            val b = blur[i]
            val detail = (v - b) * strength
            val nv = (v + detail).coerceIn(0, 255)
            outPixels[i] = Color.argb(255, nv.toInt(), nv.toInt(), nv.toInt())
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun adaptiveThreshold(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 1..height) {
            var rowSum = 0
            val rowOffset = y * (width + 1)
            val srcOffset = (y - 1) * width
            for (x in 1..width) {
                rowSum += Color.red(pixels[srcOffset + x - 1])
                integral[rowOffset + x] = integral[rowOffset - (width + 1) + x] + rowSum
            }
        }
        val minSide = min(width, height)
        val window = (minSide / 16).coerceIn(16, 40)
        val half = window / 2
        val outPixels = IntArray(width * height)
        for (y in 0 until height) {
            val y1 = (y - half).coerceIn(0, height - 1)
            val y2 = (y + half).coerceIn(0, height - 1)
            val iy1 = y1
            val iy2 = y2 + 1
            for (x in 0 until width) {
                val x1 = (x - half).coerceIn(0, width - 1)
                val x2 = (x + half).coerceIn(0, width - 1)
                val ix1 = x1
                val ix2 = x2 + 1
                val area = (ix2 - ix1) * (iy2 - iy1)
                val sum = integral[iy2 * (width + 1) + ix2] -
                    integral[iy1 * (width + 1) + ix2] -
                    integral[iy2 * (width + 1) + ix1] +
                    integral[iy1 * (width + 1) + ix1]
                val mean = sum / area
                // 计算局部标准差作为对比度度量
                var variance = 0.0
                for (yy in y1..y2) {
                    for (xx in x1..x2) {
                        val valDiff = Color.red(pixels[yy * width + xx]) - mean
                        variance += valDiff * valDiff
                    }
                }
                val stdDev = kotlin.math.sqrt(variance / area)
                // 根据局部对比度动态调整阈值
                val thresholdOffset = when {
                    stdDev < 10 -> 5  // 低对比度，较小偏移
                    stdDev < 30 -> 12 // 中等对比度
                    else -> 20        // 高对比度，较大偏移
                }
                val v = Color.red(pixels[y * width + x])
                val threshold = mean - thresholdOffset
                val out = if (v < threshold) 0 else 255
                outPixels[y * width + x] = Color.argb(255, out, out, out)
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun morphClose(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val src = IntArray(width * height)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)
        
        // 膨胀操作
        val dilated = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var hasBlack = false
                // 3x3 结构元素
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val v = Color.red(src[yy * width + xx])
                        if (v < 128) {
                            hasBlack = true
                            break
                        }
                    }
                    if (hasBlack) break
                }
                val out = if (hasBlack) 0 else 255
                dilated[y * width + x] = Color.argb(255, out, out, out)
            }
        }
        
        // 腐蚀操作
        val eroded = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var allBlack = true
                // 3x3 结构元素
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val v = Color.red(dilated[yy * width + xx])
                        if (v >= 128) {
                            allBlack = false
                            break
                        }
                    }
                    if (!allBlack) break
                }
                val out = if (allBlack) 0 else 255
                eroded[y * width + x] = Color.argb(255, out, out, out)
            }
        }
        
        // 再次膨胀以增强文字连接
        val final = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var hasBlack = false
                for (dy in -1..0) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val v = Color.red(eroded[yy * width + xx])
                        if (v < 128) {
                            hasBlack = true
                            break
                        }
                    }
                    if (hasBlack) break
                }
                val out = if (hasBlack) 0 else 255
                final[y * width + x] = Color.argb(255, out, out, out)
            }
        }
        
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(final, 0, width, 0, 0, width, height)
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
        
        // 优化二值化处理，使用Otsu阈值
        val binary = ByteArray(width * height)
        val threshold = calculateOtsuThreshold(pixels)
        for (i in pixels.indices) {
            val v = Color.red(pixels[i])
            binary[i] = if (v < threshold) 0 else 1
        }
        
        // 优化角度搜索范围和步长
        var bestAngle = 0f
        var bestScore = -1f
        
        // 粗搜索：步长0.5度，范围-3到3度
        var angle = -3f
        while (angle <= 3f) {
            val score = calculateSkewScore(binary, width, height, angle)
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += 0.5f
        }
        
        // 精搜索：在最佳角度附近，步长0.1度，范围±0.5度
        val fineStart = bestAngle - 0.5f
        val fineEnd = bestAngle + 0.5f
        var fineAngle = fineStart
        while (fineAngle <= fineEnd) {
            val score = calculateSkewScore(binary, width, height, fineAngle)
            if (score > bestScore) {
                bestScore = score
                bestAngle = fineAngle
            }
            fineAngle += 0.1f
        }
        
        return bestAngle
    }
    
    private fun calculateOtsuThreshold(pixels: IntArray): Int {
        val histogram = IntArray(256)
        for (p in pixels) {
            val v = Color.red(p)
            histogram[v]++
        }
        
        val total = pixels.size
        var sum = 0
        for (i in 0..255) {
            sum += i * histogram[i]
        }
        
        var sumB = 0
        var wB = 0
        var wF = 0
        var maxVariance = 0.0
        var threshold = 0
        
        for (i in 0..255) {
            wB += histogram[i]
            if (wB == 0) continue
            
            wF = total - wB
            if (wF == 0) break
            
            sumB += i * histogram[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            
            val variance = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i
            }
        }
        
        return threshold
    }
    
    private fun calculateSkewScore(binary: ByteArray, width: Int, height: Int, angle: Float): Float {
        val rad = Math.toRadians(angle.toDouble())
        val tan = kotlin.math.tan(rad)
        val bins = IntArray(height)
        
        // 只处理非空白区域，提高效率
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
        
        // 计算行之间的差异，差异越大说明文字越对齐
        var score = 0f
        for (i in 1 until height) {
            val diff = bins[i] - bins[i - 1]
            score += diff * diff
        }
        
        return score
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
