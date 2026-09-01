package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRecoveryReason
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 启动 Splash 与恢复页选择不依赖 Compose 的纯 JVM 契约。 */
class DatabaseStartupUiPolicyTest {
    @Test
    fun startingKeepsSplashAndDoesNotCreateDatabaseViewModels() {
        assertEquals(
            DatabaseStartupUiDecision(keepSplash = true, createDatabaseViewModels = false, showRecovery = false),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Starting)
        )
    }

    @Test
    fun recoveryReleasesSplashWithoutCreatingDatabaseViewModels() {
        assertEquals(
            DatabaseStartupUiDecision(keepSplash = false, createDatabaseViewModels = false, showRecovery = true),
            DatabaseStartupUiPolicy.decide(
                DatabaseRuntimeState.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid)
            )
        )
    }

    @Test
    fun readyCreatesNormalGraphOnlyAfterVerification() {
        assertEquals(
            DatabaseStartupUiDecision(keepSplash = false, createDatabaseViewModels = true, showRecovery = false),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Ready)
        )
    }

    @Test
    fun blockedStartupDoesNotExposeRecoveryActionsWithoutPersistentTransaction() {
        assertEquals(
            DatabaseStartupUiDecision(keepSplash = false, createDatabaseViewModels = false, showRecovery = false),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.StartupBlocked)
        )
    }
}
