package com.dawncourse.feature.widget

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Widget 渲染前由 Repository 构建出的真实时间线数据。 */
data class WidgetTimeline(
    /** 构建时确认的活动课表身份，供 Surface 防止把旧课表时间线误当作当前结果。 */
    val profileId: Long?,
    val displayCourses: List<Course>,
    val today: LocalDate,
    val currentWeek: Int,
    val sectionTimes: List<SectionTime>,
    val emptyMessage: String,
    val isBeforeSemesterStart: Boolean,
    val nextUpdateMillis: Long?,
    val sourceCourseCount: Int
)

/**
 * Widget 的生产数据构建链路。
 *
 * 该类集中承载 Hilt -> Repository -> Room Flow 的读取和当天课程筛选；[DawnWidget]
 * 与 benchmark-only Provider 都调用这一实现，避免测试侧复制 Widget 算法。
 */
@Singleton
class WidgetTimelineBuilder @Inject constructor(
    private val courseRepository: CourseRepository,
    private val timetableProfileRepository: TimetableProfileRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend fun build(
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): WidgetTimeline = withContext(Dispatchers.IO) {
        // 读取课程期间可能切换 Profile；只重试一次，第二次仍变化时安全降级为空，
        // 绝不把旧 Profile 的课程绘制到新 Profile 的 Widget 上。
        val first = buildOnce(today, now)
        if (isStillCurrent(first.contextKey)) return@withContext first.timeline
        val second = buildOnce(today, now)
        if (isStillCurrent(second.contextKey)) {
            second.timeline
        } else {
            val latest = timetableProfileRepository.getActiveContext()
            second.timeline.copy(
                profileId = latest?.profile?.id,
                displayCourses = emptyList(),
                emptyMessage = "课表已切换，正在刷新",
                nextUpdateMillis = null,
                sourceCourseCount = 0
            )
        }
    }

    /** 单次读取必须由 [build] 在完成后复核活动上下文。 */
    private suspend fun buildOnce(
        today: LocalDate,
        now: LocalTime
    ): WidgetTimelineAttempt {
        // Profile 与其活动学期由领域层在同一选择锁内解析，禁止分别读取旧选择状态。
        val activeContext = timetableProfileRepository.getActiveContext()
        val semester = activeContext?.semester
        val settings = settingsRepository.settings.first()
        val sectionTimes = settings.sectionTimes
        val termStartDate = semester?.let {
            Instant.ofEpochMilli(it.startDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
        val daysUntilSemesterStart = termStartDate
            ?.takeIf { startDate -> today.isBefore(startDate) }
            ?.let { startDate -> ChronoUnit.DAYS.between(today, startDate) }
        val isBeforeSemesterStart = daysUntilSemesterStart != null
        val currentWeek = if (semester != null && termStartDate != null && !isBeforeSemesterStart) {
            val daysDiff = ChronoUnit.DAYS.between(termStartDate, today)
            (daysDiff / 7).toInt() + 1
        } else {
            0
        }
        val isSemesterEnded = semester != null && !isBeforeSemesterStart &&
            currentWeek > semester.weekCount
        val allCourses = semester?.let { currentSemester ->
            courseRepository.getCoursesBySemester(currentSemester.id).first()
        }.orEmpty()
        val courses = if (isSemesterEnded || isBeforeSemesterStart) {
            emptyList()
        } else {
            filterCoursesForTimeline(allCourses, today, currentWeek)
        }
        val emptyMessage = emptyMessage(
            courses = courses,
            allCourses = allCourses,
            currentWeek = currentWeek,
            isBeforeSemesterStart = isBeforeSemesterStart,
            isSemesterEnded = isSemesterEnded,
            daysUntilSemesterStart = daysUntilSemesterStart
        )
        val displayCourses = courses.filter { course ->
            isCourseCurrentOrFuture(course, sectionTimes, now)
        }
        return WidgetTimelineAttempt(
            contextKey = WidgetContextKey(
                profileId = activeContext?.profile?.id,
                semesterId = semester?.id
            ),
            timeline = WidgetTimeline(
            profileId = activeContext?.profile?.id,
            displayCourses = displayCourses,
            today = today,
            currentWeek = currentWeek,
            sectionTimes = sectionTimes,
            emptyMessage = if (displayCourses.isNotEmpty()) {
                ""
            } else if (courses.isNotEmpty()) {
                "今日课程已结束 🌙"
            } else {
                emptyMessage
            },
            isBeforeSemesterStart = isBeforeSemesterStart,
            nextUpdateMillis = computeNextCourseEndMillis(courses, sectionTimes, today, now),
            sourceCourseCount = allCourses.size
            )
        )
    }

    /** 构建完成后确认 Profile 与学期仍未切换。 */
    private suspend fun isStillCurrent(expected: WidgetContextKey): Boolean {
        val current = timetableProfileRepository.getActiveContext()
        return expected == WidgetContextKey(
            profileId = current?.profile?.id,
            semesterId = current?.semester?.id
        )
    }

    /** Widget 读取事务外的轻量上下文版本，防止交叉 Profile 的瞬时组合。 */
    private data class WidgetContextKey(
        val profileId: Long?,
        val semesterId: Long?
    )

    /** 单次构建结果与其读入时的上下文身份。 */
    private data class WidgetTimelineAttempt(
        val contextKey: WidgetContextKey,
        val timeline: WidgetTimeline
    )

    private fun filterCoursesForTimeline(
        courses: List<Course>,
        today: LocalDate,
        currentWeek: Int
    ): List<Course> = courses
        .filter { course ->
            course.dayOfWeek == today.dayOfWeek.value &&
                currentWeek in course.startWeek..course.endWeek &&
                course.matchesWeekType(currentWeek)
        }
        .groupBy { "${it.startSection}-${it.name}" }
        .map { (_, coursesAtTime) ->
            coursesAtTime.maxByOrNull { if (it.location.isNotBlank()) 1 else 0 }
                ?: coursesAtTime.first()
        }
        .sortedBy { it.startSection }

    private fun emptyMessage(
        courses: List<Course>,
        allCourses: List<Course>,
        currentWeek: Int,
        isBeforeSemesterStart: Boolean,
        isSemesterEnded: Boolean,
        daysUntilSemesterStart: Long?
    ): String {
        if (courses.isNotEmpty()) return ""
        if (isBeforeSemesterStart) {
            return when (daysUntilSemesterStart) {
                0L -> "明天就要接受知识的洗礼了"
                null -> "还未开学哦"
                else -> "距开学还有 $daysUntilSemesterStart 天"
            }
        }
        if (isSemesterEnded) return "学期已结束 🎉"

        val hasCourseThisWeek = allCourses.any { course ->
            currentWeek in course.startWeek..course.endWeek && course.matchesWeekType(currentWeek)
        }
        return if (hasCourseThisWeek) {
            "今日已无课 ☕"
        } else if (allCourses.any { it.endWeek > currentWeek }) {
            "本周无课 🌴"
        } else {
            "好好享受假期吧 🎉"
        }
    }

    private fun Course.matchesWeekType(currentWeek: Int): Boolean = when (weekType) {
        Course.WEEK_TYPE_ALL -> true
        Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
        Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
        else -> true
    }

    private fun isCourseCurrentOrFuture(
        course: Course,
        sectionTimes: List<SectionTime>,
        now: LocalTime
    ): Boolean {
        if (sectionTimes.isEmpty()) return true
        val endSectionIndex = course.startSection + course.duration - 2
        val endTime = sectionTimes.getOrNull(endSectionIndex)?.endTime
            ?.let(::parseSectionTime)
            ?: return true
        return now.isBefore(endTime)
    }

    private fun computeNextCourseEndMillis(
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        today: LocalDate,
        now: LocalTime
    ): Long? {
        if (courses.isEmpty() || sectionTimes.isEmpty()) return null
        val nextEndTime = courses.mapNotNull { course ->
            val endSectionIndex = course.startSection + course.duration - 2
            sectionTimes.getOrNull(endSectionIndex)?.endTime
                ?.let(::parseSectionTime)
                ?.takeIf { it.isAfter(now) }
        }.minOrNull() ?: return null
        val triggerAt = today.atTime(nextEndTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return triggerAt.takeIf { it > Instant.now().toEpochMilli() }
    }

    private fun parseSectionTime(value: String): LocalTime? {
        if (value.isBlank()) return null
        val trimmed = value.trim()
        val parts = trimmed.split(":")
        if (parts.size == 2) {
            val hour = parts[0].toIntOrNull()
            val minute = parts[1].toIntOrNull()
            if (hour == 24 && minute != null && minute in 0..59) {
                return LocalTime.of(23, 59)
            }
        }
        val formatters = listOf(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm")
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalTime.parse(trimmed, formatter) }.getOrNull()
        }
    }
}
