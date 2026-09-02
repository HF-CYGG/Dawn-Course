package com.dawncourse.feature.widget

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotCourse
import com.dawncourse.core.domain.model.StartupSnapshotWeekType
import com.dawncourse.feature.widget.policy.WidgetTimelineBoundaryPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 将已解密且通过完整性校验的启动快照投影为 Widget 时间线。
 *
 * 该映射器只依赖领域快照、`java.time` 与纯边界策略，不访问 Room、Repository 或 Android API；
 * 数据库尚未 Ready 时可安全复用相同的 Widget 展示和刷新时间语义。
 */
class StartupSnapshotWidgetTimelineMapper {

    /** 根据快照所属时区从确定的当前时刻构造 Widget 时间线。 */
    fun map(snapshot: StartupSnapshot, nowMillis: Long): WidgetTimeline {
        val zoneId = ZoneId.of(snapshot.zoneId)
        val dateTime = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = dateTime.toLocalDate()
        val now = dateTime.toLocalTime()
        val sectionTimes = snapshot.visualSettings.sectionTimes.map { section ->
            SectionTime(startTime = section.startTime, endTime = section.endTime)
        }
        val semester = snapshot.semester
        if (semester == null) {
            return WidgetTimeline(
                profileId = snapshot.profile.id,
                displayCourses = emptyList(),
                today = today,
                currentWeek = 0,
                sectionTimes = sectionTimes,
                emptyMessage = "暂无当前学期",
                isBeforeSemesterStart = false,
                nextUpdateMillis = null,
                sourceCourseCount = 0,
            )
        }

        val semesterStart = Instant.ofEpochMilli(semester.startDateEpochMillis)
            .atZone(zoneId)
            .toLocalDate()
        val daysUntilSemesterStart = semesterStart
            .takeIf { startDate -> today.isBefore(startDate) }
            ?.let { startDate -> ChronoUnit.DAYS.between(today, startDate) }
        val isBeforeSemesterStart = daysUntilSemesterStart != null
        val currentWeek = if (isBeforeSemesterStart) {
            0
        } else {
            (ChronoUnit.DAYS.between(semesterStart, today) / DAYS_PER_WEEK).toInt() + 1
        }
        val isSemesterEnded = !isBeforeSemesterStart && currentWeek > semester.weekCount
        val sourceCourses = snapshot.courses.map { course -> course.toCourse(semester.id) }
        val todayCourses = if (isBeforeSemesterStart || isSemesterEnded) {
            emptyList()
        } else {
            filterCoursesForToday(sourceCourses, today, currentWeek)
        }
        val displayCourses = todayCourses.filter { course ->
            course.isCurrentOrFuture(sectionTimes, now)
        }
        val nextUpdateMillis = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = todayCourses,
            sectionTimes = sectionTimes,
            today = today,
            zoneId = zoneId,
            nowMillis = nowMillis,
        )

