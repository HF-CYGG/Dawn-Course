package com.dawncourse.feature.timetable.notification

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemScheduleReplayJournalTest {

    @Test
    fun `marker 写入失败仍尝试 WorkManager 入队`() = runBlocking {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = false)
        var enqueueCalled = false

        val accepted = persistAndEnqueueSystemScheduleReplay(journal) {
            enqueueCalled = true
            true
        }

        assertTrue(enqueueCalled)
        assertTrue(accepted)
        assertFalse(journal.pendingToken() != null)
    }

    @Test
    fun `WorkManager 入队失败时持久 marker 接管责任`() = runBlocking {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = true)

        val accepted = persistAndEnqueueSystemScheduleReplay(journal) { false }

        assertTrue(accepted)
        assertTrue(journal.pendingToken() != null)
    }

    @Test
    fun `marker 与 WorkManager 同时失败时报告未接管`() = runBlocking {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = false)

        val accepted = persistAndEnqueueSystemScheduleReplay(journal) { false }

        assertFalse(accepted)
        assertFalse(journal.pendingToken() != null)
    }

    @Test
    fun `WorkManager 入队成功后仍保留 marker 直到实际 force replay 完成`() = runBlocking {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = true)

        val accepted = persistAndEnqueueSystemScheduleReplay(journal) { true }

        assertTrue(accepted)
        assertTrue(journal.pendingToken() != null)
    }

    @Test
    fun `input false 但 marker 存在时仍强制重放`() {
        assertTrue(shouldForceReplay(inputForceReplay = false, markerPending = true))
        assertFalse(shouldForceReplay(inputForceReplay = false, markerPending = false))
    }

    @Test
    fun `marker 读取失败时保守认为仍待重放`() {
        val failingJournal = object : SystemScheduleReplayJournal {
            override fun pendingToken(): String? = error("read failed")
            override fun markPending(): String? = null
            override fun clearPendingIfMatches(token: String): Boolean = false
        }

        val claim = captureSystemScheduleReplayClaim(failingJournal)
        assertTrue(claim.isPending)
        assertTrue(claim.readFailed)
    }

    @Test
    fun `对账失败时不清 marker`() {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = true)
        journal.markPending()

        assertTrue(
            acknowledgeSystemScheduleReplay(
                journal = journal,
                claim = captureSystemScheduleReplayClaim(journal),
                triggerReconciled = false,
            )
        )
        assertTrue(journal.pendingToken() != null)
    }

    @Test
    fun `对账成功后才清 marker`() {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = true)
        journal.markPending()

        assertTrue(
            acknowledgeSystemScheduleReplay(
                journal = journal,
                claim = captureSystemScheduleReplayClaim(journal),
                triggerReconciled = true,
            )
        )
        assertFalse(journal.pendingToken() != null)
    }

    @Test
    fun `旧 Worker 不得清除对账期间到达的新系统事件代际`() {
        val journal = FakeSystemScheduleReplayJournal(markSucceeds = true)
        journal.markPending()
        val oldClaim = captureSystemScheduleReplayClaim(journal)
        val newToken = journal.markPending()

        assertTrue(
            acknowledgeSystemScheduleReplay(
                journal = journal,
                claim = oldClaim,
                triggerReconciled = true,
            )
        )
        assertTrue(newToken != null)
        assertTrue(journal.pendingToken() == newToken)
    }

    private class FakeSystemScheduleReplayJournal(
        private val markSucceeds: Boolean,
    ) : SystemScheduleReplayJournal {
        private var token: String? = null
        private var generation = 0

        override fun pendingToken(): String? = token

        override fun markPending(): String? {
            if (!markSucceeds) return null
            generation += 1
            return "token-$generation".also { token = it }
        }

        override fun clearPendingIfMatches(token: String): Boolean {
            if (this.token == token) this.token = null
            return true
        }
    }
}
