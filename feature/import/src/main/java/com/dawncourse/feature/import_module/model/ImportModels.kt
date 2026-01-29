package com.dawncourse.feature.import_module.model

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
)

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
    val start: java.time.LocalDateTime,
    val end: java.time.LocalDateTime?,
    val rrule: String?,
    val rdates: List<java.time.LocalDateTime>,
    val exdates: List<java.time.LocalDateTime>
)

fun parseIcsToParsedCourses(raw: String): List<ParsedCourse> {
    val events = parseIcsEvents(raw)
    if (events.isEmpty()) return emptyList()
    val allDates = events.flatMap { event ->
        expandIcsOccurrences(event)
    }.map { it.toLocalDate() }
    if (allDates.isEmpty()) return emptyList()
    val baseWeekStart = allDates.minOrNull()!!.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val parsedCourses = mutableListOf<ParsedCourse>()
    for (event in events) {
        val occurrences = expandIcsOccurrences(event)
        if (occurrences.isEmpty()) continue
        val occurrencesByDay = occurrences.groupBy { it.dayOfWeek.value }
        val sectionRange = calculateSectionRange(event.start, event.end)
        for ((dayOfWeek, dayOccurrences) in occurrencesByDay) {
            val weeks = dayOccurrences.map { occurrence ->
                val weekStart = occurrence.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                (java.time.temporal.ChronoUnit.WEEKS.between(baseWeekStart, weekStart) + 1).toInt()
            }.distinct().sorted()
            val weekRanges = splitWeeks(weeks)
            for (weekRange in weekRanges) {
                parsedCourses.add(
                    ParsedCourse(
                        name = event.summary,
                        teacher = extractTeacher(event.description),
                        location = event.location,
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
        val parts = line.split(":", limit = 2)
        if (parts.size < 2) continue
        val key = parts[0].substringBefore(";").uppercase()
        val value = parts[1]
        map.getOrPut(key) { mutableListOf() }.add(value)
    }
    return events
}

private fun buildIcsEvent(map: Map<String, List<String>>): IcsEvent? {
    val dtStartRaw = map["DTSTART"]?.firstOrNull() ?: return null
    val start = parseIcsDateTime(dtStartRaw) ?: return null
    val end = map["DTEND"]?.firstOrNull()?.let { parseIcsDateTime(it) }
    val rrule = map["RRULE"]?.firstOrNull()
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

private fun unfoldIcsLines(raw: String): List<String> {
    val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
    val result = mutableListOf<String>()
    for (line in normalized.split("\n")) {
        if (line.startsWith(" ") || line.startsWith("\t")) {
            val lastIndex = result.lastIndex
            if (lastIndex >= 0) {
                result[lastIndex] = result[lastIndex] + line.trimStart()
            }
        } else if (line.isNotBlank()) {
            result.add(line.trimEnd())
        }
    }
    return result
}

private fun parseIcsDateTime(value: String): java.time.LocalDateTime? {
    val raw = value.trim()
    return when {
        raw.length == 8 -> {
            val date = java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
            date.atStartOfDay()
        }
        raw.endsWith("Z") -> {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            java.time.OffsetDateTime.parse(raw, formatter).toLocalDateTime()
        }
        raw.length >= 15 -> {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            java.time.LocalDateTime.parse(raw.substring(0, 15), formatter)
        }
        else -> null
    }
}

private fun expandIcsOccurrences(event: IcsEvent): List<java.time.LocalDateTime> {
    val occurrences = mutableListOf<java.time.LocalDateTime>()
    occurrences.add(event.start)
    occurrences.addAll(event.rdates)
    val rule = event.rrule ?: return occurrences.filterNot { it in event.exdates }.distinct().sorted()
    val ruleParts = rule.split(";").associate {
        val pair = it.split("=")
        pair[0].uppercase() to pair.getOrElse(1) { "" }
    }
    if (ruleParts["FREQ"]?.uppercase() != "WEEKLY") {
        return occurrences.filterNot { it in event.exdates }.distinct().sorted()
    }
    val interval = ruleParts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val count = ruleParts["COUNT"]?.toIntOrNull()
    val until = ruleParts["UNTIL"]?.let { parseIcsDateTime(it) }?.toLocalDate()
    val byDay = ruleParts["BYDAY"]?.split(",")?.mapNotNull { parseIcsByDay(it) }.orEmpty()
    val days = if (byDay.isEmpty()) listOf(event.start.dayOfWeek) else byDay
    val startDate = event.start.toLocalDate()
    val startTime = event.start.toLocalTime()
    val firstDates = days.map { day ->
        startDate.with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
    }
    val maxCount = count ?: 60
    var weekIndex = 0
    while (occurrences.size < maxCount && weekIndex < 200) {
        for (date in firstDates) {
            val occurrenceDate = date.plusWeeks((weekIndex * interval).toLong())
            if (until != null && occurrenceDate.isAfter(until)) continue
            occurrences.add(java.time.LocalDateTime.of(occurrenceDate, startTime))
            if (occurrences.size >= maxCount) break
        }
        if (until != null && firstDates.all { it.plusWeeks((weekIndex * interval).toLong()).isAfter(until) }) break
        weekIndex++
    }
    return occurrences.filterNot { it in event.exdates }.distinct().sorted()
}

private fun parseIcsByDay(value: String): java.time.DayOfWeek? {
    return when (value.trim().uppercase()) {
        "MO" -> java.time.DayOfWeek.MONDAY
        "TU" -> java.time.DayOfWeek.TUESDAY
        "WE" -> java.time.DayOfWeek.WEDNESDAY
        "TH" -> java.time.DayOfWeek.THURSDAY
        "FR" -> java.time.DayOfWeek.FRIDAY
        "SA" -> java.time.DayOfWeek.SATURDAY
        "SU" -> java.time.DayOfWeek.SUNDAY
        else -> null
    }
}

private fun calculateSectionRange(
    start: java.time.LocalDateTime,
    end: java.time.LocalDateTime?
): SectionRange {
    val baseMinutes = 8 * 60
    val startMinutes = start.hour * 60 + start.minute
    val sectionIndex = ((startMinutes - baseMinutes) / 60).coerceAtLeast(0) + 1
    val duration = if (end != null) {
        val endMinutes = end.hour * 60 + end.minute + if (end.toLocalDate().isAfter(start.toLocalDate())) 24 * 60 else 0
        val minutes = (endMinutes - startMinutes).coerceAtLeast(1)
        kotlin.math.ceil(minutes / 60.0).toInt().coerceAtLeast(1)
    } else {
        1
    }
    return SectionRange(startSection = sectionIndex, duration = duration)
}

private fun extractTeacher(description: String): String {
    if (description.isBlank()) return ""
    val lines = description.split("\\n", "\\\\n")
    val keywords = listOf("老师", "教师", "授课", "任课")
    for (line in lines) {
        val trimmed = line.trim()
        if (keywords.any { trimmed.contains(it) }) {
            return trimmed.replace("老师", "").replace("教师", "").replace("授课", "").replace("任课", "").trim()
        }
    }
    return ""
}
