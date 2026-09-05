package com.dawncourse.feature.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** 午夜 Widget 闹钟 capability 与降级策略测试。 */
class MidnightAlarmStrategyTest {

    @Test
    fun `API 31 以上无精确权限时不调用 exact 并直接降级`() {
        val operations = RecordingOperations(canScheduleExact = false)

        val result = MidnightAlarmStrategy.schedule(
            requiresExactAlarmCapability = true,
            triggerAtMillis = 1_000L,
            operations = operations,
        )

        assertEquals(MidnightAlarmResult.INEXACT, result)
        assertEquals(listOf("capability", "inexact:1000"), operations.events)
    }

    @Test
    fun `精确权限查询失败时按无权限降级`() {
        val operations = RecordingOperations(capabilityFailure = IllegalStateException("query failed"))

        val result = MidnightAlarmStrategy.schedule(
            requiresExactAlarmCapability = true,
            triggerAtMillis = 1_000L,
            operations = operations,
        )

        assertEquals(MidnightAlarmResult.INEXACT, result)
        assertEquals(listOf("capability", "inexact:1000"), operations.events)
    }

    @Test
    fun `允许 exact 但平台调用失败时降级为 inexact`() {
        val operations = RecordingOperations(exactFailure = SecurityException("revoked"))

        val result = MidnightAlarmStrategy.schedule(
            requiresExactAlarmCapability = true,
            triggerAtMillis = 1_000L,
            operations = operations,
        )

        assertEquals(MidnightAlarmResult.INEXACT, result)
        assertEquals(listOf("capability", "exact:1000", "inexact:1000"), operations.events)
    }

    @Test
    fun `API 31 以下跳过 capability 查询并使用 exact`() {
        val operations = RecordingOperations(canScheduleExact = false)

        val result = MidnightAlarmStrategy.schedule(
            requiresExactAlarmCapability = false,
            triggerAtMillis = 1_000L,
            operations = operations,
        )

        assertEquals(MidnightAlarmResult.EXACT, result)
        assertEquals(listOf("exact:1000"), operations.events)
    }

    @Test
    fun `inexact fallback 自身失败时安全结束而不冒泡普通异常`() {
        val operations = RecordingOperations(
            canScheduleExact = false,
            inexactFailure = IllegalStateException("alarm service rejected fallback"),
        )

        val result = MidnightAlarmStrategy.schedule(
            requiresExactAlarmCapability = true,
            triggerAtMillis = 1_000L,
            operations = operations,
        )

        assertEquals(MidnightAlarmResult.NOT_SCHEDULED, result)
        assertEquals(listOf("capability", "inexact:1000"), operations.events)
    }

    /** 记录策略调用序列的纯 Kotlin 假实现。 */
    private class RecordingOperations(
        private val canScheduleExact: Boolean = true,
        private val capabilityFailure: Exception? = null,
        private val exactFailure: Exception? = null,
        private val inexactFailure: Exception? = null,
    ) : MidnightAlarmOperations {
        val events = mutableListOf<String>()

        override fun canScheduleExactAlarm(): Boolean {
            events += "capability"
            capabilityFailure?.let { failure -> throw failure }
            return canScheduleExact
        }

        override fun scheduleExact(triggerAtMillis: Long) {
            events += "exact:$triggerAtMillis"
            exactFailure?.let { failure -> throw failure }
        }

        override fun scheduleInexact(triggerAtMillis: Long) {
            events += "inexact:$triggerAtMillis"
            inexactFailure?.let { failure -> throw failure }
        }
    }
}
