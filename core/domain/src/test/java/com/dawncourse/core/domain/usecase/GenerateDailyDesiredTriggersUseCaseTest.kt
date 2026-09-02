package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 今日课程触发器生成策略测试。 */
class GenerateDailyDesiredTriggersUseCaseTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 8, 24)

    @Test
    fun `提醒静音与恢复动作都携带日期和真实 profile`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 42L,
            date = date,
            now = Instant.parse("2026-08-23T23:00:00Z"),
            zoneId = zoneId,
            currentWeek = 1,
            courses = listOf(course(id = Long.MAX_VALUE)),
            settings = settings()
        )

        assertEquals(
            listOf(TriggerKind.UNMUTE, TriggerKind.REMINDER, TriggerKind.MUTE),
            triggers.map { trigger -> trigger.key.kind }
        )
        assertTrue(triggers.all { trigger -> trigger.key.occurrenceDate == date })
        assertTrue(triggers.all { trigger -> trigger.key.profileId == 42L })
        assertTrue(triggers.all { trigger -> trigger.key.courseId == Long.MAX_VALUE })
    }

    @Test
    fun `课程已开始时仍保留未来 unmute`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 1L,
            date = date,
            now = Instant.parse("2026-08-24T00:30:00Z"),
            zoneId = zoneId,
            currentWeek = 1,
            courses = listOf(course(id = 1)),
            settings = settings(enableReminder = false)
        )

        assertEquals(listOf(TriggerKind.UNMUTE), triggers.map { trigger -> trigger.key.kind })
    }

    @Test
    fun `无效节次与时间只隔离单条课程`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 1L,
            date = date,
            now = Instant.parse("2026-08-23T23:00:00Z"),
            zoneId = zoneId,
            currentWeek = 1,
            courses = listOf(
                course(id = 1, startSection = 9),
                course(id = 2, startSection = 1)
            ),
            settings = settings(sectionTimes = listOf(SectionTime("08:00", "bad")))
        )

        assertEquals(listOf(2L, 2L), triggers.map { trigger -> trigger.key.courseId })
        assertEquals(
            listOf(TriggerKind.REMINDER, TriggerKind.MUTE),
            triggers.map { trigger -> trigger.key.kind }
        )
    }

    @Test
    fun `非当周与非当日课程不产生触发器`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 1L,
            date = date,
            now = Instant.parse("2026-08-23T23:00:00Z"),
            zoneId = zoneId,
            currentWeek = 2,
            courses = listOf(
                course(id = 1, dayOfWeek = 2),
                course(id = 2, weekType = Course.WEEK_TYPE_ODD)
            ),
            settings = settings()
        )

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `未知单双周类型按损坏课程隔离`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 1L,
            date = date,
            now = Instant.parse("2026-08-23T23:00:00Z"),
            zoneId = zoneId,
            currentWeek = 1,
            courses = listOf(course(id = 1, weekType = 999)),
            settings = settings()
        )

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `legacy profile 永不生成新触发器`() {
        val triggers = GenerateDailyDesiredTriggersUseCase().invoke(
            profileId = 0L,
            date = date,
            now = Instant.parse("2026-08-23T23:00:00Z"),
            zoneId = zoneId,
            currentWeek = 1,
            courses = listOf(course(id = 1L)),
            settings = settings()
        )

        assertTrue(triggers.isEmpty())
    }

    private fun course(
        id: Long,
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        weekType: Int = Course.WEEK_TYPE_ALL
    ): Course = Course(
        id = id,
        semesterId = 1,
        name = "课程$id",
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = 1,
        startWeek = 1,
        endWeek = 18,
        weekType = weekType
    )

    private fun settings(
        enableReminder: Boolean = true,
        sectionTimes: List<SectionTime> = listOf(SectionTime("08:00", "09:00"))
    ): AppSettings = AppSettings(
        enableClassReminder = enableReminder,
        reminderMinutes = 10,
        enableAutoMute = true,
        sectionTimes = sectionTimes
    )
}
