package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.repository.OperationalDataReadiness
import org.junit.Assert.assertEquals
import org.junit.Test

/** 应用级数据库启动策略的纯 JVM 契约。 */
class DatabaseStartupRuntimePolicyTest {
    @Test
    fun backgroundEntryPointsRetryOnlyWhileStartupIsStillRunning() {
        assertEquals(
            BackgroundDatabaseAction.RETRY_LATER,
            DatabaseStartupRuntimePolicy.backgroundAction(OperationalDataReadiness.STARTING)
        )
        assertEquals(
            BackgroundDatabaseAction.RUN,
            DatabaseStartupRuntimePolicy.backgroundAction(OperationalDataReadiness.READY)
        )
        assertEquals(
            BackgroundDatabaseAction.STOP_SAFELY,
            DatabaseStartupRuntimePolicy.backgroundAction(OperationalDataReadiness.RECOVERY_REQUIRED)
        )
    }

    @Test
    fun unmutesRemainAllowedWhenOperationalDatabaseIsUnavailable() {
        OperationalDataReadiness.entries.forEach { readiness ->
            assertEquals(
                ReceiverDatabaseAction.RUN_WITHOUT_DATABASE,
                DatabaseStartupRuntimePolicy.receiverAction(
                    readiness = readiness,
                    requiresOperationalDatabase = false
                )
            )
        }
    }

    @Test
    fun databaseDependentReceiversStopBeforeResolvingRepositories() {
        assertEquals(
            ReceiverDatabaseAction.STOP_SAFELY,
            DatabaseStartupRuntimePolicy.receiverAction(
                readiness = OperationalDataReadiness.RECOVERY_REQUIRED,
                requiresOperationalDatabase = true
            )
        )
        assertEquals(
            ReceiverDatabaseAction.STOP_SAFELY,
            DatabaseStartupRuntimePolicy.receiverAction(
                readiness = OperationalDataReadiness.STARTING,
                requiresOperationalDatabase = true
            )
        )
        assertEquals(
            ReceiverDatabaseAction.RUN_WITH_DATABASE,
            DatabaseStartupRuntimePolicy.receiverAction(
                readiness = OperationalDataReadiness.READY,
                requiresOperationalDatabase = true
            )
        )
    }
}
