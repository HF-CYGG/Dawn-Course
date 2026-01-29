package com.dawncourse.core.domain.usecase

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * 计算当前周次用例
 */
class CalculateWeekUseCase @Inject constructor() {
    
    /**
     * @param startDateMillis 学期开始时间戳 (ms)
     * @param currentMillis 当前时间戳 (ms)，默认为现在
     * @return 当前是第几周 (1-based)。如果在开学前，返回 0 或负数。
     */
    operator fun invoke(startDateMillis: Long, currentMillis: Long = System.currentTimeMillis()): Int {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startDateMillis).atZone(zone).toLocalDate()
        val current = Instant.ofEpochMilli(currentMillis).atZone(zone).toLocalDate()
        
        val daysDiff = ChronoUnit.DAYS.between(start, current)
        return (daysDiff / 7).toInt() + 1
    }
}
