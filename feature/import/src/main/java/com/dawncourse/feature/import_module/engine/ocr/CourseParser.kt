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
        val weekRegex = Regex("(\\d+)[\\-~](\\d+)周?")
        // 单双周正则
        val singleDoubleRegex = Regex("(单|双)周")
        // 地点正则：包含楼、室、馆、教等字眼，或字母+数字组合（如 A101）
        val locationRegex = Regex(".*(楼|室|馆|教|校区).*|[A-Z]+\\d+")
        // 教师正则：通常为 2-4 个中文字符，或包含“老师”字样
        val teacherRegex = Regex("^[\u4e00-\u9fa5]{2,4}$|.*老师.*")

        for (cell in cells) {
            if (cell.blocks.isEmpty()) continue

            // 将块内容按顺序合并为多行文本
            val lines = cell.blocks.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var name = ""
            var teacher = ""
            var location = ""
            var startWeek = 1
            var endWeek = 16
            var weekType = 0 // 0=全周, 1=单周, 2=双周
            val duration = 2 // 默认认为占据 2 小节，后续可根据单元格跨度计算

            // 启发式规则 1: 单元格的第一行极大概率是课程名
            name = lines[0]

            // 遍历剩余行进行字段提取
            for (i in 1 until lines.size) {
                var line = lines[i]
                
                // 简单的字典纠错：处理 OCR 常见数字字母混淆
                line = line.replace("O", "0").replace("l", "1").replace("I", "1")

                // 尝试匹配周次信息
                val weekMatch = weekRegex.find(line)
                if (weekMatch != null) {
                    startWeek = weekMatch.groupValues[1].toIntOrNull() ?: 1
                    endWeek = weekMatch.groupValues[2].toIntOrNull() ?: 16
                    
                    // 判断单双周
                    if (singleDoubleRegex.containsMatchIn(line)) {
                        weekType = if (line.contains("单")) 1 else 2
                    }
                    continue // 匹配成功后跳过当前行
                }

                // 尝试匹配上课地点
                if (locationRegex.matches(line) || (line.any { it.isLetter() } && line.any { it.isDigit() })) {
                    location = line
                    continue
                }

                // 尝试匹配教师姓名
                if (teacherRegex.matches(line)) {
                    teacher = line.replace("老师", "")
                    continue
                }
                
                // 如果未能匹配上述规则，且地点或教师为空，进行兜底分配
                if (location.isEmpty() && line.length > 4) {
                    location = line
                } else if (teacher.isEmpty() && line.length in 2..4) {
                    teacher = line
                }
            }

            parsedCourses.add(
                ParsedCourse(
                    name = name,
                    // 允许部分字段为空，交由强 UI 兜底（ReviewStep）供用户手动校验补充
                    teacher = teacher.ifEmpty { "未知教师" },
                    location = location.ifEmpty { "未知地点" },
                    dayOfWeek = cell.dayOfWeek,
                    startSection = cell.startSection,
                    duration = duration,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekType = weekType
                )
            )
        }

        return parsedCourses
    }
}
