package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显式恢复的 marker 删除和 COMMITTED 写入必须是可机械重试的顺序。 */
class DatabaseRecoveryExplicitCommitTest {
    @Test
    fun markerClearFailurePreventsCommitAndTheSameJournalCanConvergeOnRetry() {
        var backupMarkerPresent = true
        var recoveryStateMarkerPresent = true
        var commitRecorded = false
        var failRecoveryStateClear = true

        assertThrows(IllegalStateException::class.java) {
            commitExplicitRecoveryDecision(
                clearRecoveryResponsibilities = { backupMarkerPresent = false },
                clearRecoveryStateMarker = {
                    if (failRecoveryStateClear) error("模拟 recovery-state-v1 删除失败")
                    recoveryStateMarkerPresent = false
                },
                recordCommitted = { commitRecorded = true },
            )
        }

        assertFalse(commitRecorded)
        assertTrue("失败时仍保留可用于下次收敛的 recovery-state-v1", recoveryStateMarkerPresent)

        failRecoveryStateClear = false
        commitExplicitRecoveryDecision(
            clearRecoveryResponsibilities = { backupMarkerPresent = false },
            clearRecoveryStateMarker = { recoveryStateMarkerPresent = false },
            recordCommitted = { commitRecorded = true },
        )

        assertFalse(backupMarkerPresent)
        assertFalse(recoveryStateMarkerPresent)
        assertTrue(commitRecorded)
    }
}
