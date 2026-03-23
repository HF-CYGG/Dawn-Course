package com.dawncourse.feature.import_module.engine.ocr

import android.graphics.Bitmap

/**
 * OCR 识别出的文本边界框
 *
 * 用于后续的网格聚类和坐标映射
 */
data class OcrBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * OCR 识别出的单个文本块
 *
 * @property text 识别出的文本内容
 * @property boundingBox 文本所在的物理坐标边界框
 * @property confidence 识别置信度 (0.0 - 1.0)
 */
data class TextBlock(
    val text: String,
    val boundingBox: OcrBoundingBox,
    val confidence: Float = 1.0f
)

/**
 * OCR 引擎抽象接口
 *
 * 为后续接入具体的轻量级 OCR SDK（如 PaddleOCR Lite、NCNN 等）提供统一规范，
 * 满足“离线可用、按需下载模型”的架构要求。
 */
interface OcrEngine {
    
    /**
     * 初始化引擎
     *
     * 包含检查模型文件是否存在、触发下载以及加载模型到内存等耗时操作。
     * 必须在协程中调用。
     */
    suspend fun initialize()
    
    /**
     * 提取图片中的文本块
     *
     * @param bitmap 经过预处理（裁剪、缩放）后的图片
     * @return 包含坐标和置信度的文本块列表
     */
    suspend fun extractText(bitmap: Bitmap): List<TextBlock>
    
    /**
     * 释放引擎资源，避免内存泄漏
     */
    fun release()
}
