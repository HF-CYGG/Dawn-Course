package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 每个 journal 边界在冷启动时的机械收敛动作。 */
class DatabaseRecoveryInstallRecoveryPolicyTest {
    @Test
    fun everyInstallBoundaryHasAnExplicitRecoveryAction() {
        assertEquals(
            DatabaseRecoveryInstallResumeAction.ROLLBACK_NEW_REPLACEMENT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.INITIALIZED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.ROLLBACK_NEW_REPLACEMENT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.NEW_ENVELOPE_READY)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.ROLLBACK_NEW_REPLACEMENT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.STAGING_VERIFIED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.FINISH_SETTINGS_AND_COMMIT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.MAIN_SWAPPED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.FINISH_SETTINGS_AND_COMMIT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.SETTINGS_APPLIED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.FINISH_SETTINGS_AND_COMMIT,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.LEGACY_REKEY_RETIRED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.KEEP_COMMITTED,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.COMMITTED)
        )
        assertEquals(
            DatabaseRecoveryInstallResumeAction.KEEP_RECOVERY_MODE,
            DatabaseRecoveryInstallRecoveryPolicy.action(DatabaseRecoveryInstallStage.ROLLED_BACK)
        )
    }
}
