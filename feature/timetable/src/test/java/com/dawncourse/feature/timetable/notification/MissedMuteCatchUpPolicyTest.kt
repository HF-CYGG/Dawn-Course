package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedMuteCatchUpPolicyTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 8, 24)
    private val semesterStart = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()

    @Test
    fun `课程仍在窗口内且没有恢复责任时生成立即 MUTE`() {
        val candidates = find(
            now = Instant.parse("2026-08-24T00:30:00Z"),
            courses = listOf(course(id = 7, dayOfWeek = 1)),
        )

        assertEquals(1, candidates.size)
        assertEquals(
            TriggerKey(9, 7, monday, TriggerKind.MUTE),
            candidates.single().muteKey,
        )
        assertEquals(Instant.parse("2026-08-24T01:00:00Z"), candidates.single().recoveryAt)
    }

    @Test
    fun `课程已结束或已有 UNMUTE 责任时不重复补静音`() {
        assertTrue(
            find(
                now = Instant.parse("2026-08-24T01:00:00Z"),
                courses = listOf(course(id = 7, dayOfWeek = 1)),
            ).isEmpty(),
        )
        assertTrue(
            find(
                now = Instant.parse("2026-08-24T00:30:00Z"),
                courses = listOf(course(id = 7, dayOfWeek = 1)),
                protectedUnmuteKeys = setOf(TriggerKey(9, 7, monday, TriggerKind.UNMUTE)),
            ).isEmpty(),
        )
    }

    @Test
    fun `跨日课程在次日仍可补静音且使用昨日 occurrence`() {
        val sunday = monday.minusDays(1)
        val candidates = MissedMuteCatchUpPolicy.find(
            enabled = true,
            profileId = 9,
            now = Instant.parse("2026-08-23T16:30:00Z"),
            zoneId = zoneId,
            semesterStartDateMillis = monday.minusDays(7)
                .atStartOfDay(zoneId).toInstant().toEpochMilli(),
            semesterWeekCount = 18,
            courses = listOf(course(id = 8, dayOfWeek = sunday.dayOfWeek.value)),
            sectionTimes = listOf(SectionTime("23:30", "01:00")),
            protectedUnmuteKeys = emptySet(),
        )

        assertEquals(sunday, candidates.single().muteKey.occurrenceDate)
        assertEquals(Instant.parse("2026-08-23T17:00:00Z"), candidates.single().recoveryAt)
    }

    @Test
    fun `关闭自动静音或越过学期边界时不补投`() {
        assertTrue(
            MissedMuteCatchUpPolicy.find(
                enabled = false,
                profileId = 9,
                now = Instant.parse("2026-08-24T00:30:00Z"),
                zoneId = zoneId,
                semesterStartDateMillis = semesterStart,
                semesterWeekCount = 18,
                courses = listOf(course(id = 7, dayOfWeek = 1)),
                sectionTimes = listOf(SectionTime("08:00", "09:00")),
                protectedUnmuteKeys = emptySet(),
            ).isEmpty(),
        )
    }

    private fun find(
        now: Instant,
        courses: List<Course>,
        protectedUnmuteKeys: Set<TriggerKey> = emptySet(),
    ) = MissedMuteCatchUpPolicy.find(
        enabled = true,
        profileId = 9,
        now = now,
        zoneId = zoneId,
        semesterStartDateMillis = semesterStart,
        semesterWeekCount = 18,
        courses = courses,
        sectionTimes = listOf(SectionTime("08:00", "09:00")),
        protectedUnmuteKeys = protectedUnmuteKeys,
    )

    private fun course(id: Long, dayOfWeek: Int) = Course(
        id = id,
        semesterId = 1,
        name = "课程$id",
        dayOfWeek = dayOfWeek,
        startSection = 1,
        duration = 1,
        startWeek = 1,
        endWeek = 18,
        weekType = Course.WEEK_TYPE_ALL,
    )
}
