package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRecoveryEntryMode
import com.dawncourse.core.data.local.startup.DatabaseRecoveryReason
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在线恢复故障只在 marker 已确认持久化时允许自动移交重启。 */
class DatabaseRecoveryRestartPolicyTest {

    @Test
    fun onlyRestartRequiredStateRequestsOneAutomaticRestart() {
        assertTrue(
            DatabaseRecoveryRestartPolicy.shouldAutoRestart(
                DatabaseRuntimeState.RecoveryRequired(
                    reason = DatabaseRecoveryReason.RestoreFailed,
                    entryMode = DatabaseRecoveryEntryMode.RESTART_REQUIRED,
                ),
            ),
        )
        assertFalse(
            DatabaseRecoveryRestartPolicy.shouldAutoRestart(
                DatabaseRuntimeState.RecoveryRequired(
                    reason = DatabaseRecoveryReason.RestoreFailed,
                    entryMode = DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED,
                ),
            ),
        )
        assertFalse(DatabaseRecoveryRestartPolicy.shouldAutoRestart(DatabaseRuntimeState.Ready))
    }
}
