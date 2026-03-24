package com.dawncourse.feature.import_module.engine.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

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

        val imageLeft = blocks.minOf { it.boundingBox.left }
        val imageRight = blocks.maxOf { it.boundingBox.right }
        val imageTop = blocks.minOf { it.boundingBox.top }
        val imageBottom = blocks.maxOf { it.boundingBox.bottom }
        val contentWidth = imageRight - imageLeft
        val contentHeight = imageBottom - imageTop

        // 1. 寻找时间轴基准 (Y轴划分：第1节到第12节)
        // 通常时间轴在最左侧（前 15% 宽度区域），且文本为纯数字
        val leftBoundary = imageLeft + (contentWidth * 0.15).toInt()
        val sectionBlocks = blocks.filter { block ->
            block.boundingBox.centerX < leftBoundary && parseSectionNumber(block.text) != null
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

        // 2. 寻找表头基准 (X轴划分：星期一到星期日)
        // 兼容 "周一"、"一"、"日"、"25日" 这类常见表头格式
        val dayRegex = Regex("一|二|三|四|五|六|日|天")
        val dayHeaderRegex = Regex("^(周|星期)?[一二三四五六日天]$")
        val dateHeaderRegex = Regex("^\\d{1,2}(日|号)?$")
        val headerCandidateTop = imageTop + (contentHeight * 0.25f).toInt()
        val headerCandidates = blocks.filter { it.boundingBox.centerY <= headerCandidateTop }
        val headerBlocks = headerCandidates.filter { block ->
            val text = block.text.trim()
            dayHeaderRegex.matches(text) ||
                dateHeaderRegex.matches(text) ||
                ((text.contains("周") || text.contains("星期")) && dayRegex.containsMatchIn(text))
        }.sortedBy { it.boundingBox.centerX }
        val headerBottom = headerBlocks.maxOfOrNull { it.boundingBox.bottom }

        val gridLeft = if (sectionBlocks.isNotEmpty()) {
            sectionBlocks.maxOf { it.boundingBox.right }.toFloat()
        } else {
            imageLeft + contentWidth * 0.12f
        }
        val gridRight = imageRight.toFloat()

        // 提取 7 列的中心点 X 坐标
        val colCenters = if (headerBlocks.size >= 5) {
            val rawCenters = headerBlocks.map { it.boundingBox.centerX }.sorted()
            val mergedCenters = mergeColumnCenters(rawCenters, contentWidth)
            if (mergedCenters.size == 7) {
                mergedCenters.toMutableList()
            } else {
                fallbackColumnCenters(gridLeft, gridRight)
            }
        } else {
            fallbackColumnCenters(gridLeft, gridRight)
        }

        // 如果没有节次轴，且检测到了表头，则尝试剔除表头行对行聚类的干扰
        if (sectionBlocks.isEmpty() && rowCenters.size > 1 && headerBottom != null) {
            val rowAverage = estimateRowAverage(rowCenters)
            val filteredCenters = rowCenters.filter { it > headerBottom + rowAverage * 0.35f }
            rowCenters.clear()
            rowCenters.addAll(if (filteredCenters.size >= 3) filteredCenters else rowCenters)
        }

        // 3. 匹配映射 (将非表头、非时间轴的业务文本映射到网格中)
        var contentBlocks = blocks - headerBlocks.toSet() - sectionBlocks.toSet()
        
        // 过滤掉明显的非课表内容 (如未裁剪时顶部的标题、底部的导航栏等)
        // 规则 1: 空间位置过滤。
        // 顶部过滤：如果找到了表头，那么在表头上方的内容 (如 "我的课表", 学期等) 应当被过滤
        if (headerBlocks.isNotEmpty()) {
            val topBoundary = headerBlocks.minOf { it.boundingBox.top }
            contentBlocks = contentBlocks.filter { it.boundingBox.centerY >= topBoundary }
            val rowAverage = estimateRowAverage(rowCenters)
            if (headerBottom != null) {
                val headerExclusionBottom = headerBottom + rowAverage * 0.4f
                contentBlocks = contentBlocks.filter { it.boundingBox.centerY > headerExclusionBottom }
            }
        }
        
        // 底部过滤：如果找到了时间轴，那么在最后一个节次下方很远的内容也应当被过滤
        if (rowCenters.size > 1) {
            val avgRowHeight = (rowCenters.last() - rowCenters.first()) / (rowCenters.size - 1)
            val bottomBoundary = rowCenters.last() + avgRowHeight * 1.5 // 允许最后一节课有一定的高度
            contentBlocks = contentBlocks.filter { it.boundingBox.centerY <= bottomBoundary }
        }
        
        // 规则 2: 关键词过滤。过滤掉常见的 UI 提示文本和底部导航文本
        val ignoreKeywords = listOf("我的课表", "学期", "添加备注", "空白格子", "无课教室", "首页", "消息", "我", "设置", "我的", "发现", "课程表")
        contentBlocks = contentBlocks.filter { block ->
            !ignoreKeywords.any { keyword -> block.text.contains(keyword) }
        }

        // 规则 3: 过滤顶部的日期/星期短文本，避免被当成课程名
        if (headerBottom != null) {
            val rowAverage = estimateRowAverage(rowCenters)
            val headerLineBottom = headerBottom + rowAverage * 0.4f
            contentBlocks = contentBlocks.filter { block ->
                val text = block.text.trim()
                val isShortHeader = dayHeaderRegex.matches(text) || dateHeaderRegex.matches(text)
                !(isShortHeader && block.boundingBox.centerY <= headerLineBottom)
            }
        }
        
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

    /**
     * 合并表头列中心点，避免同一列出现多条重复表头（如日期行 + 星期行）
     *
     * @param rawCenters 按 X 排序的原始中心点列表
     * @param contentWidth 图片内容宽度
     */
    private fun mergeColumnCenters(rawCenters: List<Int>, contentWidth: Int): List<Int> {
        if (rawCenters.isEmpty()) return emptyList()
        val mergeThreshold = (contentWidth * 0.06f).roundToInt().coerceAtLeast(12)
        val groups = mutableListOf<MutableList<Int>>()
        for (center in rawCenters) {
            if (groups.isEmpty()) {
                groups.add(mutableListOf(center))
                continue
            }
            val lastGroup = groups.last()
            val lastAvg = lastGroup.average().toFloat()
            if (abs(center - lastAvg) <= mergeThreshold) {
                lastGroup.add(center)
            } else {
                groups.add(mutableListOf(center))
            }
        }

        val merged = groups.map { it.average().roundToInt() }.toMutableList()

        // 如果超过 7 列，按最近距离逐步合并到 7 列
        while (merged.size > 7) {
            var minIndex = 0
            var minGap = Int.MAX_VALUE
            for (i in 0 until merged.size - 1) {
                val gap = abs(merged[i + 1] - merged[i])
                if (gap < minGap) {
                    minGap = gap
                    minIndex = i
                }
            }
            val mergedValue = ((merged[minIndex] + merged[minIndex + 1]) / 2f).roundToInt()
            merged[minIndex] = mergedValue
            merged.removeAt(minIndex + 1)
        }

        return merged
    }

    /**
     * 兜底列中心点计算：基于网格左右边界等分为 7 列
     */
    private fun fallbackColumnCenters(gridLeft: Float, gridRight: Float): MutableList<Int> {
        val colCenters = mutableListOf<Int>()
        val width = (gridRight - gridLeft).coerceAtLeast(1f)
        val colWidth = width / 7f
        for (i in 0 until 7) {
            val center = gridLeft + colWidth * (i + 0.5f)
            colCenters.add(center.roundToInt())
        }
        return colCenters
    }

    /**
     * 估算行平均高度，用于顶部表头过滤与底部边界计算
     */
    private fun estimateRowAverage(rowCenters: List<Int>): Float {
        if (rowCenters.size <= 1) return 48f
        val sorted = rowCenters.sorted()
        val diffs = sorted.zipWithNext { a, b -> (b - a).coerceAtLeast(1) }
        val average = diffs.average().toFloat()
        return average.coerceAtLeast(36f)
    }

    /**
     * 解析节次数字
     *
     * 兼容 "1"、"12"、"第1节"、"1节" 等常见格式
     */
    private fun parseSectionNumber(raw: String): Int? {
        val text = raw.trim()
        val number = text
            .replace("第", "")
            .replace("节", "")
            .replace(" ", "")
        val value = number.toIntOrNull()
        return if (value != null && value in 1..20) value else null
    }
}
