package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.startup.BackupRecoveryActivation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 破坏性恢复前的 marker 时序必须可独立验证。 */
class BackupRestoreSafetyProtocolTest {

    @Test
    fun markerIsPreparedBeforeDestructiveRestoreAndClearedAfterVerifiedSuccess() = runBlocking {
        val events = mutableListOf<String>()

        val result = BackupRestoreSafetyProtocol.execute(
            prepareMarker = { events += "marker" },
            runRestore = {
                events += "replace"
                Result.success(Unit)
            },
            clearMarkerAndVerify = { events += "clear" },
            onMarkerUnavailable = { events += "marker-retry" },
            onMarkerStillRequired = {
                events += "restart"
                BackupRecoveryActivation.MarkerPersisted
            },
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("marker", "replace", "clear"), events)
    }

    @Test
    fun markerPreparationFailurePreventsEveryDestructiveWrite() = runBlocking {
        var restoreCalled = false
        var retryEntered = false

        val result = BackupRestoreSafetyProtocol.execute(
            prepareMarker = { error("marker storage unavailable") },
            runRestore = {
                restoreCalled = true
                Result.success(Unit)
            },
            clearMarkerAndVerify = { error("不得清除不存在的 marker") },
            onMarkerUnavailable = { retryEntered = true },
            onMarkerStillRequired = { BackupRecoveryActivation.MarkerPersisted },
        )

        assertTrue(result.isFailure)
        assertFalse(restoreCalled)
        assertTrue(retryEntered)
    }

    @Test
    fun markerClearFailureKeepsRecoveryRequiredInsteadOfReportingSuccess() = runBlocking {
        var restartEntered = false

        val result = BackupRestoreSafetyProtocol.execute(
            prepareMarker = {},
            runRestore = { Result.success(Unit) },
            clearMarkerAndVerify = { error("marker delete failed") },
            onMarkerUnavailable = { error("marker 已成功预置") },
            onMarkerStillRequired = {
                restartEntered = true
                BackupRecoveryActivation.MarkerPersisted
            },
        )

        assertTrue(result.exceptionOrNull() is BackupRecoveryRequiredException)
        assertTrue(restartEntered)
    }
}
