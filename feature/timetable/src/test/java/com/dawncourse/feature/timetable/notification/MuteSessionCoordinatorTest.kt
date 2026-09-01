package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静音会话读改写必须由单例协调器串行化。 */
class MuteSessionCoordinatorTest {

    @Test
    fun `并发双 mute 均建立责任且只首次切到震动`() {
        val store = FakeMuteSessionStore()
        val ringer = FakeRingerController(RingerState.NORMAL)
        val coordinator = MuteSessionCoordinator(store)
        runConcurrently(
            { coordinator.mute(key(1), ringer) },
            { coordinator.mute(key(2), ringer) }
        )

        assertEquals(setOf(key(1), key(2)), store.keys)
        assertEquals(1, ringer.vibrateWrites)
    }

    @Test
    fun `交错 mute unmute 保持剩余课程责任且不提前恢复`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1)))
        val ringer = FakeRingerController(RingerState.VIBRATE)
        val coordinator = MuteSessionCoordinator(store)
        coordinator.mute(key(2), ringer)
        coordinator.unmute(key(1), ringer)

        assertEquals(setOf(key(2)), store.keys)
        assertEquals(RingerState.VIBRATE, ringer.state)
        assertEquals(0, ringer.normalWrites)
    }

    @Test
    fun `覆盖升级中旧 mute 已执行时无新会话也可安全恢复`() {
        val store = FakeMuteSessionStore()
        val ringer = FakeRingerController(RingerState.VIBRATE)

        assertTrue(MuteSessionCoordinator(store).recoverLegacyUnmute(ringer))
        assertEquals(RingerState.NORMAL, ringer.state)
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun `legacy 桥不碰非震动状态或缺少 DND 权限`() {
        val store = FakeMuteSessionStore()
        val silent = FakeRingerController(RingerState.SILENT)
        val noPermission = FakeRingerController(RingerState.VIBRATE, hasPolicyAccess = false)

        assertFalse(MuteSessionCoordinator(store).recoverLegacyUnmute(silent))
        assertFalse(MuteSessionCoordinator(store).recoverLegacyUnmute(noPermission))
        assertEquals(0, silent.normalWrites)
        assertEquals(0, noPermission.normalWrites)
    }

    @Test
    fun `同刻双 unmute 仅最后会话恢复一次`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1), key(2)))
        val ringer = FakeRingerController(RingerState.VIBRATE)
        val coordinator = MuteSessionCoordinator(store)
        runConcurrently(
            { coordinator.unmute(key(1), ringer) },
            { coordinator.unmute(key(2), ringer) }
        )

        assertTrue(store.keys.isEmpty())
        assertEquals(RingerState.NORMAL, ringer.state)
        assertEquals(1, ringer.normalWrites)
    }

    @Test
    fun `最后会话恢复 setter 失败保留责任并请求有限重放`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1)))
        val ringer = FakeRingerController(RingerState.VIBRATE, failNormalWrites = true)
        val coordinator = MuteSessionCoordinator(store)

        assertEquals(MuteRecoveryOutcome.RetryRequired(1), coordinator.unmute(key(1), ringer))
        assertTrue(key(1) in store.keys)
        assertEquals(1, store.recoveryAttempt(key(1)))

        assertEquals(MuteRecoveryOutcome.RetryRequired(2), coordinator.unmute(key(1), ringer))
        assertEquals(MuteRecoveryOutcome.Exhausted, coordinator.unmute(key(1), ringer))
        assertEquals(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS, store.recoveryAttempt(key(1)))
        assertEquals(
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
            store.record(key(1))?.status
        )
        assertFalse(key(1) in store.activeKeys())
        assertTrue(key(1) in store.keys)
    }

    @Test
    fun `下一次重放恢复成功后才消费责任并清除计数`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1)))
        val ringer = FakeRingerController(RingerState.VIBRATE, failNormalWrites = true)
        val coordinator = MuteSessionCoordinator(store)
        assertEquals(MuteRecoveryOutcome.RetryRequired(1), coordinator.unmute(key(1), ringer))

        ringer.failNormalWrites = false
        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), ringer))

        assertFalse(key(1) in store.keys)
        assertEquals(0, store.recoveryAttempt(key(1)))
    }

    @Test
    fun `耗尽责任不阻断后续课程的最终恢复且责任仍保留`() {
        val exhausted = key(1)
        val current = key(2)
        val store = FakeMuteSessionStore(
            initialKeys = mutableSetOf(exhausted, current),
            attempts = mutableMapOf(
                exhausted to MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            )
        )
        val ringer = FakeRingerController(RingerState.VIBRATE)

        val outcome = MuteSessionCoordinator(store).unmute(current, ringer)

        assertEquals(MuteRecoveryOutcome.Recovered, outcome)
        assertEquals(setOf(exhausted), store.keys)
        assertEquals(RingerState.NORMAL, ringer.state)
        assertEquals(1, ringer.normalWrites)
    }

    @Test
    fun `存在新式活动会话时 legacy unmute 不恢复铃声`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1)))
        val ringer = FakeRingerController(RingerState.VIBRATE)

        assertFalse(MuteSessionCoordinator(store).recoverLegacyUnmute(ringer))
        assertEquals(RingerState.VIBRATE, ringer.state)
        assertEquals(0, ringer.normalWrites)
    }

    @Test
    fun `旧耗尽责任仍处于震动时新课程建立责任并在结束时恢复`() {
        val exhausted = key(1)
        val current = key(2)
        val store = FakeMuteSessionStore(
            initialKeys = mutableSetOf(exhausted),
            attempts = mutableMapOf(exhausted to MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        )
        val ringer = FakeRingerController(RingerState.VIBRATE)
        val coordinator = MuteSessionCoordinator(store)

        coordinator.mute(current, ringer)
        assertEquals(setOf(exhausted, current), store.keys)

        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(current, ringer))
        assertEquals(setOf(exhausted), store.keys)
        assertEquals(RingerState.NORMAL, ringer.state)
    }

    @Test
    fun `重叠会话释放当前责任不要求 DND 权限也不制造假重试`() {
        val first = key(1)
        val second = key(2)
        val store = FakeMuteSessionStore(mutableSetOf(first, second))
        val ringer = FakeRingerController(
            state = RingerState.VIBRATE,
            hasPolicyAccess = false
        )

        val outcome = MuteSessionCoordinator(store).unmute(first, ringer)

        assertEquals(MuteRecoveryOutcome.ResponsibilityReleased, outcome)
        assertEquals(setOf(second), store.keys)
        assertEquals(0, store.recoveryAttempt(first))
        assertEquals(0, ringer.normalWrites)
    }

    @Test
    fun `权限恢复后的受控重试成功并清理责任`() {
        val target = key(1)
        val store = FakeMuteSessionStore(
            initialKeys = mutableSetOf(target),
            attempts = mutableMapOf(target to MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.prepareUserRetry(target))
        assertEquals(MuteSessionStatus.RECOVERY_PENDING, store.record(target)?.status)
        assertEquals(0, store.recoveryAttempt(target))

        val outcome = coordinator.unmute(target, FakeRingerController(RingerState.VIBRATE))

        assertEquals(MuteRecoveryOutcome.Recovered, outcome)
        assertFalse(target in store.keys)
    }

    @Test
    fun `用户确认手动恢复后清理耗尽责任`() {
        val target = key(1)
        val store = FakeMuteSessionStore(
            initialKeys = mutableSetOf(target),
            attempts = mutableMapOf(target to MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        )

        assertTrue(MuteSessionCoordinator(store).releaseByUser(target))
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun `受控重试提交失败时可恢复为用户处理状态`() {
        val target = key(1)
        val store = FakeMuteSessionStore(
            initialKeys = mutableSetOf(target),
            attempts = mutableMapOf(target to MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.prepareUserRetry(target))
        assertTrue(coordinator.restoreExhaustedAfterFailedRetry(target))

        assertEquals(
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
            store.record(target)?.status
        )
        assertFalse(target in store.activeKeys())
    }

    @Test
    fun `用户已离开震动模式时只清理应用责任不改铃声`() {
        val store = FakeMuteSessionStore(mutableSetOf(key(1)), mutableMapOf(key(1) to 2))
        val ringer = FakeRingerController(RingerState.SILENT)

        val outcome = MuteSessionCoordinator(store).unmute(key(1), ringer)

        assertEquals(MuteRecoveryOutcome.ResponsibilityReleased, outcome)
        assertTrue(store.keys.isEmpty())
        assertEquals(0, store.recoveryAttempt(key(1)))
        assertEquals(0, ringer.normalWrites)
    }

    private fun runConcurrently(first: () -> Unit, second: () -> Unit) {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = listOf(first, second).map { action ->
            executor.submit {
                start.await()
                action()
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        executor.shutdownNow()
    }

    private class FakeMuteSessionStore(
        initialKeys: MutableSet<TriggerKey> = mutableSetOf(),
        attempts: MutableMap<TriggerKey, Int> = mutableMapOf()
    ) : MuteSessionPersistence {
        private val storedRecords = initialKeys.associateWithTo(mutableMapOf()) { key ->
            val attempt = attempts[key] ?: 0
            MuteSessionRecord(
                key = key,
                status = when {
                    attempt >= MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS ->
                        MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
                    attempt > 0 -> MuteSessionStatus.RECOVERY_PENDING
                    else -> MuteSessionStatus.ACTIVE
                },
                recoveryAttempt = attempt
            )
        }
        val keys: Set<TriggerKey>
            get() = storedRecords.keys.toSet()

        override fun records(): Set<MuteSessionRecord> = storedRecords.values.toSet()
        override fun add(key: TriggerKey): Boolean {
            if (key in storedRecords) return false
            storedRecords[key] = MuteSessionRecord(key, MuteSessionStatus.ACTIVE, 0)
            return true
        }
        override fun remove(key: TriggerKey) { storedRecords.remove(key) }
        override fun consume(key: TriggerKey): ConsumedMuteSession =
            ConsumedMuteSession(storedRecords.remove(key) != null, activeKeys().size)
        override fun recoveryAttempt(key: TriggerKey): Int = storedRecords[key]?.recoveryAttempt ?: 0
        override fun recordRecoveryFailure(key: TriggerKey): Int {
            val next = (recoveryAttempt(key) + 1).coerceAtMost(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
            val current = storedRecords.getValue(key)
            storedRecords[key] = current.copy(
                status = if (next < MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS) {
                    MuteSessionStatus.RECOVERY_PENDING
                } else {
                    MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
                },
                recoveryAttempt = next
            )
            return next
        }
        override fun clearRecoveryAttempt(key: TriggerKey) {
            storedRecords[key]?.let { record ->
                storedRecords[key] = record.copy(status = MuteSessionStatus.ACTIVE, recoveryAttempt = 0)
            }
        }
        override fun prepareUserRetry(key: TriggerKey): Boolean {
            val current = storedRecords[key]
                ?.takeIf { record -> record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED }
                ?: return false
            storedRecords[key] = current.copy(
                status = MuteSessionStatus.RECOVERY_PENDING,
                recoveryAttempt = 0
            )
            return true
        }
        override fun releaseByUser(key: TriggerKey): Boolean {
            return storedRecords.remove(key) != null
        }
        override fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean {
            val current = storedRecords[key]
                ?.takeIf { record -> record.status == MuteSessionStatus.RECOVERY_PENDING }
                ?: return false
            storedRecords[key] = current.copy(
                status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
                recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            )
            return true
        }
    }

    private class FakeRingerController(
        override var state: RingerState,
        var failNormalWrites: Boolean = false,
        override val hasPolicyAccess: Boolean = true
    ) : RingerController {
        var normalWrites = 0
        var vibrateWrites = 0
        override fun setVibrate() {
            vibrateWrites += 1
            state = RingerState.VIBRATE
        }
        override fun setNormal() {
            normalWrites += 1
            if (failNormalWrites) throw IllegalStateException("ROM rejected")
            state = RingerState.NORMAL
        }
    }

    private fun key(courseId: Long) = TriggerKey(
        profileId = 0,
        courseId = courseId,
        occurrenceDate = LocalDate.of(2026, 8, 24),
        kind = TriggerKind.UNMUTE
    )
}
