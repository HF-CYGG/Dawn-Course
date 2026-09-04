package com.dawncourse.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFlowRecoveryTest {
    @Test fun `exception emits fallback and failure event`() = runBlocking {
        var events = 0
        assertEquals("safe", recoverSettingsFlow(flow<String> { throw IllegalStateException() }, "safe") { events++ }.first())
        assertEquals(1, events)
    }

    @Test(expected = AssertionError::class)
    fun `error is rethrown`() {
        runBlocking { recoverSettingsFlow(flow<String> { throw AssertionError() }, "safe") { }.first() }
    }
}
