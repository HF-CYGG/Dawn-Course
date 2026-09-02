package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** exact 到 inexact 的完整降级链测试。 */
class AlarmDeliveryStrategyTest {

    @Test
    fun `exact 权限异常时降级为 allow while idle 非精确闹钟`() {
        val operations = FakeAlarmOperations(exactFailure = SecurityException("denied"))

        val precision = AlarmDeliveryStrategy.schedule(
            canUseExact = true,
            triggerAtMillis = 1_000,
            operations = operations
        )

        assertEquals(TriggerPrecision.INEXACT, precision)
        assertEquals(listOf("exact", "inexact-idle"), operations.calls)
    }

    @Test
    fun `非精确两级都失败时向上抛出以便 Worker retry`() {
        val operations = FakeAlarmOperations(
            exactFailure = SecurityException("denied"),
            inexactFailure = IllegalStateException("idle failed"),
            basicFailure = IllegalStateException("basic failed")
        )

        assertThrows(TriggerSchedulingException::class.java) {
            AlarmDeliveryStrategy.schedule(true, 1_000, operations)
        }
    }

    private class FakeAlarmOperations(
        private val exactFailure: Throwable? = null,
        private val inexactFailure: Throwable? = null,
        private val basicFailure: Throwable? = null
    ) : AlarmOperations {
        val calls = mutableListOf<String>()

        override fun setExact(triggerAtMillis: Long) {
            calls += "exact"
            exactFailure?.let { failure -> throw failure }
        }

        override fun setInexactAllowWhileIdle(triggerAtMillis: Long) {
            calls += "inexact-idle"
            inexactFailure?.let { failure -> throw failure }
        }

        override fun setInexact(triggerAtMillis: Long) {
            calls += "inexact"
            basicFailure?.let { failure -> throw failure }
        }
    }
}
