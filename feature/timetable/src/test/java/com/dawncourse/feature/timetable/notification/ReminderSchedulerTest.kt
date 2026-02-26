package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [ReminderScheduler.calculateInitialDelayMillis] 的单元测试。
 *
 * 覆盖重点：
 * - 当前时间早于目标 06:00：应对齐到“今天 06:00”
 * - 当前时间晚于目标 06:00：应对齐到“明天 06:00”
 *
 * 说明：
 * - 这里使用不含夏令时的时区（Asia/Shanghai），避免 DST 导致的小时数变化。
 * - 同时验证：延迟必须为非负，并且 now + delay 之后的本地日期/时间对齐到期望目标点。
 */
class ReminderSchedulerTest {

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    private val targetLocalTime: LocalTime = LocalTime.of(6, 0)

    @Test
    fun `当前时间早于06点时对齐到今天06点`() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(5, 0), zoneId)
        val expectedTarget = ZonedDateTime.of(LocalDate.of(2026, 1, 1), targetLocalTime, zoneId)
        val expectedDelay = Duration.between(now, expectedTarget).toMillis()

        val actualDelay = ReminderScheduler.calculateInitialDelayMillis(
            zoneId = zoneId,
            targetLocalTime = targetLocalTime,
            now = now
        )

        assertTrue("延迟必须为非负", actualDelay >= 0L)
        assertEquals(expectedDelay, actualDelay)

        val nextRun = now.plus(Duration.ofMillis(actualDelay))
        assertEquals(LocalDate.of(2026, 1, 1), nextRun.toLocalDate())
        assertEquals(targetLocalTime, nextRun.toLocalTime())
    }

    @Test
    fun `当前时间晚于06点时对齐到明天06点`() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(7, 0), zoneId)
        val expectedTarget = ZonedDateTime.of(LocalDate.of(2026, 1, 2), targetLocalTime, zoneId)
        val expectedDelay = Duration.between(now, expectedTarget).toMillis()

        val actualDelay = ReminderScheduler.calculateInitialDelayMillis(
            zoneId = zoneId,
            targetLocalTime = targetLocalTime,
            now = now
        )

        assertTrue("延迟必须为非负", actualDelay >= 0L)
        assertEquals(expectedDelay, actualDelay)

        val nextRun = now.plus(Duration.ofMillis(actualDelay))
        assertEquals(LocalDate.of(2026, 1, 2), nextRun.toLocalDate())
        assertEquals(targetLocalTime, nextRun.toLocalTime())
    }
}

