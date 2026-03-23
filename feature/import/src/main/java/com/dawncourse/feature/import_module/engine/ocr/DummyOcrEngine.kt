package com.dawncourse.feature.import_module.engine.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.delay

/**
 * 占位用 OCR 引擎实现 (用于 Phase 1 前期集成测试)
 *
 * 模拟引擎初始化、模型下载和 OCR 推理的耗时操作，
 * 方便跑通 "Image -> Pipeline -> ReviewStep" 的整体链路。
 */
class DummyOcrEngine : OcrEngine {

    override suspend fun initialize() {
        // 模拟检查本地模型与下载耗时
        delay(1500)
    }

    override suspend fun extractText(bitmap: Bitmap): List<TextBlock> {
        // 模拟 OCR 图像推理耗时
        delay(800)
        
        // 暂时返回空数据，实际开发中会返回真实提取出的文本和 BoundingBox
        return emptyList()
    }

    override fun release() {
        // 释放相关资源
    }
}
