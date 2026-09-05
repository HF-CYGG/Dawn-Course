package com.dawncourse.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** 导入成功数量必须排除 Room IGNORE 返回的占位行。 */
class ImportCommitResultCountingTest {
    @Test
    fun ignoredRowsAreExcludedFromCommittedCount() {
        assertEquals(2, countCommittedCourseRows(listOf(11L, -1L, 12L, -1L)))
    }
}
