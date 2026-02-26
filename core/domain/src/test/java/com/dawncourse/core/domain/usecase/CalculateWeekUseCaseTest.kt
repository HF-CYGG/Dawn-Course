package com.dawncourse.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [CalculateWeekUseCase] 的单元测试。
 *
 * 说明：
 * - 该用例内部使用“系统默认时区”把时间戳转换为日期，因此测试也必须用同一时区构造时间戳，
 *   才能避免因时区差异导致的日期偏移问题。
 * - 这里重点覆盖：开学当天、跨周边界、开学前（返回 0 或负数）的行为。
 */
class CalculateWeekUseCaseTest {

    private val useCase = CalculateWeekUseCase()

    @Test
    fun `开学当天返回第1周`() {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.of(2026, 3, 2)

        // 同一天不同时间也应属于第 1 周（只看日期，不看时分秒）
        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val currentMillis = startDate.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli()

        val week = useCase(startDateMillis = startMillis, currentMillis = currentMillis)
        assertEquals(1, week)
    }

    @Test
    fun `跨周边界第8天返回第2周`() {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.of(2026, 3, 2)
        val currentDate = startDate.plusDays(7) // 第 8 天（含开学当天作为第 1 天）

        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val currentMillis = currentDate.atStartOfDay(zone).toInstant().toEpochMilli()

        val week = useCase(startDateMillis = startMillis, currentMillis = currentMillis)
        assertEquals(2, week)
    }

    @Test
    fun `开学前一天返回第0周`() {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.of(2026, 3, 2)
        val currentDate = startDate.minusDays(1)

        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val currentMillis = currentDate.atStartOfDay(zone).toInstant().toEpochMilli()

        val week = useCase(startDateMillis = startMillis, currentMillis = currentMillis)
        assertEquals(0, week)
    }

    @Test
    fun `开学前第8天返回负周次`() {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.of(2026, 3, 2)
        val currentDate = startDate.minusDays(8)

        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val currentMillis = currentDate.atStartOfDay(zone).toInstant().toEpochMilli()

        val week = useCase(startDateMillis = startMillis, currentMillis = currentMillis)
        assertEquals(-1, week)
    }
}

