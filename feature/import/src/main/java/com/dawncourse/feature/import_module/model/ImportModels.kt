package com.dawncourse.feature.import_module.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/**
 * 导入解析结果模型
 *
 * JS 脚本解析后返回的 JSON 结构应对应此数据类。
 * 用于中间层传输，最终会转换为 Core:Domain 中的 Course 实体。
 */
data class ImportResult(
    val courses: List<ParsedCourse>,
    val timetableJson: String? = null,
    val error: String? = null
)

/**
 * 解析后的课程实体 (DTO)
 */
data class ParsedCourse(
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int, // 1-7
    val startSection: Int,
    val duration: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int // 0=All, 1=Odd, 2=Even
) {
    val endSection: Int
        get() = startSection + duration - 1
}

/**
 * 小爱课程表标准课程结构
 *
 * 对应 Provider/Parser/Timer 规范中的课程字段。
 */
data class XiaoaiCourse(
    val name: String,
    val teacher: String,
    val position: String,
    val day: Int,
    val weeks: List<Int>,
    val sections: List<Int>
)

/**
 * 小爱课程表 Provider 结果结构
 *
 * provider 返回的 JSON 字符串将转换为此结构。
 */
data class XiaoaiProviderResult(
    val courses: List<XiaoaiCourse>,
    val timetableJson: String?
)

/**
 * 周次区间数据
 *
 * 用于将离散周次数组拆分为可导入的区间。
 */
data class WeekRange(
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int
)

/**
 * 节次区间数据
 *
 * 用于将离散节次数组拆分为连续节次区间。
 */
data class SectionRange(
    val startSection: Int,
    val duration: Int
)

data class IcsEvent(
    val summary: String,
    val location: String,
    val description: String,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val rrule: String?,
    val rdates: List<LocalDateTime>,
    val exdates: List<LocalDateTime>
)

fun parseIcsToParsedCourses(raw: String): List<ParsedCourse> {
    val events = parseIcsEvents(raw)
    if (events.isEmpty()) return emptyList()
    
    // 找出最早的日期作为基准周的开始
    // 这里假设所有课程都在同一个学期内，取最早的一个作为开学第一周的参考
    // 注意：有些日历可能会导出几年前的数据，这里应该过滤一下异常值，但暂时假设用户导入的是当前学期的 ICS
    val allDates = events.flatMap { event ->
        expandIcsOccurrences(event)
    }.map { it.toLocalDate() }
    
    if (allDates.isEmpty()) return emptyList()
    
    // 找到所有事件中最早的一天，并定位到该周的周一作为 "基准周" (Week 1) 的开始
    val baseWeekStart = allDates.minOrNull()
        ?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ?: return emptyList()
        
    val parsedCourses = mutableListOf<ParsedCourse>()
    
    for (event in events) {
        val occurrences = expandIcsOccurrences(event)
        if (occurrences.isEmpty()) continue
        
        // 按星期几分组处理，因为同一个 RRULE 可能包含多个 BYDAY (e.g. MO,WE)
        // 它们在逻辑上应该是分开的课程记录
        val occurrencesByDay = occurrences.groupBy { it.dayOfWeek.value }
        
        val sectionRange = calculateSectionRange(event.start, event.end)
        
        // 尝试从 Summary 或 Description 中提取更多信息
        var teacher = extractTeacher(event.description)
        var name = unescapeIcsText(event.summary)
        
        // 如果没有提取到老师，尝试从 Summary 分割 (e.g. "高等数学 - 张三")
        if (teacher.isBlank() && name.contains("-")) {
            val parts = name.split("-")
            if (parts.size >= 2) {
                name = parts[0].trim()
                teacher = parts[1].trim()
            }
        } else if (teacher.isBlank() && name.contains(" ")) {
             // 尝试空格分割 (e.g. "高等数学 张三")
             val parts = name.split(" ")
             if (parts.size >= 2) {
                 // 简单的启发式：假设后面较短的是名字
                 val last = parts.last()
                 if (last.length in 2..4) {
                     teacher = last
                     name = parts.dropLast(1).joinToString(" ")
                 }
             }
        }
        
        for ((dayOfWeek, dayOccurrences) in occurrencesByDay) {
            // 计算这些发生日期对应的周次 (相对于 baseWeekStart)
            val weeks = dayOccurrences.map { occurrence ->
                val weekStart = occurrence.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                // 计算相差的周数 + 1
                (ChronoUnit.WEEKS.between(baseWeekStart, weekStart) + 1).toInt()
            }.distinct().sorted().filter { it > 0 } // 过滤掉负数周次（如果有的话）
            
            if (weeks.isEmpty()) continue
            
            // 将离散的周次合并为区间 (e.g. 1-16, 1-15单)
            val weekRanges = splitWeeks(weeks)
            
            for (weekRange in weekRanges) {
                parsedCourses.add(
                    ParsedCourse(
                        name = name,
                        teacher = teacher,
                        location = unescapeIcsText(event.location),
                        dayOfWeek = dayOfWeek,
                        startSection = sectionRange.startSection,
                        duration = sectionRange.duration,
                        startWeek = weekRange.startWeek,
                        endWeek = weekRange.endWeek,
                        weekType = weekRange.weekType
                    )
                )
            }
        }
    }
    return parsedCourses
}

