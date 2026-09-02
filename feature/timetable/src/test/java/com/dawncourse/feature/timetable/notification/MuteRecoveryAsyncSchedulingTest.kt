package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** WorkManager Operation 的异步失败必须反馈到持久状态机。 */
class MuteRecoveryAsyncSchedulingTest {
    private val key = TriggerKey(0, 21, LocalDate.of(2026, 8, 25), TriggerKind.UNMUTE)

    @Test
    fun `用户 retry 异步 enqueue 失败回滚 EXHAUSTED 并刷新警示`() = runBlocking {
        val store = FakePersistence(exhausted(key))
        val scheduler = FakeScheduler(enqueueSuccess = false)
        val attention = FakeAttention()
        val controller = MuteRecoveryUserActionController(
            store = store,
            coordinator = MuteSessionCoordinator(store),
            scheduler = scheduler,
            notificationHelper = attention
        )

        assertFalse(controller.retry(key))
        assertEquals(MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED, store.record(key)?.status)
        assertEquals(1, scheduler.nowRequests)
        assertEquals(1, attention.refreshes)
    }

    @Test
    fun `Receiver 首次恢复调度异步失败转 EXHAUSTED`() = runBlocking {
        val store = FakePersistence(
            MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 1)
        )
        val scheduler = FakeScheduler(enqueueSuccess = false)
        val attention = FakeAttention()
        val dispatcher = MuteRecoveryOutcomeDispatcher(
            coordinator = MuteSessionCoordinator(store),
            scheduler = scheduler,
            notificationHelper = attention
        )

        dispatcher.dispatch(key, MuteRecoveryOutcome.RetryRequired(1))

