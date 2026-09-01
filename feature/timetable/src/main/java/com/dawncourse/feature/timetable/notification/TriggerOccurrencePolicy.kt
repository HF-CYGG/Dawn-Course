package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Receiver 真正执行前的课程 occurrence 防御性校验。 */
object TriggerOccurrencePolicy {

    /** 判断课程是否在指定日期与周次发生。 */
    fun occursOn(course: Course, date: LocalDate, currentWeek: Int): Boolean {
        if (course.dayOfWeek != date.dayOfWeek.value) return false
        if (currentWeek !in course.startWeek..course.endWeek) return false
        return when (course.weekType) {
            Course.WEEK_TYPE_ALL -> true
            Course.WEEK_TYPE_ODD -> currentWeek % 2 == 1
            Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
            else -> false
        }
    }

    /** 判断当前时刻是否位于该课程的 `[start, end)` 区间。 */
    fun isInCourseWindow(
        course: Course,
        occurrenceDate: LocalDate,
        currentWeek: Int,
        now: Instant,
        zoneId: ZoneId,
        sectionTimes: List<SectionTime>
    ): Boolean {
        if (!occursOn(course, occurrenceDate, currentWeek)) return false
        val start = sectionTimes.timeAt(course.startSection, useEnd = false) ?: return false
        val startAt = LocalDateTime.of(occurrenceDate, start).atZone(zoneId).toInstant()
        val endAt = courseEndAt(course, occurrenceDate, zoneId, sectionTimes) ?: return false
        return !now.isBefore(startAt) && now.isBefore(endAt)
    }

    /** 解析 occurrence 的绝对结束时刻，供静音责任独立于 Alarm registry 持久保存。 */
    fun courseEndAt(
        course: Course,
        occurrenceDate: LocalDate,
        zoneId: ZoneId,
        sectionTimes: List<SectionTime>
    ): Instant? {
        val start = sectionTimes.timeAt(course.startSection, useEnd = false) ?: return null
        val endSectionLong = course.startSection.toLong() + course.duration.toLong() - 1L
        if (endSectionLong !in 1..Int.MAX_VALUE) return null
        val end = sectionTimes.timeAt(endSectionLong.toInt(), useEnd = true) ?: return null
        val localStart = LocalDateTime.of(occurrenceDate, start)
        var localEnd = LocalDateTime.of(occurrenceDate, end)
        if (!localEnd.isAfter(localStart)) localEnd = localEnd.plusDays(1)
        return localEnd.atZone(zoneId).toInstant()
    }

    /** 判断当前时刻是否位于该 occurrence 的 `[reminderAt, courseStart]` 投递窗口。 */
    fun isInReminderWindow(
        course: Course,
        occurrenceDate: LocalDate,
        currentWeek: Int,
        now: Instant,
        zoneId: ZoneId,
        reminderMinutes: Int,
        sectionTimes: List<SectionTime>
    ): Boolean {
        if (!occursOn(course, occurrenceDate, currentWeek)) return false
        val start = sectionTimes.timeAt(course.startSection, useEnd = false) ?: return false
        val startAt = LocalDateTime.of(occurrenceDate, start).atZone(zoneId).toInstant()
        val reminderAt = startAt.minusSeconds(reminderMinutes.coerceAtLeast(0).toLong() * 60L)
        return !now.isBefore(reminderAt) && !now.isAfter(startAt)
    }

    /** 安全解析节次时间。 */
    private fun List<SectionTime>.timeAt(section: Int, useEnd: Boolean): LocalTime? {
        val value = getOrNull(section - 1)?.let { item -> if (useEnd) item.endTime else item.startTime }
            ?: return null
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}