/**
 * 解析小爱 Provider 返回结果
 *
 * 同时兼容以下格式：
 * 1) { courses: [...], timetable: {...} }
 * 2) { courseInfos: [...] }
 * 3) 直接课程数组 [...]
 */
fun parseXiaoaiProviderResult(raw: String): XiaoaiProviderResult {
    val trimmed = raw.trim()
    if (trimmed == "do not continue") {
        return XiaoaiProviderResult(emptyList(), null)
    }
    val courses = mutableListOf<XiaoaiCourse>()
    var timetableJson: String? = null
    try {
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            courses.addAll(parseXiaoaiCourses(array))
        } else {
            val obj = org.json.JSONObject(trimmed)
            timetableJson = obj.optJSONObject("timetable")?.toString()
                ?: obj.optString("timetable").takeIf { it.isNotBlank() }
            val courseArray = when {
                obj.has("courses") -> obj.optJSONArray("courses")
                obj.has("courseInfos") -> obj.optJSONArray("courseInfos")
                else -> null
            }
            if (courseArray != null) {
                courses.addAll(parseXiaoaiCourses(courseArray))
            }
        }
    } catch (e: Exception) {
        return XiaoaiProviderResult(emptyList(), null)
    }
    return XiaoaiProviderResult(courses, timetableJson)
}

fun parseParsedCoursesFromRaw(raw: String): List<ParsedCourse> {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed == "do not continue") return emptyList()
    return try {
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            parseParsedCourseArray(array)
        } else {
            val obj = org.json.JSONObject(trimmed)
            val courseArray = obj.optJSONArray("courses") ?: org.json.JSONArray()
            parseParsedCourseArray(courseArray)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun parseParsedCourseArray(array: org.json.JSONArray): List<ParsedCourse> {
    val list = mutableListOf<ParsedCourse>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val name = item.optString("name")
        val teacher = item.optString("teacher")
        val location = item.optString("location")
        val dayOfWeek = item.optInt("dayOfWeek", -1)
        val startSection = item.optInt("startSection", -1)
        val duration = item.optInt("duration", -1)
        val startWeek = item.optInt("startWeek", -1)
        val endWeek = item.optInt("endWeek", -1)
        val weekType = item.optInt("weekType", 0)
        if (name.isBlank() || dayOfWeek <= 0 || startSection <= 0 || duration <= 0 || startWeek <= 0 || endWeek <= 0) {
            continue
        }
        list.add(
            ParsedCourse(
                name = name,
                teacher = teacher,
                location = location,
                dayOfWeek = dayOfWeek,
                startSection = startSection,
                duration = duration,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType
            )
        )
    }
    return list
}

/**
 * 将小爱课程结构转换为 Dawn Course 的导入课程
 *
 * 同时兼容离散周次/节次，必要时拆分为多个可导入课程。
 */
