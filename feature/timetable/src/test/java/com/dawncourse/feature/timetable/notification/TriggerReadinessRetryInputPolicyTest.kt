package com.dawncourse.feature.timetable.notification

import androidx.work.workDataOf
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerPrecision
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 触发就绪重试 Worker 的输入必须完整，且只接受 REMINDER / MUTE Key。 */
class TriggerReadinessRetryInputPolicyTest {
    private val reminderKey = TriggerKey(7, 42, LocalDate.of(2026, 8, 25), TriggerKind.REMINDER)
    private val muteKey = reminderKey.copy(kind = TriggerKind.MUTE)
    private val unmuteKey = reminderKey.copy(kind = TriggerKind.UNMUTE)

    @Test
    fun `REMINDER 与 MUTE 的 Work Data 携带完整 URI 并可往返`() {
        listOf(reminderKey, muteKey).forEach { key ->
            val input = TriggerReadinessRetryInputPolicy.createInputData(key, precision = null)

            assertEquals(
                TriggerUriCodec.encode(key),
                input.getString(TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI)
            )
            assertEquals(key, TriggerReadinessRetryInputPolicy.decode(input))
            assertNull(TriggerReadinessRetryInputPolicy.decodePrecision(input))
        }
    }

    @Test
    fun `精度随任务持久保存并可解出`() {
        val input = TriggerReadinessRetryInputPolicy.createInputData(
            reminderKey,
            precision = TriggerPrecision.INEXACT
        )
        assertEquals(reminderKey, TriggerReadinessRetryInputPolicy.decode(input))
        assertEquals(TriggerPrecision.INEXACT, TriggerReadinessRetryInputPolicy.decodePrecision(input))
        // 损坏精度值不影响 Key 解码，精度回退为 null。
        val corrupted = workDataOf(
            TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI to TriggerUriCodec.encode(reminderKey),
            TriggerReadinessRetryInputPolicy.INPUT_PRECISION to "NOT_A_PRECISION"
        )
        assertEquals(reminderKey, TriggerReadinessRetryInputPolicy.decode(corrupted))
        assertNull(TriggerReadinessRetryInputPolicy.decodePrecision(corrupted))
    }

    @Test
    fun `拒绝 UNMUTE 缺失与非规范 URI`() {
        assertThrows(IllegalArgumentException::class.java) {
            TriggerReadinessRetryInputPolicy.createInputData(unmuteKey, precision = null)
        }
        assertNull(TriggerReadinessRetryInputPolicy.decode(workDataOf()))
        assertNull(
            TriggerReadinessRetryInputPolicy.decode(
                workDataOf(
                    TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI to
                        TriggerUriCodec.encode(unmuteKey)
                )
            )
        )
        assertNull(
            TriggerReadinessRetryInputPolicy.decode(
                workDataOf(
                    TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI to
                        "dawn://alarm/7/42/2026-08-25/REMINDER"
                )
            )
        )
    }

    @Test
    fun `唯一任务名随 Key 稳定变化`() {
        assertEquals(
            "TriggerReadinessRetry:${TriggerUriCodec.encode(reminderKey)}",
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey)
        )
        assertEquals(
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey),
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey)
        )
        assertEquals(
            false,
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey) ==
                WorkManagerTriggerReadinessRetryScheduler.workName(muteKey)
        )
    }

    @Test
    fun `WorkManager 异步入队成功后才清理持久补投责任`() = runBlocking {
        val journal = FakeRetryJournal()
        val record = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.INEXACT)

        val enqueued = persistAndEnqueueReadinessRetry(record, journal) { true }

        assertTrue(enqueued)
        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun `WorkManager 异步入队失败时保留持久补投责任供冷启动重放`() = runBlocking {
        val journal = FakeRetryJournal()
        val record = TriggerReadinessRetryRecord(muteKey, precision = null)

        val enqueued = persistAndEnqueueReadinessRetry(record, journal) { false }

        assertFalse(enqueued)
        assertEquals(setOf(record), journal.records())
    }

    @Test
    fun `journal 写入失败仍尝试 WorkManager 且入队成功即接管责任`() = runBlocking {
        val journal = FakeRetryJournal(failPut = true)
        val record = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.EXACT)
        var enqueueCalled = false

        val enqueued = persistAndEnqueueReadinessRetry(record, journal) {
            enqueueCalled = true
            true
        }

        assertTrue(enqueueCalled)
        assertTrue(enqueued)
        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun `journal 与 WorkManager 同时失败时报告责任未接管`() = runBlocking {
        val journal = FakeRetryJournal(failPut = true)
        val record = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.EXACT)
        var enqueueCalled = false

        val enqueued = persistAndEnqueueReadinessRetry(record, journal) {
            enqueueCalled = true
            false
        }

        assertTrue(enqueueCalled)
        assertFalse(enqueued)
        assertTrue(journal.records().isEmpty())
    }

    @Test
    fun `WorkManager 成功但 journal 清理失败仍报告已接管并保留幂等重放`() = runBlocking {
        val journal = FakeRetryJournal(failRemove = true)
        val record = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.EXACT)

        val enqueued = persistAndEnqueueReadinessRetry(record, journal) { true }

        assertTrue(enqueued)
        assertEquals(setOf(record), journal.records())
    }

    @Test
    fun `补投入队协程被取消时保留 journal 并传播取消`() = runBlocking {
        val journal = FakeRetryJournal()
        val record = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.EXACT)

        try {
            persistAndEnqueueReadinessRetry(record, journal) {
                throw CancellationException("cancelled")
            }
            fail("CancellationException 应向上传播")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(setOf(record), journal.records())
    }

    @Test
    fun `journal 重放任一入队未确认时向每日调度报告失败`() = runBlocking {
        val first = TriggerReadinessRetryRecord(reminderKey, TriggerPrecision.EXACT)
        val second = TriggerReadinessRetryRecord(muteKey, null)
        val attempts = mutableListOf<TriggerKey>()

        val success = reconcileTriggerReadinessRetryRecords(setOf(first, second)) { record ->
            attempts += record.key
            record.key != muteKey
        }

        assertFalse(success)
        assertEquals(setOf(reminderKey, muteKey), attempts.toSet())
    }

    private class FakeRetryJournal(
        private val failPut: Boolean = false,
        private val failRemove: Boolean = false,
    ) : TriggerReadinessRetryJournal {
        private val values = linkedSetOf<TriggerReadinessRetryRecord>()

        override fun records(): Set<TriggerReadinessRetryRecord> = values.toSet()

        override fun put(record: TriggerReadinessRetryRecord) {
            if (failPut) error("put failed")
            values.removeAll { current -> current.key == record.key }
            values += record
        }

        override fun remove(key: TriggerKey) {
            if (failRemove) error("remove failed")
            values.removeAll { record -> record.key == key }
        }
    }
}