        assertEquals(MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED, store.record(key)?.status)
        assertEquals(1, scheduler.retryRequests)
        assertEquals(1, attention.refreshes)
    }

    @Test
    fun `异步 enqueue adapter 传播 Operation 失败而不阻塞调用线程`() = runBlocking {
        val callingThread = Thread.currentThread()
        var callbackThread: Thread? = null
        val executor = Executors.newSingleThreadExecutor()
        val result = try {
            awaitEnqueueCompletion { complete ->
                executor.execute {
                    callbackThread = Thread.currentThread()
                    complete(false)
                }
            }
        } finally {
            executor.shutdownNow()
        }

        assertFalse(result)
        assertNotEquals(callingThread, callbackThread)
    }

    @Test
    fun `启动对账从 ACTIVE recoveryAt 重建独立 key addressed work`() = runBlocking {
        val recoveryAt = Instant.now().plusSeconds(3600)
        val store = FakePersistence(
            MuteSessionRecord(key, MuteSessionStatus.ACTIVE, 0, recoveryAt)
        )
        val scheduler = FakeScheduler(enqueueSuccess = true)
        val attention = FakeAttention()
        val controller = MuteRecoveryUserActionController(
            store = store,
            coordinator = MuteSessionCoordinator(store),
            scheduler = scheduler,
            notificationHelper = attention
        )

        controller.reconcilePersistedState()

        assertEquals(listOf(key to recoveryAt), scheduler.atRequests)
        assertEquals(MuteSessionStatus.ACTIVE, store.record(key)?.status)
        assertEquals(1, attention.refreshes)
    }

    @Test
    fun `Worker 基础设施异常使用持久次数并在第三次升级为 EXHAUSTED`() {
        val store = FakePersistence(
            MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 2)
        )

        val outcome = MuteSessionCoordinator(store).recordWorkerFailure(key)

        assertEquals(MuteRecoveryOutcome.Exhausted, outcome)
        assertEquals(MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED, store.record(key)?.status)
        assertEquals(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS, store.record(key)?.recoveryAttempt)
    }

    @Test
    fun `Worker 基础设施异常无法持久记录时向上传播供 WorkManager 重试`() {
        val store = FakePersistence(
            initial = MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 2),
            recordFailure = IOException("commit failed")
        )

        try {
            MuteSessionCoordinator(store).recordWorkerFailure(key)
            fail("持久计数失败必须向上传播")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(MuteSessionStatus.RECOVERY_PENDING, store.record(key)?.status)
    }

    @Test
    fun `Worker 基础设施异常发现责任已并发清除时返回 NoAction`() {
        val store = FakePersistence(
            MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 2)
        )
        store.remove(key)

        val outcome = MuteSessionCoordinator(store).recordWorkerFailure(key)

        assertEquals(MuteRecoveryOutcome.NoAction, outcome)
    }

    @Test
    fun `Worker 请求被 REPLACE 后仍沿用持久失败次数而不是 runAttemptCount`() {
        val store = FakePersistence(
            MuteSessionRecord(key, MuteSessionStatus.RECOVERY_PENDING, 1)
        )
        val coordinator = MuteSessionCoordinator(store)

        assertEquals(MuteRecoveryOutcome.RetryRequired(2), coordinator.recordWorkerFailure(key))
        assertEquals(MuteRecoveryOutcome.Exhausted, coordinator.recordWorkerFailure(key))
        assertEquals(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS, store.record(key)?.recoveryAttempt)
    }

    @Test
    fun `用户 retry 入队被取消时向上传播并保留 PENDING 供启动修补`() = runBlocking {
        val store = FakePersistence(exhausted(key))
        val scheduler = FakeScheduler(
            enqueueSuccess = true,
            enqueueFailure = CancellationException("cancelled")
        )
        val controller = MuteRecoveryUserActionController(
            store = store,
            coordinator = MuteSessionCoordinator(store),
            scheduler = scheduler,
            notificationHelper = FakeAttention()
        )

        try {
            controller.retry(key)
            fail("CancellationException 应向上传播")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(MuteSessionStatus.RECOVERY_PENDING, store.record(key)?.status)
    }

    private class FakePersistence(
        initial: MuteSessionRecord,
        private val recordFailure: Throwable? = null
    ) : MuteSessionPersistence {
        private val values = mutableMapOf(initial.key to initial)

        override fun records(): Set<MuteSessionRecord> = values.values.toSet()
        override fun observeRecords(): Flow<Set<MuteSessionRecord>> = flowOf(records())
        override fun add(key: TriggerKey): Boolean = false
        override fun remove(key: TriggerKey) { values.remove(key) }
        override fun consume(key: TriggerKey): ConsumedMuteSession =
            ConsumedMuteSession(values.remove(key) != null, activeKeys().size)
        override fun recoveryAttempt(key: TriggerKey): Int = values[key]?.recoveryAttempt ?: 0
        override fun recordRecoveryFailure(key: TriggerKey): Int {
            recordFailure?.let { throw it }
            val current = values[key] ?: throw IOException("missing")
            val next = (current.recoveryAttempt + 1)
                .coerceAtMost(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
            values[key] = current.copy(
                status = if (next < MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS) {
                    MuteSessionStatus.RECOVERY_PENDING
                } else {
                    MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
                },
                recoveryAttempt = next
            )
            return next
        }
        override fun clearRecoveryAttempt(key: TriggerKey) = Unit
        override fun prepareUserRetry(key: TriggerKey): Boolean {
            val current = values[key]
                ?.takeIf { it.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED }
                ?: return false
            values[key] = current.copy(status = MuteSessionStatus.RECOVERY_PENDING, recoveryAttempt = 0)
            return true
        }
        override fun releaseByUser(key: TriggerKey): Boolean = values.remove(key) != null
        override fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean =
            requireUserAction(key)
        override fun requireUserAction(key: TriggerKey): Boolean {
            val current = values[key] ?: return false
            values[key] = current.copy(
                status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
                recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            )
            return true
        }
    }

    private class FakeScheduler(
        private val enqueueSuccess: Boolean,
        private val enqueueFailure: Throwable? = null
    ) : MuteRecoveryWorkScheduler {
        var nowRequests = 0
        var retryRequests = 0
        val atRequests = mutableListOf<Pair<TriggerKey, Instant>>()
        override suspend fun enqueueRetry(key: TriggerKey, attempt: Int): Boolean {
            retryRequests += 1
            enqueueFailure?.let { throw it }
            return enqueueSuccess
        }
        override suspend fun enqueueNow(key: TriggerKey): Boolean {
            nowRequests += 1
            enqueueFailure?.let { throw it }
            return enqueueSuccess
        }
        override suspend fun enqueueAt(key: TriggerKey, runAt: Instant): Boolean {
            atRequests += key to runAt
            enqueueFailure?.let { throw it }
            return enqueueSuccess
        }
        override fun cancel(key: TriggerKey) = Unit
    }

    private class FakeAttention : MuteRecoveryAttention {
        var refreshes = 0
        override fun showExhausted() = Unit
        override fun cancel() = Unit
        override fun refreshForCurrentState() { refreshes += 1 }
    }

    private fun exhausted(key: TriggerKey) = MuteSessionRecord(
        key,
        MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
        MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
    )
}