fun convertXiaoaiCoursesToParsedCourses(courses: List<XiaoaiCourse>): List<ParsedCourse> {
    val parsed = mutableListOf<ParsedCourse>()
    for (course in courses) {
        val weekRanges = splitWeeks(course.weeks)
        val sectionRanges = splitSections(course.sections)
        for (weekRange in weekRanges) {
            for (sectionRange in sectionRanges) {
                parsed.add(
                    ParsedCourse(
                        name = course.name,
                        teacher = course.teacher,
                        location = course.position,
                        dayOfWeek = course.day,
                        startSection = sectionRange.startSection,
                        duration = sectionRange.duration,
                        startWeek = weekRange.startWeek,
                        endWeek = weekRange.endWeek,
                        weekType = weekRange.weekType
                    )
                )
            }
        }
    }
    return parsed
}

/**
 * 将 ParsedCourse 转换为领域层 Course
 *
 * 保持导入字段一致，便于直接入库。
 */
fun ParsedCourse.toDomainCourse(): com.dawncourse.core.domain.model.Course {
    return com.dawncourse.core.domain.model.Course(
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = duration,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType
    )
}

/**
 * 解析课程数组
 *
 * 兼容 sections 为 number[] 或 {section}[] 的情况。
 */
private fun parseXiaoaiCourses(array: org.json.JSONArray): List<XiaoaiCourse> {
    val list = mutableListOf<XiaoaiCourse>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val name = obj.optString("name")
        val teacher = obj.optString("teacher")
        val position = obj.optString("position")
        val day = obj.optInt("day", obj.optInt("dayOfWeek", 0))
        val weeks = parseIntArray(obj.optJSONArray("weeks"))
        val sections = parseSectionsArray(obj.optJSONArray("sections"))
        if (day <= 0 || weeks.isEmpty() || sections.isEmpty()) continue
        list.add(
            XiaoaiCourse(
                name = name,
                teacher = teacher,
                position = position,
                day = day,
                weeks = weeks,
                sections = sections
            )
        )
    }
    return list
}

/**
 * 解析 int 数组
 *
 * 用于 weeks 数组解析。
 */
private fun parseIntArray(array: org.json.JSONArray?): List<Int> {
    if (array == null) return emptyList()
    val list = mutableListOf<Int>()
    for (i in 0 until array.length()) {
        val value = array.optInt(i, -1)
        if (value > 0) list.add(value)
    }
    return list
}

/**
 * 解析节次数组
 *
 * 兼容 number[] 与 {section}[]。
 */
private fun parseSectionsArray(array: org.json.JSONArray?): List<Int> {
    if (array == null) return emptyList()
    val list = mutableListOf<Int>()
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        when (item) {
            is Number -> list.add(item.toInt())
            is org.json.JSONObject -> {
                val section = item.optInt("section", -1)
                if (section > 0) list.add(section)
            }
        }
    }
    return list
}

/**
 * 拆分周次为区间列表
 *
 * 支持全周、单周、双周三种模式。
 */
private fun splitWeeks(weeks: List<Int>): List<WeekRange> {
    if (weeks.isEmpty()) return emptyList()
    val sorted = weeks.distinct().sorted()
    val ranges = mutableListOf<WeekRange>()
    var start = sorted.first()
    var prev = start
    var step = 0
    for (index in 1 until sorted.size) {
        val current = sorted[index]
        val diff = current - prev
        if (step == 0) {
            step = diff
        }
        val isValidStep = (step == 1 || step == 2) && diff == step
        if (!isValidStep) {
            ranges.add(buildWeekRange(start, prev, step))
            start = current
            prev = current
            step = 0
            continue
        }
        prev = current
    }
    ranges.add(buildWeekRange(start, prev, step))
    return ranges
}

/**
 * 构建周次区间
 *
 * step=2 时自动按奇偶周计算 weekType。
 */
private fun buildWeekRange(start: Int, end: Int, step: Int): WeekRange {
    val weekType = if (step == 2) {
        if (start % 2 == 1) 1 else 2
    } else {
        0
    }
    return WeekRange(startWeek = start, endWeek = end, weekType = weekType)
}

/**
 * 拆分节次为连续区间
 *
 * 不连续的节次会被拆分为多个区间。
 */