        return WidgetTimeline(
            profileId = snapshot.profile.id,
            displayCourses = displayCourses,
            today = today,
            currentWeek = currentWeek,
            sectionTimes = sectionTimes,
            emptyMessage = if (displayCourses.isNotEmpty()) {
                ""
            } else if (todayCourses.isNotEmpty()) {
                "今日课程已结束 🌙"
            } else {
                emptyMessage(
                    sourceCourses = sourceCourses,
                    currentWeek = currentWeek,
                    isBeforeSemesterStart = isBeforeSemesterStart,
                    isSemesterEnded = isSemesterEnded,
                    daysUntilSemesterStart = daysUntilSemesterStart,
                )
            },
            isBeforeSemesterStart = isBeforeSemesterStart,
            nextUpdateMillis = nextUpdateMillis,
            sourceCourseCount = sourceCourses.size,
        )
    }

    /** 只保留当天、当前周次和单双周规则均匹配的课程，并与实时 Widget 采用相同去重键。 */
    private fun filterCoursesForToday(
        courses: List<Course>,
        today: LocalDate,
        currentWeek: Int,
    ): List<Course> = courses
        .filter { course ->
            course.dayOfWeek == today.dayOfWeek.value &&
                currentWeek in course.startWeek..course.endWeek &&
                course.matchesWeekType(currentWeek)
        }
        .groupBy { course -> "${course.startSection}-${course.name}" }
        .mapNotNull { (_, candidates) ->
            candidates.maxByOrNull { course -> if (course.location.isNotBlank()) 1 else 0 }
        }
        .sortedBy(Course::startSection)

    /** 当前课程和还未结束的课程都应可见；节次时间缺失或不可读时保守展示。 */
    private fun Course.isCurrentOrFuture(sectionTimes: List<SectionTime>, now: LocalTime): Boolean {
        if (sectionTimes.isEmpty()) return true
        val endSectionIndex = startSection + duration - SECTION_INDEX_OFFSET
        val endTime = sectionTimes.getOrNull(endSectionIndex)?.endTime
            ?.let(::parseSectionTime)
            ?: return true
        return now.isBefore(endTime)
    }

    /** 空态必须区分没有学期、开学前、学期结束与当前周无课；无学期已在入口提前处理。 */
    private fun emptyMessage(
        sourceCourses: List<Course>,
        currentWeek: Int,
        isBeforeSemesterStart: Boolean,
        isSemesterEnded: Boolean,
        daysUntilSemesterStart: Long?,
    ): String {
        if (isBeforeSemesterStart) {
            return "距开学还有 ${daysUntilSemesterStart ?: 0L} 天"
        }
        if (isSemesterEnded) return "学期已结束 🎉"
        val hasCourseThisWeek = sourceCourses.any { course ->
            currentWeek in course.startWeek..course.endWeek && course.matchesWeekType(currentWeek)
        }
        return if (hasCourseThisWeek) {
            "今日已无课 ☕"
        } else if (sourceCourses.any { course -> course.endWeek > currentWeek }) {
            "本周无课 🌴"
        } else {
            "好好享受假期吧 🎉"
        }
    }

    /** 快照枚举不依赖 Room integer，但投影出的 Widget 仍复用领域 Course 周次常量。 */
    private fun StartupSnapshotCourse.toCourse(semesterId: Long): Course = Course(
        id = id,
        semesterId = semesterId,
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = duration,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = when (weekType) {
            StartupSnapshotWeekType.ALL -> Course.WEEK_TYPE_ALL
            StartupSnapshotWeekType.ODD -> Course.WEEK_TYPE_ODD
            StartupSnapshotWeekType.EVEN -> Course.WEEK_TYPE_EVEN
        },
        color = color,
    )

    /** 匹配课程的单双周约束。 */
    private fun Course.matchesWeekType(currentWeek: Int): Boolean = when (weekType) {
        Course.WEEK_TYPE_ALL -> true
        Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
        Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
        else -> false
    }

    /** 兼容用户保存的单数字小时，非法时间不应让 Widget 隐藏仍可能存在的课程。 */
    private fun parseSectionTime(value: String): LocalTime? {
        val normalized = value.trim().takeIf(String::isNotEmpty) ?: return null
        val parts = normalized.split(':')
        if (parts.size == 2 && parts[0].toIntOrNull() == 24) {
            return if (parts[1].toIntOrNull() == 0) LocalTime.MAX else null
        }
        return TIME_FORMATTERS.firstNotNullOfOrNull { formatter ->
            runCatching { LocalTime.parse(normalized, formatter) }.getOrNull()
        }
    }

    private companion object {
        /** 周次定义为连续七个自然日。 */
        const val DAYS_PER_WEEK = 7L

        /** 第 N 节课程结束时间位于从零开始的 N + duration - 2 位置。 */
        const val SECTION_INDEX_OFFSET = 2

        /** 支持现有设置中的 `H:mm` 与 `HH:mm` 两种合法格式。 */
        val TIME_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm"),
        )
    }
}
