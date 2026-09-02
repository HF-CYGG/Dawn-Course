package com.dawncourse.core.data.local.startup

import com.dawncourse.core.data.repository.OperationalDataMutationGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同步/后台扫描、持久 marker 与写门关闭顺序的纯 JVM 测试。 */
class DatabaseStartupIntegrityCoordinatorTest {
    @Test
    fun synchronousFailureIsReturnedBeforeReadyWithoutPersistingOnlineMarker() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            verifier = {
                events += "verify"
                error("模拟同步完整性失败")
            },
        )

        val verified = coordinator.verifyBeforeReady(
            IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
            Any(),
        )

        assertFalse(verified)
        assertEquals(listOf("verify"), events)
    }

    @Test
    fun synchronousSuccessDefersResponsibilityCompletionUntilAllOtherReadyChecksFinish() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertTrue(
            coordinator.verifyBeforeReady(
                IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
                Any(),
            ),
        )
        assertEquals(listOf("verify"), events)

        assertTrue(
            coordinator.completeBeforeReady(IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY),
        )
        assertEquals(listOf("verify", "complete-success"), events)
    }

    @Test
    fun ordinaryPathRunsNoScanBeforeReadyAndCompletesInPostReadyAction() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertTrue(
            coordinator.verifyBeforeReady(
                IntegrityVerificationMode.BACKGROUND_AFTER_READY,
                Any(),
            ),
        )
        assertTrue(events.isEmpty())

        val result = requireNotNull(
            coordinator.postReadyAction(IntegrityVerificationMode.BACKGROUND_AFTER_READY, Any()),
        ).run()

        assertEquals(DatabasePostReadyResult.Complete, result)
        assertEquals(listOf("verify", "complete-success"), events)
    }

    @Test
    fun backgroundFailurePersistsDedicatedMarkerThenPermanentlyBlocksWrites() = runBlocking {
        val events = mutableListOf<String>()
        val mutationGate = OperationalDataMutationGate()
        val coordinator = coordinator(
            events = events,
            mutationGate = mutationGate,
            verifier = {
                events += "verify"
                error("不得向状态或日志传播的底层失败")
            },
        )

        val result = requireNotNull(
            coordinator.postReadyAction(IntegrityVerificationMode.BACKGROUND_AFTER_READY, Any()),
        ).run()

        assertEquals(
            DatabasePostReadyResult.RecoveryRequired(
                DatabaseRecoveryReason.IntegrityVerificationFailed,
                DatabaseRecoveryEntryMode.RESTART_REQUIRED,
            ),
            result,
        )
        assertEquals(listOf("verify", "integrity-marker"), events)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { mutationGate.withMutation { Unit } }
        }
        Unit
    }

    @Test
    fun backgroundMarkerFailureStillBlocksWritesAndRequiresCorrectMarkerRetry() = runBlocking {
        val events = mutableListOf<String>()
        val mutationGate = OperationalDataMutationGate()
        val coordinator = coordinator(
            events = events,
            mutationGate = mutationGate,
            verifier = { error("模拟完整性失败") },
            persistMarker = {
                events += "integrity-marker"
                error("模拟 marker 写失败")
            },
        )

        val result = requireNotNull(
            coordinator.postReadyAction(IntegrityVerificationMode.BACKGROUND_AFTER_READY, Any()),
        ).run()

        assertEquals(
            DatabasePostReadyResult.RecoveryRequired(
                DatabaseRecoveryReason.IntegrityVerificationFailed,
                DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED,
            ),
            result,
        )
        assertEquals(listOf("integrity-marker"), events)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { mutationGate.withMutation { Unit } }
        }
        Unit
    }

    private fun coordinator(
        events: MutableList<String>,
        mutationGate: OperationalDataMutationGate = OperationalDataMutationGate(),
        verifier: (Any) -> Unit = { events += "verify" },
        persistMarker: () -> Unit = { events += "integrity-marker" },
    ): DatabaseStartupIntegrityCoordinator<Any> = DatabaseStartupIntegrityCoordinator(
        verifier = verifier,
        completeSuccessfulVerification = { events += "complete-success" },
        persistIntegrityRecoveryMarker = persistMarker,
        mutationGate = mutationGate,
    )
}
