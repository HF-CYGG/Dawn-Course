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
     * 计算当前周次
     *
     * 根据学期开始日期和当前日期，计算当前是第几周。
     *
     * @param startDateMillis 学期开始时间戳 (ms)
     * @param currentMillis 当前时间戳 (ms)，默认为现在
     * @return 当前是第几周 (1-based)。如果在开学前，返回 0 或负数。
     * 
     * 算法逻辑：
     * 1. 将时间戳转换为系统默认时区的 LocalDate (忽略具体时间，只看日期)
     * 2. 计算两个日期之间的天数差 (ChronoUnit.DAYS)
     * 3. (天数差 / 7) + 1 即为周次
     */
    operator fun invoke(startDateMillis: Long, currentMillis: Long = System.currentTimeMillis()): Int {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startDateMillis).atZone(zone).toLocalDate()
        val current = Instant.ofEpochMilli(currentMillis).atZone(zone).toLocalDate()
        
        val daysDiff = ChronoUnit.DAYS.between(start, current)
        // 注意：这里必须使用“向下取整”的整除（floorDiv）。
        // 否则在开学前（daysDiff 为负数）时，Kotlin/Java 的整除会向 0 截断，
        // 例如 -1 / 7 会得到 0，导致周次被错误计算为 1。
        return Math.floorDiv(daysDiff, 7).toInt() + 1
    }
}
