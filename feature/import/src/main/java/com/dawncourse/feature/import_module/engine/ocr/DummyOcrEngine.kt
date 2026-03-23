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
        // 长截屏分块处理策略 (Phase 4)
        // 1. 判断是否为长图 (例如高度大于宽度的 2.5 倍，或绝对高度超过 3000px)
        val isLongImage = bitmap.height > bitmap.width * 2.5 || bitmap.height > 3000
        
        val results = mutableListOf<TextBlock>()
        
        if (isLongImage) {
            // 2. 设定分块参数
            val chunkHeight = 2000 // 每个分块的高度
            val overlap = 200      // 相邻分块的重叠区域，防止文字被从中间截断
            var currentY = 0
            
            while (currentY < bitmap.height) {
                // 计算当前分块的实际高度，最后一块可能不足 chunkHeight
                val h = if (currentY + chunkHeight > bitmap.height) {
                    bitmap.height - currentY
                } else {
                    chunkHeight
                }
                
                // 裁剪当前分块
                val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, bitmap.width, h)
                
                // 模拟单块推理耗时
                delay(500)
                val chunkBlocks = extractTextFromChunk(chunkBitmap)
                
                // 3. 坐标映射与重叠去重
                // 将局部坐标映射回原图的绝对坐标
                val mappedBlocks = chunkBlocks.map { block ->
                    val newBox = OcrBoundingBox(
                        left = block.boundingBox.left,
                        top = block.boundingBox.top + currentY,
                        right = block.boundingBox.right,
                        bottom = block.boundingBox.bottom + currentY
                    )
                    block.copy(boundingBox = newBox)
                }
                
                // 简单的重叠去重：如果当前块的文本块与已收集的文本块在坐标上有明显重叠，则丢弃
                for (block in mappedBlocks) {
                    val isDuplicate = results.any { existing ->
                        val yOverlap = maxOf(0, minOf(existing.boundingBox.bottom, block.boundingBox.bottom) - 
                                              maxOf(existing.boundingBox.top, block.boundingBox.top))
                        // 如果 Y 轴重叠超过自身高度的 50%，认为是同一个文本块（简化的去重逻辑）
                        yOverlap > block.boundingBox.height * 0.5 && existing.text == block.text
                    }
                    if (!isDuplicate) {
                        results.add(block)
                    }
                }
                
                // 步进到下一块，减去重叠区域
                currentY += chunkHeight - overlap
                chunkBitmap.recycle()
            }
        } else {
            // 非长图，直接单次推理
            delay(800)
            results.addAll(extractTextFromChunk(bitmap))
        }
        
        return results
    }

    /**
     * 模拟从单个图片块中提取文本
     */
    private fun extractTextFromChunk(bitmap: Bitmap): List<TextBlock> {
        // 实际开发中会调用具体的 OCR SDK，这里返回空列表或模拟数据
        return emptyList()
    }

    override fun release() {
        // 释放相关资源
    }
}
