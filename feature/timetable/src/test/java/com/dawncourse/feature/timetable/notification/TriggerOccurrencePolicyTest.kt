package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receiver 触发时的课程 occurrence 二次校验。 */
class TriggerOccurrencePolicyTest {
    private val date = LocalDate.of(2026, 8, 24)
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `mute 只在课程实际进行区间内允许`() {
        val course = course()
        val sectionTimes = listOf(SectionTime("08:00", "09:00"))

        assertTrue(
            TriggerOccurrencePolicy.isInCourseWindow(
                course,
                date,
                currentWeek = 1,
                now = Instant.parse("2026-08-24T00:30:00Z"),
                zoneId = zoneId,
                sectionTimes = sectionTimes
            )
        )
        assertFalse(
            TriggerOccurrencePolicy.isInCourseWindow(
                course,
                date,
                currentWeek = 1,
                now = Instant.parse("2026-08-24T01:00:00Z"),
                zoneId = zoneId,
                sectionTimes = sectionTimes
            )
        )
    }

    @Test
    fun `提醒仅校验该日该周课程而不要求已开始`() {
        assertTrue(TriggerOccurrencePolicy.occursOn(course(), date, currentWeek = 1))
        assertFalse(TriggerOccurrencePolicy.occursOn(course(dayOfWeek = 2), date, currentWeek = 1))
        assertFalse(TriggerOccurrencePolicy.occursOn(course(weekType = 999), date, currentWeek = 1))
    }

    @Test
    fun `跨日前提醒允许在 reminderAt 到 courseStart 窗口投递`() {
        val occurrenceDate = LocalDate.of(2026, 8, 25)
        val midnightCourse = course(dayOfWeek = occurrenceDate.dayOfWeek.value)
        val sectionTimes = listOf(SectionTime("00:30", "01:30"))

        assertTrue(
            TriggerOccurrencePolicy.isInReminderWindow(
                course = midnightCourse,
                occurrenceDate = occurrenceDate,
                currentWeek = 1,
                now = Instant.parse("2026-08-24T15:30:00Z"),
                zoneId = zoneId,
                reminderMinutes = 60,
                sectionTimes = sectionTimes
            )
        )
        assertTrue(
            TriggerOccurrencePolicy.isInReminderWindow(
                midnightCourse,
                occurrenceDate,
                1,
                Instant.parse("2026-08-24T16:30:00Z"),
                zoneId,
                60,
                sectionTimes
            )
        )
        assertFalse(
            TriggerOccurrencePolicy.isInReminderWindow(
                midnightCourse,
                occurrenceDate,
                1,
                Instant.parse("2026-08-24T16:30:01Z"),
                zoneId,
                60,
                sectionTimes
            )
        )
    }

    @Test
    fun `课程结束时刻可独立持久且正确跨日`() {
        val overnight = course(dayOfWeek = date.dayOfWeek.value)

        assertEquals(
            Instant.parse("2026-08-24T16:30:00Z"),
            TriggerOccurrencePolicy.courseEndAt(
                course = overnight,
                occurrenceDate = date,
                zoneId = zoneId,
                sectionTimes = listOf(SectionTime("23:30", "00:30"))
            )
        )
    }

    private fun course(
        dayOfWeek: Int = 1,
        weekType: Int = Course.WEEK_TYPE_ALL
    ): Course = Course(
        id = 1,
        semesterId = 1,
        name = "课程",
        dayOfWeek = dayOfWeek,
        startSection = 1,
        duration = 1,
        startWeek = 1,
        endWeek = 18,
        weekType = weekType
    )
}
