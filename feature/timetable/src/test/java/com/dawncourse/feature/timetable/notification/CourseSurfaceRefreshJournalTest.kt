package com.dawncourse.feature.timetable.notification

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseSurfaceRefreshJournalTest {

    @Test
    fun `marker 成功但 WorkManager 失败时仍保留边界刷新责任`() = runBlocking {
        val journal = FakeCourseSurfaceRefreshJournal()

        assertTrue(persistAndEnqueueCourseSurfaceRefresh(journal) { false })
        assertTrue(journal.pendingToken() != null)
    }

    @Test
    fun `Surface 刷新失败时不清责任`() {
        val journal = FakeCourseSurfaceRefreshJournal()
        journal.markPending()
        val claim = captureCourseSurfaceRefreshClaim(journal)

        assertTrue(acknowledgeCourseSurfaceRefresh(journal, claim, surfaceRefreshed = false))
        assertTrue(journal.pendingToken() != null)
    }

    @Test
    fun `Surface 刷新成功后清除同一代际`() {
        val journal = FakeCourseSurfaceRefreshJournal()
        journal.markPending()
        val claim = captureCourseSurfaceRefreshClaim(journal)

        assertTrue(acknowledgeCourseSurfaceRefresh(journal, claim, surfaceRefreshed = true))
        assertFalse(journal.pendingToken() != null)
    }

    @Test
    fun `旧 Worker 不得清除刷新期间到达的新边界代际`() {
        val journal = FakeCourseSurfaceRefreshJournal()
        journal.markPending()
        val oldClaim = captureCourseSurfaceRefreshClaim(journal)
        val newToken = journal.markPending()

        assertTrue(acknowledgeCourseSurfaceRefresh(journal, oldClaim, surfaceRefreshed = true))
        assertTrue(journal.pendingToken() == newToken)
    }

    private class FakeCourseSurfaceRefreshJournal : CourseSurfaceRefreshJournal {
        private var token: String? = null
        private var generation = 0

        override fun pendingToken(): String? = token

        override fun markPending(): String {
            generation += 1
            return "surface-$generation".also { token = it }
        }

        override fun clearPendingIfMatches(token: String): Boolean {
            if (this.token == token) this.token = null
            return true
        }
    }
}
