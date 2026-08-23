package com.dawncourse.feature.import_module.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeLimitsTest {

    @Test
    fun `accepts payloads at the configured limits`() {
        assertTrue(
            ScriptRuntimeLimits.validateInput(
                harnessBytes = 1024,
                scriptAndDependencyBytes = 512 * 1024,
                htmlBytes = 3 * 1024 * 1024,
                timeoutMillis = 5_000
            ).isValid
        )
        assertTrue(ScriptRuntimeLimits.isResultSizeValid(1024 * 1024))
    }

    @Test
    fun `rejects every payload dimension above its limit`() {
        assertFalse(
            ScriptRuntimeLimits.validateInput(
                harnessBytes = 1024,
                scriptAndDependencyBytes = 512 * 1024 + 1,
                htmlBytes = 1,
                timeoutMillis = 5_000
            ).isValid
        )
        assertFalse(
            ScriptRuntimeLimits.validateInput(
                harnessBytes = 1024,
                scriptAndDependencyBytes = 1,
                htmlBytes = 3 * 1024 * 1024 + 1,
                timeoutMillis = 5_000
            ).isValid
        )
        assertFalse(ScriptRuntimeLimits.isResultSizeValid(1024 * 1024 + 1))
    }

    @Test
    fun `normalizes execution budget to the hard process limit`() {
        assertEquals(1L, ScriptRuntimeLimits.normalizeTimeout(0))
        assertEquals(4_000L, ScriptRuntimeLimits.normalizeTimeout(4_000))
        assertEquals(5_000L, ScriptRuntimeLimits.normalizeTimeout(8_000))
    }
}
