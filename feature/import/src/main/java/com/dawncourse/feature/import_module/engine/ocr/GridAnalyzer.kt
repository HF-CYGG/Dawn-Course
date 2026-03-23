package com.dawncourse.feature.import_module.engine.ocr

import kotlin.math.abs

/**
 * 课表网格中的单个单元格
 *
 * @property startSection 对应的起始节次 (例如第 1 节)
 * @property dayOfWeek 对应的星期 (1 = 周一, 7 = 周日)
 * @property blocks 落在该单元格范围内的所有文本块
 */
data class GridCell(
    val startSection: Int,
    val dayOfWeek: Int,
    val blocks: List<TextBlock>
)

/**
 * 网格布局分析引擎 (Layout Engine)
 *
 * 核心职责：将离散的、无结构 OCR 文本块，通过物理坐标聚类，还原为二维的表格结构。
 * 遵循“表格优先”策略，先找表头基准，再映射业务数据。
 */
class GridAnalyzer {

    /**
     * 分析文本块，建立网格坐标系并映射
     *
     * @param blocks 原始的文本块列表
     * @return 结构化的单元格列表
     */
    fun analyze(blocks: List<TextBlock>): List<GridCell> {
        if (blocks.isEmpty()) return emptyList()

        // 1. 寻找表头基准 (X轴划分：星期一到星期日)
        // 使用正则匹配包含“一”到“日”的块，且通常带有“周”或“星期”字样
        val dayRegex = Regex("一|二|三|四|五|六|日|天")
        val headerBlocks = blocks.filter { block ->
            val text = block.text
            (text.contains("周") || text.contains("星期")) && dayRegex.containsMatchIn(text)
        }.sortedBy { it.boundingBox.centerX }

        val imageWidth = blocks.maxOf { it.boundingBox.right }
        val imageLeft = blocks.minOf { it.boundingBox.left }
        val contentWidth = imageWidth - imageLeft
        
        // 提取 7 列的中心点 X 坐标
        val colCenters = mutableListOf<Int>()
        if (headerBlocks.size >= 5) {
            // 如果成功识别到大部分表头，以它们为准
            colCenters.addAll(headerBlocks.map { it.boundingBox.centerX })
            // 若表头不足7个，这里应当有补全逻辑（简化版：如果全，就直接用）
        } else {
            // 虚拟补线：若未识别到表头，则默认去掉左侧时间轴区域（约占 1/8），将剩余宽度等分为 7 份
            val colWidth = contentWidth / 8
            for (i in 1..7) {
                colCenters.add(imageLeft + colWidth * i + colWidth / 2)
            }
        }

        // 2. 寻找时间轴基准 (Y轴划分：第1节到第12节)
        // 通常时间轴在最左侧（前 15% 宽度区域），且文本为纯数字
        val leftBoundary = imageLeft + (contentWidth * 0.15).toInt()
        val sectionBlocks = blocks.filter { block ->
            block.boundingBox.centerX < leftBoundary && block.text.matches(Regex("\\d+"))
        }.sortedBy { it.boundingBox.centerY }

        val rowCenters = mutableListOf<Int>()
        if (sectionBlocks.isNotEmpty()) {
            rowCenters.addAll(sectionBlocks.map { it.boundingBox.centerY })
        } else {
            // 虚拟补线：基于所有文本行的 Y 坐标进行一维聚类
            val sortedByY = blocks.sortedBy { it.boundingBox.centerY }
            var currentY = sortedByY.first().boundingBox.centerY
            rowCenters.add(currentY)
            for (block in sortedByY) {
                // 如果当前块的 Y 坐标距离上一个聚类中心超过自身高度的 1.5 倍，认为是新的一行
                if (block.boundingBox.centerY - currentY > block.boundingBox.height * 1.5) {
                    currentY = block.boundingBox.centerY
                    rowCenters.add(currentY)
                }
            }
        }

        // 3. 匹配映射 (将非表头、非时间轴的业务文本映射到网格中)
        val contentBlocks = blocks - headerBlocks.toSet() - sectionBlocks.toSet()
        
        val cellMap = mutableMapOf<Pair<Int, Int>, MutableList<TextBlock>>()

        for (block in contentBlocks) {
            val cx = block.boundingBox.centerX
            val cy = block.boundingBox.centerY

            // 寻找 X 坐标最接近的列 (1..7)
            val colIndex = colCenters.indices.minByOrNull { abs(colCenters[it] - cx) } ?: -1
            val dayOfWeek = colIndex + 1 // 1-based

            // 寻找 Y 坐标最接近的行 (节次)
            val rowIndex = rowCenters.indices.minByOrNull { abs(rowCenters[it] - cy) } ?: -1
            val section = rowIndex + 1 // 1-based

            // 仅当映射在有效范围内时才加入
            if (dayOfWeek in 1..7 && section > 0) {
                val key = Pair(section, dayOfWeek)
                cellMap.getOrPut(key) { mutableListOf() }.add(block)
            }
        }

        // 4. 组装结果并保证阅读顺序
        return cellMap.map { (key, cellBlocks) ->
            GridCell(
                startSection = key.first,
                dayOfWeek = key.second,
                // 对同一单元格内的文本，从上到下排序，还原真实文本结构
                blocks = cellBlocks.sortedBy { it.boundingBox.centerY }
            )
        }
    }
}
