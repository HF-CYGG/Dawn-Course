package com.dawncourse.core.data.local.startup

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** marker 重试必须按恢复原因选择专用持久责任。 */
class DatabaseRecoveryMarkerRetryCoordinatorTest {
    @Test
    fun restoreFailureRetriesOnlyBackupMarker() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertTrue(coordinator.retry(DatabaseRecoveryReason.RestoreFailed))

        assertEquals(listOf("backup-marker"), events)
    }

    @Test
    fun integrityFailureRetriesOnlyIntegrityMarker() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertTrue(coordinator.retry(DatabaseRecoveryReason.IntegrityVerificationFailed))

        assertEquals(listOf("integrity-marker"), events)
    }

    @Test
    fun unsupportedReasonOrPersistenceFailureCannotAdvanceToRestart() = runBlocking {
        val unsupportedEvents = mutableListOf<String>()
        assertFalse(
            coordinator(unsupportedEvents).retry(DatabaseRecoveryReason.DatabaseOpenFailed),
        )
        assertTrue(unsupportedEvents.isEmpty())

        val failed = DatabaseRecoveryMarkerRetryCoordinator(
            persistBackupMarker = { error("模拟备份 marker 失败") },
            persistIntegrityMarker = { error("模拟完整性 marker 失败") },
        )
        assertFalse(failed.retry(DatabaseRecoveryReason.RestoreFailed))
        assertFalse(failed.retry(DatabaseRecoveryReason.IntegrityVerificationFailed))
    }

    @Test
    fun markerPersistenceRunsOnTheConfiguredIoDispatcher() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recovery-marker-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            var persistenceThread = ""
            val coordinator = DatabaseRecoveryMarkerRetryCoordinator(
                persistBackupMarker = { error("不应写备份 marker") },
                persistIntegrityMarker = { persistenceThread = Thread.currentThread().name },
                persistenceDispatcher = dispatcher,
            )

            assertTrue(runBlocking { coordinator.retry(DatabaseRecoveryReason.IntegrityVerificationFailed) })
            assertTrue(persistenceThread.startsWith("recovery-marker-io"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun coordinator(events: MutableList<String>) = DatabaseRecoveryMarkerRetryCoordinator(
        persistBackupMarker = { events += "backup-marker" },
        persistIntegrityMarker = { events += "integrity-marker" },
    )
}
