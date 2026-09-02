package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程状态通知的领域状态。
 */
enum class PersistentCourseStatus {
    /** 今天没有可展示的课程。 */
    NO_COURSES,

    /** 今天仍有尚未开始的课程。 */
    UPCOMING,

    /** 当前至少有一门正在进行的课程。 */
    IN_CLASS,

    /** 今天的有效课程已经全部结束。 */
    FINISHED
}

/**
 * 已解析出绝对时间边界的课程。
 *
 * @property course 原始课程定义
 * @property startAt 课程开始时刻（包含）
 * @property endAt 课程结束时刻（不包含）
 */
data class PersistentCourseOccurrence(
    val course: Course,
    val startAt: Instant,
    val endAt: Instant
)

/**
 * 一次课程状态通知刷新所需的完整计划。
 *
 * @property status 当前状态
 * @property currentCourses 当前所有重叠课程，顺序由领域层固定
 * @property nextCourses 下一组同时开始的课程，顺序由领域层固定
 * @property nextRefreshAt 下一次状态必然可能变化的最早时刻
 */
data class PersistentNotificationPlan(
    val status: PersistentCourseStatus,
    val currentCourses: List<PersistentCourseOccurrence>,
    val nextCourses: List<PersistentCourseOccurrence>,
    val nextRefreshAt: Instant
)

/**
 * 课程状态通知计划解析器。
 *
 * 该对象不读取系统时间或 Android API，调用方必须显式传入时钟和时区，
 * 从而让 Worker、通知与测试共享同一套确定性边界语义。
 */
object PersistentNotificationPlanResolver {
    /**
     * 展开今天的有效课程并计算当前状态与下一刷新边界。
     *
     * 课程区间采用 `[startAt, endAt)`；非法节次、非法时间或倒置时间会被忽略。
     * 排序固定为开始时间、开始节次、课程 ID。
     */
    fun resolve(
        now: Instant,
        zoneId: ZoneId,
        currentWeek: Int,
        weekCount: Int,
        courses: List<Course>,
        sectionTimes: List<SectionTime>
    ): PersistentNotificationPlan {
        val today = now.atZone(zoneId).toLocalDate()
        val nextMidnight = today.plusDays(1).atStartOfDay(zoneId).toInstant()
        val semesterIsValid = currentWeek in 1..weekCount && weekCount > 0
        val occurrences = if (semesterIsValid) {
            courses.mapNotNull { course ->
                course.toOccurrenceOrNull(today, currentWeek, sectionTimes, zoneId)
            }.sortedWith(OCCURRENCE_COMPARATOR)
        } else {
            emptyList()
        }

        val currentCourses = occurrences.filter { occurrence ->
            !now.isBefore(occurrence.startAt) && now.isBefore(occurrence.endAt)
        }
        val futureCourses = occurrences.filter { occurrence -> occurrence.startAt.isAfter(now) }
        val nextStartAt = futureCourses.firstOrNull()?.startAt
        val nextCourses = if (nextStartAt == null) {
            emptyList()
        } else {
            futureCourses.takeWhile { occurrence -> occurrence.startAt == nextStartAt }
        }
        val status = when {
            currentCourses.isNotEmpty() -> PersistentCourseStatus.IN_CLASS
            futureCourses.isNotEmpty() -> PersistentCourseStatus.UPCOMING
            occurrences.isEmpty() -> PersistentCourseStatus.NO_COURSES
            else -> PersistentCourseStatus.FINISHED
        }
        val nextRefreshAt = occurrences.asSequence()
            .flatMap { occurrence -> sequenceOf(occurrence.startAt, occurrence.endAt) }
            .filter { boundary -> boundary.isAfter(now) }
            .plus(nextMidnight)
            .minOrNull()
            ?: nextMidnight

        return PersistentNotificationPlan(
            status = status,
            currentCourses = currentCourses,
            nextCourses = nextCourses,
            nextRefreshAt = nextRefreshAt
        )
    }

    /**
     * 发布通知前检查初始计划是否已经过期，并至多重算一次。
     *
     * 该方法故意不循环：即使系统时间在重算期间再次跳变，也只返回一次新计划，
     * 后续由刷新调度器的单次即时对账保护负责收敛，避免 Worker 自激循环。
     */
    fun recalculateForPublication(
        initialPlan: PersistentNotificationPlan,
        publicationNow: Instant,
        zoneId: ZoneId,
        currentWeek: Int,
        weekCount: Int,
        courses: List<Course>,
        sectionTimes: List<SectionTime>
    ): PersistentNotificationPlan {
        if (initialPlan.nextRefreshAt.isAfter(publicationNow)) return initialPlan
        return resolve(
            now = publicationNow,
            zoneId = zoneId,
            currentWeek = currentWeek,
            weekCount = weekCount,
            courses = courses,
            sectionTimes = sectionTimes
        )
    }

    /**
     * 将课程定义转换为今天的绝对时间 occurrence；任何非法输入都返回 null。
     */
    private fun Course.toOccurrenceOrNull(
        today: LocalDate,
        currentWeek: Int,
        sectionTimes: List<SectionTime>,
        zoneId: ZoneId
    ): PersistentCourseOccurrence? {
        if (dayOfWeek != today.dayOfWeek.value) return null
        if (currentWeek !in startWeek..endWeek) return null
        if (!matchesWeekType(currentWeek)) return null
        if (startSection <= 0 || duration <= 0) return null

        val endSection = startSection + duration - 1
        if (endSection < startSection || endSection > sectionTimes.size) return null
        val startTime = parseTimeOrNull(sectionTimes[startSection - 1].startTime) ?: return null
        val endTime = parseTimeOrNull(sectionTimes[endSection - 1].endTime) ?: return null
        if (!endTime.isAfter(startTime)) return null

        return PersistentCourseOccurrence(
            course = this,
            startAt = today.atTime(startTime).atZone(zoneId).toInstant(),
            endAt = today.atTime(endTime).atZone(zoneId).toInstant()
        )
    }

    /**
     * 判断课程的单双周约束是否匹配当前周。
     */
    private fun Course.matchesWeekType(currentWeek: Int): Boolean = when (weekType) {
        Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
        Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
        Course.WEEK_TYPE_ALL -> true
        else -> false
    }

    /**
     * 容错解析 `H:mm` 或 `HH:mm`，拒绝越界值。
     */
    private fun parseTimeOrNull(value: String): LocalTime? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * 所有 Surface 共用的稳定排序规则。
     */
    private val OCCURRENCE_COMPARATOR = compareBy<PersistentCourseOccurrence>(
        { occurrence -> occurrence.startAt },
        { occurrence -> occurrence.course.startSection },
        { occurrence -> occurrence.course.id }
    )
}
