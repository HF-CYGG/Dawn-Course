package com.dawncourse.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseRecoveryRestartOutcomeTest {
    @Test
    fun `restart launch failure releases busy state`() {
        assertFalse(recoveryBusyAfterRestartAttempt(restartStarted = false))
    }

    @Test
    fun `successful restart keeps surface blocked during handoff`() {
        assertTrue(recoveryBusyAfterRestartAttempt(restartStarted = true))
    }
}
