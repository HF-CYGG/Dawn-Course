package com.dawncourse.feature.import_module.engine.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
            extractTextFromChunk(safeBitmap)
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
}
