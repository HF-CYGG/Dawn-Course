package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.repository.OperationalDataReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Runtime 外层锁、状态和句柄发布的纯 JVM 测试。 */
class DatabaseStartupRuntimeControllerTest {
    @Test
    fun fullInitializationRunsInsideSingleOuterCriticalSection() {
        val events = mutableListOf<String>()
        val database = Any()
        val controller = DatabaseStartupRuntimeController(
            criticalSection = object : DatabaseStartupCriticalSection {
                override fun run(block: () -> Unit) {
                    events += "lock"
                    try {
                        block()
                    } finally {
                        events += "unlock"
                    }
                }
            },
            initializer = DatabaseStartupInitializer {
                events += "recover-journal"
                events += "inspect-files"
                events += "prepare-envelope"
                events += "open-and-verify-room"
                DatabaseStartupInitialization.Ready(database, migratedPlaintextThisRun = false)
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(OperationalDataReadiness.READY, controller.readiness())
        assertSame(database, controller.requireReadyHandle())
        assertEquals(
            listOf(
                "lock",
                "recover-journal",
                "inspect-files",
                "prepare-envelope",
                "open-and-verify-room",
                "unlock"
            ),
            events
        )
    }

    @Test
    fun failurePublishesRecoveryAndNeverExposesHandle() {
        val controller = DatabaseStartupRuntimeController<Any>(
            criticalSection = DatabaseStartupCriticalSection { block -> block() },
            initializer = DatabaseStartupInitializer {
                DatabaseStartupInitialization.RecoveryRequired(
                    DatabaseRecoveryReason.KeyMissingOrInvalid
                )
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(OperationalDataReadiness.RECOVERY_REQUIRED, controller.readiness())
        assertEquals(
            DatabaseRuntimeState.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid),
            controller.state.value
        )
        runCatching { controller.requireReadyHandle() }
            .onSuccess { error("恢复状态不得暴露数据库句柄") }
    }

    @Test
    fun repeatedStartDoesNotRunInitializationTwice() {
        var calls = 0
        val controller = DatabaseStartupRuntimeController(
            criticalSection = DatabaseStartupCriticalSection { block -> block() },
            initializer = DatabaseStartupInitializer {
                calls += 1
                DatabaseStartupInitialization.Ready(Any(), migratedPlaintextThisRun = false)
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        controller.start(scope)
        controller.start(scope)

        assertEquals(1, calls)
    }

    @Test
    fun outerLockFailurePublishesBlockedInsteadOfFakeRecovery() {
        val controller = DatabaseStartupRuntimeController<Any>(
            criticalSection = DatabaseStartupCriticalSection { error("模拟锁失败") },
            initializer = DatabaseStartupInitializer { error("不应进入 initializer") },
            ioDispatcher = Dispatchers.Unconfined
        )

        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(DatabaseRuntimeState.StartupBlocked, controller.state.value)
        assertEquals(OperationalDataReadiness.RECOVERY_REQUIRED, controller.readiness())
    }

    @Test
    fun readyCanEnterRuntimeRecoveryAndRejectNewHandleAccess() {
        val database = Any()
        val controller = readyController(database)

        controller.enterRuntimeRecovery(
            DatabaseRecoveryReason.RestoreFailed,
            DatabaseRecoveryEntryMode.RESTART_REQUIRED,
        )

        assertEquals(
            DatabaseRuntimeState.RecoveryRequired(
                DatabaseRecoveryReason.RestoreFailed,
                DatabaseRecoveryEntryMode.RESTART_REQUIRED,
            ),
            controller.state.value,
        )
        assertEquals(OperationalDataReadiness.RECOVERY_REQUIRED, controller.readiness())
        assertThrows(IllegalStateException::class.java) { controller.requireReadyHandle() }
    }

    @Test
    fun markerRetryCanOnlyAdvanceToRestartRequiredAndNeverBackToReady() {
        val controller = readyController(Any())
        controller.enterRuntimeRecovery(
            DatabaseRecoveryReason.RestoreFailed,
            DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED,
        )

        controller.enterRuntimeRecovery(
            DatabaseRecoveryReason.RestoreFailed,
            DatabaseRecoveryEntryMode.RESTART_REQUIRED,
        )
        assertEquals(
            DatabaseRecoveryEntryMode.RESTART_REQUIRED,
            (controller.state.value as DatabaseRuntimeState.RecoveryRequired).entryMode,
        )

        controller.enterRuntimeRecovery(
            DatabaseRecoveryReason.RestoreFailed,
            DatabaseRecoveryEntryMode.ACTIONS_AVAILABLE,
        )
        assertEquals(
            DatabaseRecoveryEntryMode.RESTART_REQUIRED,
            (controller.state.value as DatabaseRuntimeState.RecoveryRequired).entryMode,
        )
    }

    @Test
    fun postReadyActionObservesReadyAndSuccessKeepsReady() {
        val events = mutableListOf<String>()
        lateinit var controller: DatabaseStartupRuntimeController<Any>
        controller = DatabaseStartupRuntimeController(
            criticalSection = DatabaseStartupCriticalSection { block -> block() },
            initializer = DatabaseStartupInitializer {
                DatabaseStartupInitialization.Ready(
                    handle = Any(),
                    migratedPlaintextThisRun = false,
                    postReadyAction = DatabasePostReadyAction {
                        assertEquals(DatabaseRuntimeState.Ready, controller.state.value)
                        events += "post-ready"
                        DatabasePostReadyResult.Complete
                    },
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(listOf("post-ready"), events)
        assertEquals(DatabaseRuntimeState.Ready, controller.state.value)
    }

    @Test
    fun postReadyRecoveryIsPublishedOnlyAfterMarkerAndGateActionCompletes() {
        val events = mutableListOf<String>()
        lateinit var controller: DatabaseStartupRuntimeController<Any>
        controller = DatabaseStartupRuntimeController(
            criticalSection = DatabaseStartupCriticalSection { block -> block() },
            initializer = DatabaseStartupInitializer {
                DatabaseStartupInitialization.Ready(
                    handle = Any(),
                    migratedPlaintextThisRun = false,
                    postReadyAction = DatabasePostReadyAction {
                        assertEquals(DatabaseRuntimeState.Ready, controller.state.value)
                        events += "marker"
                        events += "gate"
                        DatabasePostReadyResult.RecoveryRequired(
                            DatabaseRecoveryReason.IntegrityVerificationFailed,
                            DatabaseRecoveryEntryMode.RESTART_REQUIRED,
                        )
                    },
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertEquals(listOf("marker", "gate"), events)
        assertEquals(
            DatabaseRuntimeState.RecoveryRequired(
                DatabaseRecoveryReason.IntegrityVerificationFailed,
                DatabaseRecoveryEntryMode.RESTART_REQUIRED,
            ),
            controller.state.value,
        )
    }

    private fun readyController(database: Any): DatabaseStartupRuntimeController<Any> {
        val controller = DatabaseStartupRuntimeController(
            criticalSection = DatabaseStartupCriticalSection { block -> block() },
            initializer = DatabaseStartupInitializer {
                DatabaseStartupInitialization.Ready(database, migratedPlaintextThisRun = false)
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return controller
    }
}
