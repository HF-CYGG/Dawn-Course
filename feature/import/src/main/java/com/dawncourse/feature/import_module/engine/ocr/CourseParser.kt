package com.dawncourse.feature.import_module.engine.ocr

import com.dawncourse.feature.import_module.model.ParsedCourse

/**
 * 语义解析器 (Course Parser)
 *
 * 核心职责：将单个网格 (Cell) 内的非结构化文本列表，通过模式识别和启发式规则，
 * 解析为结构化的 [ParsedCourse] 课程模型。
 * 包含简单的字典纠错和混合容错逻辑。
 */
class CourseParser {

    /**
     * 将网格单元格中的文本解析为结构化的课程数据
     *
     * @param cells 经过布局引擎处理后的单元格列表
     * @return 解析成功并封装好的课程列表
     */
    fun parse(cells: List<GridCell>): List<ParsedCourse> {
        val parsedCourses = mutableListOf<ParsedCourse>()

        // 常用周次正则：如 "1-16周", "1~16周", "第1-16周"
        val weekRegex = Regex("(\\d+)\\s*[\\-~_—一]\\s*(\\d+)\\s*周?")
        // 单双周正则
        val singleDoubleRegex = Regex("(单|双)周")
        // 地点正则：包含楼、室、馆、教等字眼，或字母+数字组合（如 A101）
        val locationRegex = Regex(".*(楼|室|馆|教|校区).*|[A-Z]+\\d+")
        // 教师正则：通常为 2-4 个中文字符，或包含“老师”字样
        val teacherRegex = Regex("^[\u4e00-\u9fa5]{2,4}$|.*老师.*")

        for (cell in cells) {
            if (cell.blocks.isEmpty()) continue

            val allLines = cell.blocks.map { block ->
                block.text.trim()
                    .replace("O", "0")
                    .replace("o", "0")
                    .replace("l", "1")
                    .replace("I", "1")
                    .replace("Z", "2")
                    .replace("z", "2")
                    .replace("S", "5")
                    .replace("s", "5")
                    .replace("B", "8")
                    .replace("q", "9")
            }.filter { it.isNotEmpty() }
            if (allLines.isEmpty()) continue

            val courseChunks = splitIntoCourseChunks(allLines, weekRegex)

            for (lines in courseChunks) {
                if (lines.isEmpty()) continue
                
                // 过滤掉只有一个月或日期的误识别块 (例如 "03月", "25日")
                if (lines.size == 1 && lines[0].matches(Regex("^\\d+月$|^\\d+日$"))) {
                    continue
                }
                
                // 过滤掉常见的非课程文本 (防兜底)
                val firstLine = lines[0]
                if (firstLine.contains("我的课表") || 
                    firstLine.matches(Regex(".*第.*周.*")) || 
                    firstLine.matches(Regex(".*学年.*学期.*")) ||
                    firstLine.matches(Regex("^\\d{1,2}:\\d{2}$")) || // 时间如 13:18
                    firstLine.matches(Regex("^\\d{1,3}%?$")) ||      // 电量或纯数字如 25, 100%
                    firstLine.matches(Regex("^[a-zA-Z0-9]{1,3}$"))   // 极短的无意义字母数字如 5G, KB
                ) {
                    continue
                }

                var name = lines[0]
                var teacher = ""
                var location = ""
                var startWeek = 1
                var endWeek = 16
                var weekType = 0 // 0=全周, 1=单周, 2=双周
                val duration = 2 // 默认认为占据 2 小节，后续可根据单元格跨度计算


                for (i in 1 until lines.size) {
                    val line = lines[i]

                    val weekMatch = weekRegex.find(line)
                    if (weekMatch != null) {
                        startWeek = weekMatch.groupValues[1].toIntOrNull() ?: 1
                        endWeek = weekMatch.groupValues[2].toIntOrNull() ?: 16
                        if (singleDoubleRegex.containsMatchIn(line)) {
                            weekType = if (line.contains("单")) 1 else 2
                        }
                        continue
                    }

                    if (locationRegex.matches(line) || (line.any { it.isLetter() } && line.any { it.isDigit() })) {
                        location = line
                        continue
                    }

                    if (teacherRegex.matches(line)) {
                        teacher = line.replace("老师", "")
                        continue
                    }
                
                    if (location.isEmpty() && line.length > 4) {
                        location = line
                    } else if (teacher.isEmpty() && line.length in 2..4) {
                        teacher = line
                    }
                }

                var confidence = 1.0f
                if (location.isEmpty() || teacher.isEmpty() || name.length < 2) {
                    confidence -= 0.3f
                }
                if (endWeek > 30 || startWeek > endWeek) {
                    confidence -= 0.4f
                    if (startWeek > endWeek) {
                        val temp = startWeek
                        startWeek = endWeek
                        endWeek = temp
                    }
                    if (endWeek > 30) endWeek = 20
                }
                confidence = confidence.coerceAtLeast(0.1f)

                if (name.matches(Regex("^\\d+$"))) continue

                parsedCourses.add(
                    ParsedCourse(
                        name = name,
                        teacher = teacher.ifEmpty { "未知教师" },
                        location = location.ifEmpty { "未知地点" },
                        dayOfWeek = cell.dayOfWeek,
                        startSection = cell.startSection,
                        duration = duration,
                        startWeek = startWeek,
                        endWeek = endWeek,
                        weekType = weekType
                    ).apply { this.confidence = confidence }
                )
            }
        }

        return parsedCourses
    }

    /**
     * 将单个单元格内的多行文本，根据明显的分隔特征（如空行、或者重新出现了周次/地点模式），
     * 拆分为多门可能的冲突课程文本块。
     */
    private fun splitIntoCourseChunks(lines: List<String>, weekRegex: Regex): List<List<String>> {
        val chunks = mutableListOf<MutableList<String>>()
        var currentChunk = mutableListOf<String>()
        
        for (i in lines.indices) {
            val line = lines[i]
            // 如果不是第一行，且当前行又出现了“课程名”的特征（例如，上一行已经是地点或周次，当前行又是一个没有数字和特殊符号的短词）
            // 或者明确遇到了分隔符（如 "---", "==="），就认为开始了一门新课
            val isSeparator = line.matches(Regex("[-=]{3,}"))
            
            // 简单的冲突启发式：如果当前 chunk 已经包含了周次信息，且新的一行又不是地点或教师（比如又出现了一个纯中文的短词），可能是新课
            val hasWeekAlready = currentChunk.any { weekRegex.containsMatchIn(it) }
            val looksLikeNewCourseName = line.length in 2..15 && line.all { it.isLetter() } && !line.contains("老师")
            
            if (isSeparator) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf()
                }
                continue
            } else if (hasWeekAlready && looksLikeNewCourseName && currentChunk.size >= 3) {
                // 认为是一个新课的开始
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
            }
            
            currentChunk.add(line)
        }
        
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }
        
        return chunks
    }
}
