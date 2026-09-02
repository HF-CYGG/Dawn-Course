package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 今天与明天必须按各自发生日、周次生成触发器。 */
class GenerateTriggerHorizonUseCaseTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `周日跨到周一时明日课程按下一周单双周判断`() {
        val sunday = LocalDate.of(2026, 8, 30)
        val semesterStart = LocalDate.of(2026, 8, 24).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val course = course(dayOfWeek = 1, weekType = Course.WEEK_TYPE_EVEN)

        val triggers = GenerateTriggerHorizonUseCase(GenerateDailyDesiredTriggersUseCase()).invoke(
            profileId = 8L,
            firstDate = sunday,
            dayCount = 2,
            now = Instant.parse("2026-08-30T12:00:00Z"),
            zoneId = zoneId,
            semesterStartDateMillis = semesterStart,
            semesterWeekCount = 18,
            courses = listOf(course),
            settings = settings()
        )

        assertEquals(setOf(TriggerKind.REMINDER, TriggerKind.MUTE, TriggerKind.UNMUTE), triggers.map { it.key.kind }.toSet())
        assertEquals(setOf(LocalDate.of(2026, 8, 31)), triggers.map { it.key.occurrenceDate }.toSet())
    }

    @Test
    fun `零点半课程生成前一日二十三点半 reminder`() {
        val today = LocalDate.of(2026, 8, 24)
        val tomorrow = today.plusDays(1)
        val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val triggers = GenerateTriggerHorizonUseCase(GenerateDailyDesiredTriggersUseCase()).invoke(
            profileId = 8L,
            firstDate = today,
            dayCount = 2,
            now = Instant.parse("2026-08-24T14:00:00Z"),
            zoneId = zoneId,
            semesterStartDateMillis = start,
            semesterWeekCount = 18,
            courses = listOf(course(dayOfWeek = tomorrow.dayOfWeek.value)),
            settings = settings(enableAutoMute = false)
        )

        val reminder = triggers.single()
        assertEquals(TriggerKind.REMINDER, reminder.key.kind)
        assertEquals(tomorrow, reminder.key.occurrenceDate)
        assertEquals(Instant.parse("2026-08-24T15:30:00Z"), reminder.triggerAt)
    }

    @Test
    fun `仅自动静音时明日 occurrence 不生成 reminder`() {
        val today = LocalDate.of(2026, 8, 24)
        val tomorrow = today.plusDays(1)
        val triggers = GenerateTriggerHorizonUseCase(GenerateDailyDesiredTriggersUseCase()).invoke(
            profileId = 8L,
            firstDate = today,
            dayCount = 2,
            now = Instant.parse("2026-08-24T14:00:00Z"),
            zoneId = zoneId,
            semesterStartDateMillis = today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            semesterWeekCount = 18,
            courses = listOf(course(dayOfWeek = tomorrow.dayOfWeek.value)),
            settings = settings(enableReminder = false)
        )

        assertEquals(listOf(TriggerKind.UNMUTE, TriggerKind.MUTE), triggers.map { it.key.kind })
    }

    private fun course(dayOfWeek: Int, weekType: Int = Course.WEEK_TYPE_ALL) = Course(
        id = 1,
        semesterId = 1,
        name = "凌晨课程",
        dayOfWeek = dayOfWeek,
        startSection = 1,
        duration = 1,
        startWeek = 1,
        endWeek = 18,
        weekType = weekType
    )

    private fun settings(enableReminder: Boolean = true, enableAutoMute: Boolean = true) = AppSettings(
        enableClassReminder = enableReminder,
        reminderMinutes = 60,
        enableAutoMute = enableAutoMute,
        sectionTimes = listOf(SectionTime("00:30", "01:30"))
    )
}
