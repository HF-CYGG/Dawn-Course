package com.dawncourse.feature.import_module

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QidiUiActionTest {
    @Test fun `exception releases busy and returns failure`() = runBlocking {
        var busy = false
        val result = runQidiUiAction({ busy = it }) { throw IllegalStateException("secret") }
        assertFalse(result.isSuccess)
        assertFalse(busy)
    }

    @Test fun `cancellation propagates and releases busy`() = runBlocking {
        var busy = false
        try {
            runQidiUiAction({ busy = it }) { throw CancellationException() }
        } catch (_: CancellationException) { }
        assertFalse(busy)
    }

    @Test(expected = AssertionError::class)
    fun `error propagates`() {
        runBlocking { runQidiUiAction({ }) { throw AssertionError() } }
    }
}
