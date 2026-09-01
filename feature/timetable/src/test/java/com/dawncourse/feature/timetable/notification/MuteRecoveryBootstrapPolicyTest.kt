package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重启后恢复入口必须只依赖静音责任 Store，不依赖 Alarm registry。 */
class MuteRecoveryBootstrapPolicyTest {
    private val key = TriggerKey(0, 11, LocalDate.of(2026, 8, 25), TriggerKind.UNMUTE)

    @Test
    fun `系统 Alarm 全部清空后 ACTIVE 仍按持久结束时间生成 key addressed work`() {
        val now = Instant.parse("2026-08-25T01:00:00Z")
        val recoveryAt = Instant.parse("2026-08-25T02:00:00Z")
        val record = MuteSessionRecord(
            key = key,
            status = MuteSessionStatus.ACTIVE,
            recoveryAttempt = 0,
            recoveryAt = recoveryAt
        )

        val plan = MuteRecoveryBootstrapPolicy.plan(setOf(record), now)

        assertEquals(listOf(MuteRecoveryWorkPlan(key, recoveryAt)), plan.works)
        assertFalse(plan.needsUserAttention)
    }

    @Test
    fun `旧 ACTIVE 缺少结束时间时立即建立安全恢复入口`() {
        val now = Instant.parse("2026-08-25T03:00:00Z")
        val record = MuteSessionRecord(key, MuteSessionStatus.ACTIVE, 0)

        val plan = MuteRecoveryBootstrapPolicy.plan(setOf(record), now)

        assertEquals(listOf(MuteRecoveryWorkPlan(key, now)), plan.works)
    }

    @Test
    fun `PENDING 立即修补而 EXHAUSTED 只发布用户警示`() {
        val now = Instant.parse("2026-08-25T03:00:00Z")
        val pending = MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 1)
        val exhausted = MuteSessionRecord(
            key.copy(courseId = 12),
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
            MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
        )

        val plan = MuteRecoveryBootstrapPolicy.plan(setOf(exhausted, pending), now)

        assertEquals(listOf(MuteRecoveryWorkPlan(key, now)), plan.works)
        assertTrue(plan.needsUserAttention)
    }
}
