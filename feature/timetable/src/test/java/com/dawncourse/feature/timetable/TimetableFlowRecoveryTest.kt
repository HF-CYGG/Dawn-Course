package com.dawncourse.feature.timetable

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TimetableFlowRecoveryTest {
    @Test fun `exception becomes explicit error state`() = runBlocking {
        assertEquals(TimetableUiState.Error, recoverTimetableUiFlow(flow { throw IllegalStateException() }).first())
    }

    @Test(expected = AssertionError::class)
    fun `error is not converted to fallback`() {
        runBlocking { recoverTimetableUiFlow(flow { throw AssertionError() }).first() }
    }
}
