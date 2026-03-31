package com.dawncourse.feature.import_module.engine.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

        val mergedBlocks = mergeNearbyBlocks(blocks)
        if (mergedBlocks.isEmpty()) return emptyList()

        val imageLeft = mergedBlocks.minOf { it.boundingBox.left }
        val imageRight = mergedBlocks.maxOf { it.boundingBox.right }
        val imageTop = mergedBlocks.minOf { it.boundingBox.top }
        val imageBottom = mergedBlocks.maxOf { it.boundingBox.bottom }
        val contentWidth = imageRight - imageLeft
        val contentHeight = imageBottom - imageTop

        // 1. 寻找时间轴基准 (Y轴划分：第1节到第12节)
        // 通常时间轴在最左侧（前 15% 宽度区域），且文本为纯数字
        val leftBoundary = imageLeft + (contentWidth * 0.15).toInt()
        val sectionBlocks = mergedBlocks.filter { block ->
            block.boundingBox.centerX < leftBoundary && parseSectionNumber(block.text) != null
        }.sortedBy { it.boundingBox.centerY }

        val rowCenters = mutableListOf<Int>()
        if (sectionBlocks.isNotEmpty()) {
            rowCenters.addAll(sectionBlocks.map { it.boundingBox.centerY })
        } else {
            // 虚拟补线：基于所有文本行的 Y 坐标进行一维聚类
            val sortedByY = mergedBlocks.sortedBy { it.boundingBox.centerY }
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
        val headerCandidates = mergedBlocks.filter { block ->
            isHeaderText(block.text, dayRegex, dayHeaderRegex, dateHeaderRegex)
        }
        val headerRow = findHeaderRow(headerCandidates, imageTop, contentHeight)
        val headerBlocks = headerRow?.blocks?.sortedBy { it.boundingBox.centerX } ?: emptyList()
        val headerBottom = headerRow?.bottom

        val gridLeft = if (sectionBlocks.isNotEmpty()) {
            sectionBlocks.maxOf { it.boundingBox.right }.toFloat()
        } else {
            val bandLeftCandidates = mergedBlocks.filter { block ->
                block.boundingBox.centerY in (imageTop + contentHeight * 0.2f).toInt()..(imageBottom - contentHeight * 0.15f).toInt()
            }
            bandLeftCandidates.minOfOrNull { it.boundingBox.left }?.toFloat() ?: (imageLeft + contentWidth * 0.12f)
        }
        val bandRightCandidates = mergedBlocks.filter { block ->
            block.boundingBox.centerY in (imageTop + contentHeight * 0.2f).toInt()..(imageBottom - contentHeight * 0.15f).toInt()
        }
        val gridRight = bandRightCandidates.maxOfOrNull { it.boundingBox.right }?.toFloat() ?: imageRight.toFloat()

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
            if (filteredCenters.size >= 3) {
                rowCenters.clear()
                rowCenters.addAll(filteredCenters)
            }
        }

        // 3. 匹配映射 (将非表头、非时间轴的业务文本映射到网格中)
        var contentBlocks = mergedBlocks - headerBlocks.toSet() - sectionBlocks.toSet()
        
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
        val headerTopLimit = imageTop + (contentHeight * 0.35f).toInt()
        contentBlocks = contentBlocks.filter { block ->
            val isHeaderLike = isHeaderText(block.text, dayRegex, dayHeaderRegex, dateHeaderRegex)
            !(isHeaderLike && block.boundingBox.centerY <= headerTopLimit)
        }
        
        val cellMap = mutableMapOf<Pair<Int, Int>, MutableList<TextBlock>>()

        val colBounds = buildBounds(colCenters, gridLeft.roundToInt(), gridRight.roundToInt())
        val rowBounds = buildBounds(rowCenters, imageTop, imageBottom)
        for (block in contentBlocks) {
            val colIndex = findIndexByOverlapX(block.boundingBox, colBounds)
            val rowIndex = findIndexByOverlapY(block.boundingBox, rowBounds)
            if (colIndex >= 0 && rowIndex >= 0) {
                val dayOfWeek = colIndex + 1
                val section = rowIndex + 1
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
        val mergeThreshold = (contentWidth * 0.08f).roundToInt().coerceAtLeast(15)
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
        
        // 确保至少有 5 列
        if (merged.size < 5) {
            return emptyList()
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
        if (diffs.isEmpty()) return 48f
        val average = diffs.average().toFloat()
        return average.coerceAtLeast(36f)
    }

    private fun mergeNearbyBlocks(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy<TextBlock>({ it.boundingBox.centerY }, { it.boundingBox.centerX }))
        val merged = mutableListOf<TextBlock>()

        for (block in sorted) {
            val candidateIndex = merged.indexOfLast { existing ->
                val xThreshold = max(existing.boundingBox.width, block.boundingBox.width) * 0.6f
                val yThreshold = max(existing.boundingBox.height, block.boundingBox.height) * 1.2f
                val xClose = abs(existing.boundingBox.centerX - block.boundingBox.centerX) <= xThreshold
                val verticalGap = block.boundingBox.top - existing.boundingBox.bottom
                val overlapY = min(existing.boundingBox.bottom, block.boundingBox.bottom) - max(existing.boundingBox.top, block.boundingBox.top)
                xClose && (verticalGap in 0..yThreshold.toInt() || overlapY >= 0)
            }

            if (candidateIndex >= 0) {
                val existing = merged[candidateIndex]
                val mergedBox = OcrBoundingBox(
                    left = min(existing.boundingBox.left, block.boundingBox.left),
                    top = min(existing.boundingBox.top, block.boundingBox.top),
                    right = max(existing.boundingBox.right, block.boundingBox.right),
                    bottom = max(existing.boundingBox.bottom, block.boundingBox.bottom)
                )
                val isVertical = abs(block.boundingBox.centerY - existing.boundingBox.centerY) > existing.boundingBox.height * 0.5f
                val separator = if (isVertical) "\n" else " "
                val mergedText = if (block.boundingBox.top >= existing.boundingBox.top || (!isVertical && block.boundingBox.left >= existing.boundingBox.left)) {
                    "${existing.text}$separator${block.text}"
                } else {
                    "${block.text}$separator${existing.text}"
                }
                merged[candidateIndex] = existing.copy(text = mergedText, boundingBox = mergedBox)
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    private data class Bound(val start: Int, val end: Int)

    private fun buildBounds(centers: List<Int>, minBound: Int, maxBound: Int): List<Bound> {
        if (centers.isEmpty()) return emptyList()
        val sorted = centers.sorted()
        val bounds = mutableListOf<Bound>()
        for (i in sorted.indices) {
            val left = if (i == 0) {
                minBound
            } else {
                ((sorted[i - 1] + sorted[i]) / 2f).roundToInt()
            }
            val right = if (i == sorted.lastIndex) {
                maxBound
            } else {
                ((sorted[i] + sorted[i + 1]) / 2f).roundToInt()
            }
            bounds.add(Bound(left, right))
        }
        return bounds
    }

    private fun findIndexByOverlapX(box: OcrBoundingBox, bounds: List<Bound>): Int {
        if (bounds.isEmpty()) return -1
        var bestIndex = -1
        var bestScore = 0f
        val width = box.width.coerceAtLeast(1)
        val centerX = box.centerX
        for (i in bounds.indices) {
            val bound = bounds[i]
            val overlapX = max(0, min(box.right, bound.end) - max(box.left, bound.start))
            val overlapRatioX = overlapX.toFloat() / width
            // 结合重叠比例和中心点距离计算综合分数
            val centerDistance = abs(centerX - (bound.start + bound.end) / 2)
            val distanceScore = 1.0f - min(centerDistance.toFloat() / (bound.end - bound.start), 1.0f)
            val totalScore = overlapRatioX * 0.7f + distanceScore * 0.3f
            if (totalScore < 0.4f) continue
            if (totalScore > bestScore || (totalScore == bestScore && bestIndex >= 0 && i < bestIndex)) {
                bestScore = totalScore
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun findIndexByOverlapY(box: OcrBoundingBox, bounds: List<Bound>): Int {
        if (bounds.isEmpty()) return -1
        var bestIndex = -1
        var bestScore = 0f
        val height = box.height.coerceAtLeast(1)
        val centerY = box.centerY
        for (i in bounds.indices) {
            val bound = bounds[i]
            val overlapY = max(0, min(box.bottom, bound.end) - max(box.top, bound.start))
            val overlapRatioY = overlapY.toFloat() / height
            // 结合重叠比例和中心点距离计算综合分数
            val centerDistance = abs(centerY - (bound.start + bound.end) / 2)
            val distanceScore = 1.0f - min(centerDistance.toFloat() / (bound.end - bound.start), 1.0f)
            val totalScore = overlapRatioY * 0.7f + distanceScore * 0.3f
            if (totalScore < 0.4f) continue
            if (totalScore > bestScore || (totalScore == bestScore && bestIndex >= 0 && i < bestIndex)) {
                bestScore = totalScore
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun isHeaderText(
        raw: String,
        dayRegex: Regex,
        dayHeaderRegex: Regex,
        dateHeaderRegex: Regex
    ): Boolean {
        val text = raw.trim()
        if (text.isBlank()) return false
        return dayHeaderRegex.matches(text) ||
            dateHeaderRegex.matches(text) ||
            ((text.contains("周") || text.contains("星期")) && dayRegex.containsMatchIn(text)) ||
            // 支持更多表头格式
            text.matches(Regex("^[一二三四五六日天][一二三四五六日天]?$")) ||
            text.matches(Regex("^周[一二三四五六日天]$")) ||
            text.matches(Regex("^星期[一二三四五六日天]$"))
    }

    private data class HeaderRow(val blocks: List<TextBlock>, val bottom: Int)

    private fun findHeaderRow(
        candidates: List<TextBlock>,
        imageTop: Int,
        contentHeight: Int
    ): HeaderRow? {
        if (candidates.isEmpty()) return null
        val topLimit = imageTop + (contentHeight * 0.45f).toInt()
        val filtered = candidates.filter { it.boundingBox.centerY <= topLimit }
        if (filtered.isEmpty()) return null
        val heightAverage = filtered.map { it.boundingBox.height }.average().toFloat().coerceAtLeast(12f)
        val threshold = (heightAverage * 1.5f).coerceAtLeast(15f)
        val sorted = filtered.sortedBy { it.boundingBox.centerY }
        val groups = mutableListOf<MutableList<TextBlock>>()
        var current = mutableListOf<TextBlock>()
        var currentY = sorted.first().boundingBox.centerY
        for (block in sorted) {
            if (kotlin.math.abs(block.boundingBox.centerY - currentY) <= threshold) {
                current.add(block)
            } else {
                groups.add(current)
                current = mutableListOf(block)
                currentY = block.boundingBox.centerY
            }
        }
        if (current.isNotEmpty()) {
            groups.add(current)
        }
        // 选择最合适的表头行：优先选择包含5个以上元素的行，其次选择位置最靠上的行
        val best = groups.filter { it.size >= 3 }.maxByOrNull { it.size } ?: groups.maxByOrNull { it.size }
        ?: return null
        val bottom = best.maxOf { it.boundingBox.bottom }
        return HeaderRow(best, bottom)
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