private fun splitSections(sections: List<Int>): List<SectionRange> {
    if (sections.isEmpty()) return emptyList()
    val sorted = sections.distinct().sorted()
    val ranges = mutableListOf<SectionRange>()
    var start = sorted.first()
    var prev = start
    for (index in 1 until sorted.size) {
        val current = sorted[index]
        if (current - prev != 1) {
            ranges.add(SectionRange(startSection = start, duration = prev - start + 1))
            start = current
        }
        prev = current
    }
    ranges.add(SectionRange(startSection = start, duration = prev - start + 1))
    return ranges
}

private fun parseIcsEvents(raw: String): List<IcsEvent> {
    val lines = unfoldIcsLines(raw)
    val events = mutableListOf<IcsEvent>()
    var current: MutableMap<String, MutableList<String>>? = null
    for (line in lines) {
        if (line.startsWith("BEGIN:VEVENT")) {
            current = mutableMapOf()
            continue
        }
        if (line.startsWith("END:VEVENT")) {
            val map = current
            if (map != null) {
                val event = buildIcsEvent(map)
                if (event != null) events.add(event)
            }
            current = null
            continue
        }
        val map = current ?: continue
        
        // 兼容带参数的 key，例如 DTSTART;TZID=Asia/Shanghai:20230904T080000
        val parts = line.split(":", limit = 2)
        if (parts.size < 2) continue
        
        // 只取 key 的第一部分作为主键 (忽略参数)
        val fullKey = parts[0]
        val key = fullKey.substringBefore(";").uppercase()
        val value = parts[1]
        
        // 特殊处理：如果是 RDATE 或 EXDATE，可能有多个值，或者多行
        // 这里简化处理，直接存入 list
        map.getOrPut(key) { mutableListOf() }.add(value)
    }
    return events
}

private fun buildIcsEvent(map: Map<String, List<String>>): IcsEvent? {
    val dtStartRaw = map["DTSTART"]?.firstOrNull() ?: return null
    val start = parseIcsDateTime(dtStartRaw) ?: return null
    val end = map["DTEND"]?.firstOrNull()?.let { parseIcsDateTime(it) }
    val rrule = map["RRULE"]?.firstOrNull()
    
    // RDATE 和 EXDATE 可能有多个，且每个可能包含多个逗号分隔的时间
    val rdates = map["RDATE"].orEmpty().flatMap { value ->
        value.split(",").mapNotNull { parseIcsDateTime(it.trim()) }
    }
    val exdates = map["EXDATE"].orEmpty().flatMap { value ->
        value.split(",").mapNotNull { parseIcsDateTime(it.trim()) }
    }
    
    return IcsEvent(
        summary = map["SUMMARY"]?.firstOrNull().orEmpty(),
        location = map["LOCATION"]?.firstOrNull().orEmpty(),
        description = map["DESCRIPTION"]?.firstOrNull().orEmpty(),
        start = start,
        end = end,
        rrule = rrule,
        rdates = rdates,
        exdates = exdates
    )
}

/**
 * 反折叠 ICS 行
 * 
 * ICS 规范：如果一行以空格或 tab 开头，则是上一行的续行。
 */
private fun unfoldIcsLines(raw: String): List<String> {
    val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
    val result = mutableListOf<String>()
    for (line in normalized.split("\n")) {
        if (line.isEmpty()) continue
        if (line.startsWith(" ") || line.startsWith("\t")) {
            val lastIndex = result.lastIndex
            if (lastIndex >= 0) {
                result[lastIndex] = result[lastIndex] + line.trimStart() // 移除开头的空格/tab
            }
        } else {
            result.add(line)
        }
    }
    return result
}

/**
 * 反转义 ICS 文本
 *
 * 替换 \\n, \\, \\;, \\\\ 等
 */
