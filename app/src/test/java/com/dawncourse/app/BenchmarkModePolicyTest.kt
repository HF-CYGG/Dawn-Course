package com.dawncourse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 非 benchmark 变体必须在触达 PackageManager 前短路。 */
class BenchmarkModePolicyTest {
    @Test
    fun disabledBuildSkipsApplicationMetadataQuery() {
        var metadataQueries = 0

        val enabled = isBenchmarkModeEnabled(buildEnablesBenchmark = false) {
            metadataQueries += 1
            true
        }

        assertFalse(enabled)
        assertEquals(0, metadataQueries)
    }

    @Test
    fun benchmarkBuildRetainsMetadataBasedEnablement() {
        var metadataQueries = 0

        val enabled = isBenchmarkModeEnabled(buildEnablesBenchmark = true) {
            metadataQueries += 1
            true
        }

        assertTrue(enabled)
        assertEquals(1, metadataQueries)
    }
}
