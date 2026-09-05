package com.dawncourse.feature.widget

/** 午夜 Widget 闹钟调度结果。 */
internal enum class MidnightAlarmResult {
    /** 已使用精确闹钟。 */
    EXACT,

    /** 已降级为非精确闹钟。 */
    INEXACT,

    /** 精确与非精确路径均不可用，调用方安全结束。 */
    NOT_SCHEDULED,
}

/** AlarmManager 的最小可测试操作边界。 */
internal interface MidnightAlarmOperations {
    /** 查询当前是否允许精确闹钟。 */
    fun canScheduleExactAlarm(): Boolean

    /** 安排精确闹钟。 */
    fun scheduleExact(triggerAtMillis: Long)

    /** 安排允许待机唤醒的非精确闹钟。 */
    fun scheduleInexact(triggerAtMillis: Long)
}

/** 午夜 Widget 闹钟的 capability 与降级策略。 */
internal object MidnightAlarmStrategy {
    /**
     * 安排下一次午夜更新。
     *
     * @param requiresExactAlarmCapability 当前 API 是否要求先查询精确闹钟能力。
     * @param triggerAtMillis 目标触发时间。
     * @param operations 平台 AlarmManager 操作适配器。
     */
    fun schedule(
        requiresExactAlarmCapability: Boolean,
        triggerAtMillis: Long,
        operations: MidnightAlarmOperations,
    ): MidnightAlarmResult {
        val canUseExact = if (requiresExactAlarmCapability) {
            try {
                operations.canScheduleExactAlarm()
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }

        if (canUseExact) {
            try {
                operations.scheduleExact(triggerAtMillis)
                return MidnightAlarmResult.EXACT
            } catch (_: Exception) {
                // 权限可能在 capability 查询后被撤销；继续使用既有非精确 fallback。
            }
        }

        return try {
            operations.scheduleInexact(triggerAtMillis)
            MidnightAlarmResult.INEXACT
        } catch (_: Exception) {
            MidnightAlarmResult.NOT_SCHEDULED
        }
    }
}
