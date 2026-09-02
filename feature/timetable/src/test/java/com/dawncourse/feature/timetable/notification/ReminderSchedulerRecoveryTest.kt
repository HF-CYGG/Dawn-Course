package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 恢复重放必须延迟、唯一且有持久次数上限。 */
class ReminderSchedulerRecoveryTest {
    private val key = TriggerKey(0, 1, LocalDate.of(2026, 8, 24), TriggerKind.UNMUTE)

    @Test
    fun `失败次数决定专用 Worker 的线性分钟延迟`() {
        assertEquals(1L, WorkManagerMuteRecoveryScheduler.recoveryDelayMinutes(1))
        assertEquals(2L, WorkManagerMuteRecoveryScheduler.recoveryDelayMinutes(2))
    }

    @Test
    fun `恢复任务名包含完整稳定 key`() {
        assertEquals(
            "MuteRecovery:dawn://alarm/0/1/2026-08-24/unmute",
            WorkManagerMuteRecoveryScheduler.workName(key)
        )
    }
}