private fun unescapeIcsText(text: String): String {
    return text.replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

/**
 * 解析 ICS 时间字符串
 *
 * 支持格式：
 * 1. yyyyMMdd (全天 -> 00:00:00)
 * 2. yyyyMMddTHHmmss
 * 3. yyyyMMddTHHmmssZ (UTC -> 转 Local)
 * 4. yyyyMMddTHHmm
 */
private fun parseIcsDateTime(value: String): LocalDateTime? {
    val raw = value.trim()
    return try {
        when {
            // yyyyMMdd (8 chars)
            raw.length == 8 && raw.all { it.isDigit() } -> {
                val date = java.time.LocalDate.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd"))
                date.atStartOfDay()
            }
            // yyyyMMddTHHmmssZ (16 chars, ends with Z)
            raw.endsWith("Z") -> {
                // 暂时忽略时区，直接当做本地时间处理，或者转为 LocalTime
                // 严谨做法是 OffsetDateTime -> LocalDateTime (in system default zone)
                // 这里为了课程表的直观性，直接取年月日时分秒部分，假设用户就是指当地时间
                // (因为 OffsetDateTime.toLocalDateTime() 会保留原面值，不会自动加减时区)
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                LocalDateTime.parse(raw, formatter)
            }
            // yyyyMMddTHHmmss (15 chars)
            raw.length == 15 && raw.contains("T") -> {
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                LocalDateTime.parse(raw, formatter)
            }
             // yyyyMMddTHHmm (13 chars) - 部分非标准实现
            raw.length == 13 && raw.contains("T") -> {
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
                LocalDateTime.parse(raw, formatter)
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 展开重复规则，获取所有发生的时间点
 */
private fun expandIcsOccurrences(event: IcsEvent): List<LocalDateTime> {
    val occurrences = mutableListOf<LocalDateTime>()
    occurrences.add(event.start) // 初始时间总是一个发生点
    occurrences.addAll(event.rdates) // 额外的具体日期
    
    val rule = event.rrule ?: return filterExDates(occurrences, event.exdates)
    
    val ruleParts = rule.split(";").associate {
        val pair = it.split("=")
        pair[0].uppercase() to pair.getOrElse(1) { "" }
    }
    
    // 目前仅严谨支持 WEEKLY，其他频率暂按单次处理 (或依赖 RDATE)
    if (ruleParts["FREQ"]?.uppercase() != "WEEKLY") {
        return filterExDates(occurrences, event.exdates)
    }
    
    val interval = ruleParts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val count = ruleParts["COUNT"]?.toIntOrNull()
    val until = ruleParts["UNTIL"]?.let { parseIcsDateTime(it) } // UNTIL 是 UTC 或 Local
    
    // 解析 BYDAY (MO, TU, WE...)
    val byDayRaw = ruleParts["BYDAY"]
    val byDays = if (!byDayRaw.isNullOrBlank()) {
        byDayRaw.split(",").mapNotNull { parseIcsByDay(it) }
    } else {
        listOf(event.start.dayOfWeek) // 默认是开始日期的星期
    }
    
    val startDate = event.start.toLocalDate()
    val startTime = event.start.toLocalTime()
    
    // 寻找当前周的周一作为锚点
    val weekStart = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    
    // 限制最大生成数量，防止死循环
    val maxCount = count ?: 100 // 如果没有 COUNT 也没有 UNTIL，限制 100 次 (约 2 年)
    val maxWeeks = 52 // 限制最多扫描 52 周
    
    var generatedCount = 0 // 已生成的重复项数量 (不含初始项，因为初始项已经添加了)
    // 修正：初始项是否算在 COUNT 里？RFC 5545 说 COUNT 包括初始项。
    // 所以这里 generatedCount 应该从 1 开始算 (如果初始项符合规则)
    // 简单起见，我们重新生成所有符合规则的项，然后去重
    occurrences.clear() 
    // 重新添加 RDATE
    occurrences.addAll(event.rdates)
    
    // 遍历周
    for (i in 0 until maxWeeks) {
        val currentWeekStart = weekStart.plusWeeks((i * interval).toLong())
        
        for (day in byDays) {
            // 计算当前周的 目标星期几
            // 周一 + (day.value - 1) 天
            val targetDate = currentWeekStart.plusDays((day.value - 1).toLong())
            val targetDateTime = LocalDateTime.of(targetDate, startTime)
            
            // 规则：必须在 DTSTART 之后 (或相等)
            if (targetDateTime.isBefore(event.start)) continue
            
            // 规则：必须在 UNTIL 之前 (或相等)
            if (until != null) {
                // 优化：如果 UNTIL 是 00:00:00，则将其视为当天的 23:59:59 (即包含当天所有课程)
                // 这是为了解决时区差异 (UTC 00:00) 导致本地课程 (e.g. 08:00) 被误过滤的问题
                val adjustedUntil = if (until.hour == 0 && until.minute == 0 && until.second == 0) {
                    until.withHour(23).withMinute(59).withSecond(59)
                } else {
                    until
                }
                
                if (targetDateTime.isAfter(adjustedUntil)) {
                    return filterExDates(occurrences, event.exdates)
                }
            }
            
            occurrences.add(targetDateTime)
            
            // 规则：COUNT 限制
            if (count != null && occurrences.distinct().size >= count) {
                return filterExDates(occurrences, event.exdates)
            }
        } 
    }
    
    return filterExDates(occurrences, event.exdates)
}

private fun filterExDates(occurrences: MutableList<LocalDateTime>, exdates: List<LocalDateTime>): List<LocalDateTime> {
    return occurrences.filterNot { occ -> 
        exdates.any { ex -> ex.isEqual(occ) } 
    }.distinct().sorted()
}

private fun parseIcsByDay(value: String): DayOfWeek? {
    // BYDAY=2MO (第二个周一) 这种暂不支持，只支持标准 MO, TU...
    // 只要取最后两个字符即可
    val code = value.trim().takeLast(2).uppercase()
    return when (code) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }
}

private fun calculateSectionRange(
    start: LocalDateTime,
    end: LocalDateTime?
): SectionRange {
    val startMinutes = start.hour * 60 + start.minute
    
    // 更加通用的节次推断逻辑
    // 假设常见的上课时间段
    // 08:00 - 12:00 -> 1-4 节 (每节 45-50 分钟)
    // 14:00 - 18:00 -> 5-8 节
    // 19:00 - 22:00 -> 9-12 节
    
    val (baseMinutes, baseSlot) = when {
        start.hour < 13 -> (8 * 60) to 1  // 上午课，基准 8:00 是第 1 节
        start.hour < 18 -> (14 * 60) to 5 // 下午课，基准 14:00 是第 5 节
        else -> (19 * 60) to 9            // 晚课，基准 19:00 是第 9 节
    }

    val diff = startMinutes - baseMinutes
    // 粗略估算：每 45-55 分钟算一节课的偏移
    // 比如 8:00 -> diff 0 -> offset 0
    // 8:50 -> diff 50 -> offset 1
    // 10:00 -> diff 120 -> offset 2
    val offset = (diff / 45).coerceAtLeast(0)
    val sectionIndex = baseSlot + offset

    val duration = if (end != null) {
        val endMinutes = end.hour * 60 + end.minute + if (end.toLocalDate().isAfter(start.toLocalDate())) 24 * 60 else 0
        val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(1)
        // 向上取整，每 45 分钟算一节
        ceil(durationMinutes / 40.0).toInt().coerceAtLeast(1)
    } else {
        1
    }
    return SectionRange(startSection = sectionIndex, duration = duration)
}

private fun extractTeacher(description: String): String {
    if (description.isBlank()) return ""
    val unescaped = unescapeIcsText(description)
    val lines = unescaped.split("\n")
    
    // 常见的包含老师信息的关键字
    val keywords = listOf("老师", "教师", "授课", "任课", "Teacher", "Instructor")
    
    for (line in lines) {
        val trimmed = line.trim()
        if (keywords.any { trimmed.contains(it, ignoreCase = true) }) {
            var name = trimmed
            keywords.forEach { k -> 
                name = name.replace(k, "", ignoreCase = true)
            }
            name = name.replace(":", "")
                .replace("：", "")
                .trim()
            
            // 使用正则去除 (xxx)、[xxx]、(xxx) 等后缀
            val regex = Regex("[\\(\\[（].*?[\\)\\]）]")
            name = regex.replace(name, "")
            
            return name.trim()
        }
    }
    
    // 如果没有关键字，尝试找一些很短的行，且不是 URL，不是地点
    // 这是一个弱 heuristic，暂时不启用，以免误判
    return ""
}
