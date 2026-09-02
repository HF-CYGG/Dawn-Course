package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteSessionV3CoordinatorTest {

    @Test
    fun `有权限时重叠课程只激活一次应用 DND 且最后会话才撤销`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = true,
            hasPolicyAccess = true,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        assertTrue(coordinator.mute(key(2), system))
        assertEquals(1, system.dndActivations)
        assertEquals(0, system.vibrateWrites)
        assertTrue(store.records().all { it.ownership.mode == MuteApplicationMode.APP_OWNED_DND })

        assertEquals(MuteRecoveryOutcome.ResponsibilityReleased, coordinator.unmute(key(1), system))
        assertEquals(0, system.dndDeactivations)
        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(2), system))
        assertEquals(1, system.dndDeactivations)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `无 DND 权限时降级震动并仅在系统仍为应用设置值时恢复`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = true,
            hasPolicyAccess = false,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        assertEquals(RingerState.VIBRATE, system.state)
        assertEquals(MuteApplicationMode.RINGER_VIBRATE_FALLBACK, store.record(key(1))?.ownership?.mode)
        assertEquals(RingerState.NORMAL, store.record(key(1))?.ownership?.originalRingerState)

        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), system))
        assertEquals(RingerState.NORMAL, system.state)
        assertEquals(1, system.normalWrites)
    }

    @Test
    fun `用户中途手动改成静音时最后会话只释放责任不覆盖用户选择`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        system.state = RingerState.SILENT

        assertEquals(MuteRecoveryOutcome.ResponsibilityReleased, coordinator.unmute(key(1), system))
        assertEquals(RingerState.SILENT, system.state)
        assertEquals(0, system.normalWrites)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `系统静默拒绝震动写入时回滚责任并报告失败`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
            acceptRingerWrites = false,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertFalse(coordinator.mute(key(1), system))
        assertEquals(RingerState.NORMAL, system.state)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `DND 会话期间权限撤销时保留责任等待有限恢复`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.VIBRATE,
            supportsAppOwnedDnd = true,
            hasPolicyAccess = true,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        system.hasPolicyAccess = false

        assertEquals(MuteRecoveryOutcome.RetryRequired(1), coordinator.unmute(key(1), system))
        assertTrue(store.record(key(1)) != null)
        assertEquals(0, system.dndDeactivations)
    }

    @Test
    fun `DND 已生效但回包异常时保留复合责任并最终同时撤销 DND 和震动`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = true,
            hasPolicyAccess = true,
            dndActivationThrowsAfterApplying = true,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        assertTrue(system.dndActive)
        assertEquals(RingerState.VIBRATE, system.state)
        val ownership = store.record(key(1))?.ownership
        assertEquals(MuteApplicationMode.APP_OWNED_DND, ownership?.mode)
        assertTrue(ownership?.appDndActivationOwned == true)
        assertEquals(RingerState.VIBRATE, ownership?.ownedRingerState)

        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), system))
        assertFalse(system.dndActive)
        assertEquals(RingerState.NORMAL, system.state)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `DND 与震动结果不确定且状态暂时不可读时保留复合责任`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = true,
            hasPolicyAccess = true,
            dndActivationThrowsAfterApplying = true,
            vibrateThrowsAfterApplying = true,
            stateReadFailuresAfterVibrate = 1,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        val ownership = store.record(key(1))?.ownership
        assertEquals(MuteApplicationMode.APP_OWNED_DND, ownership?.mode)
        assertEquals(RingerState.VIBRATE, ownership?.ownedRingerState)
        assertTrue(ownership?.appDndActivationOwned == true)

        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), system))
        assertFalse(system.dndActive)
        assertEquals(RingerState.NORMAL, system.state)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `震动已生效但 setter 抛异常时保留责任并确保恢复`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
            vibrateThrowsAfterApplying = true,
        )
        val coordinator = MuteSessionCoordinator(store)

        assertTrue(coordinator.mute(key(1), system))
        assertTrue(store.record(key(1)) != null)
        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), system))
        assertEquals(RingerState.NORMAL, system.state)
    }

    @Test
    fun `震动确定未生效且 setter 抛异常时删除未建立责任`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
            vibrateThrowsBeforeApplying = true,
        )
        val coordinator = MuteSessionCoordinator(store)

        runCatching { coordinator.mute(key(1), system) }
        assertEquals(RingerState.NORMAL, system.state)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `恢复正常已生效但回包异常时消费责任`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
            normalThrowsAfterApplying = true,
        )
        val coordinator = MuteSessionCoordinator(store)
        assertTrue(coordinator.mute(key(1), system))

        assertEquals(MuteRecoveryOutcome.Recovered, coordinator.unmute(key(1), system))
        assertEquals(RingerState.NORMAL, system.state)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `恢复正常被静默拒绝时保留责任并进入有限重试`() {
        val store = InMemoryMutePersistence()
        val system = FakeMuteSystemController(
            state = RingerState.NORMAL,
            supportsAppOwnedDnd = false,
            hasPolicyAccess = false,
            acceptNormalWrites = false,
        )
        val coordinator = MuteSessionCoordinator(store)
        assertTrue(coordinator.mute(key(1), system))

        assertEquals(MuteRecoveryOutcome.RetryRequired(1), coordinator.unmute(key(1), system))
        assertEquals(RingerState.VIBRATE, system.state)
        assertTrue(store.record(key(1)) != null)
    }

    private class InMemoryMutePersistence : MuteSessionPersistence {
        private val values = linkedMapOf<TriggerKey, MuteSessionRecord>()

        override fun records(): Set<MuteSessionRecord> = values.values.toSet()
        override fun add(key: TriggerKey): Boolean = add(
            key,
            recoveryAt = null,
            ownership = MuteSystemOwnership.legacyV2(),
        )
        override fun add(key: TriggerKey, recoveryAt: Instant?): Boolean = add(
            key,
            recoveryAt,
            MuteSystemOwnership.legacyV2(),
        )
        override fun add(
            key: TriggerKey,
            recoveryAt: Instant?,
            ownership: MuteSystemOwnership,
        ): Boolean {
            if (values.containsKey(key)) return false
            values[key] = MuteSessionRecord(
                key = key,
                status = MuteSessionStatus.ACTIVE,
                recoveryAttempt = 0,
                recoveryAt = recoveryAt,
                ownership = ownership,
            )
            return true
        }
        override fun replaceOwnershipForActiveSessions(ownership: MuteSystemOwnership) {
            values.replaceAll { _, record ->
                if (record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED) record
                else record.copy(ownership = ownership)
            }
        }
        override fun remove(key: TriggerKey) { values.remove(key) }
        override fun consume(key: TriggerKey): ConsumedMuteSession =
            ConsumedMuteSession(values.remove(key) != null, activeKeys().size)
        override fun recoveryAttempt(key: TriggerKey): Int = values[key]?.recoveryAttempt ?: 0
        override fun recordRecoveryFailure(key: TriggerKey): Int {
            val current = values.getValue(key)
            val next = (current.recoveryAttempt + 1).coerceAtMost(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
            values[key] = current.copy(
                status = if (next < MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS) {
                    MuteSessionStatus.RECOVERY_PENDING
                } else {
                    MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
                },
                recoveryAttempt = next,
            )
            return next
        }
        override fun clearRecoveryAttempt(key: TriggerKey) = Unit
        override fun prepareUserRetry(key: TriggerKey): Boolean = false
        override fun releaseByUser(key: TriggerKey): Boolean = values.remove(key) != null
        override fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean = false
        override fun requireUserAction(key: TriggerKey): Boolean = false
    }

    private class FakeMuteSystemController(
        state: RingerState,
        override val supportsAppOwnedDnd: Boolean,
        override var hasPolicyAccess: Boolean,
        private val acceptRingerWrites: Boolean = true,
        private val dndActivationThrowsAfterApplying: Boolean = false,
        private val vibrateThrowsAfterApplying: Boolean = false,
        private val vibrateThrowsBeforeApplying: Boolean = false,
        private val normalThrowsAfterApplying: Boolean = false,
        private val acceptNormalWrites: Boolean = true,
        stateReadFailuresAfterVibrate: Int = 0,
    ) : RingerController {
        private var actualState = state
        private var remainingStateReadFailuresAfterVibrate = stateReadFailuresAfterVibrate
        override var state: RingerState
            get() {
                if (actualState == RingerState.VIBRATE && remainingStateReadFailuresAfterVibrate > 0) {
                    remainingStateReadFailuresAfterVibrate -= 1
                    throw IllegalStateException("Binder state reply lost")
                }
                return actualState
            }
            set(value) {
                actualState = value
            }
        var dndActivations = 0
        var dndDeactivations = 0
        var vibrateWrites = 0
        var normalWrites = 0
        var dndActive = false

        override fun activateAppOwnedDnd(): Boolean {
            dndActivations += 1
            dndActive = true
            if (dndActivationThrowsAfterApplying) throw IllegalStateException("Binder reply lost")
            return true
        }

        override fun deactivateAppOwnedDnd(): Boolean {
            dndDeactivations += 1
            dndActive = false
            return true
        }

        override fun setVibrate() {
            vibrateWrites += 1
            if (vibrateThrowsBeforeApplying) throw IllegalStateException("Binder rejected")
            if (acceptRingerWrites) state = RingerState.VIBRATE
            if (vibrateThrowsAfterApplying) throw IllegalStateException("Binder reply lost")
        }

        override fun setNormal() {
            normalWrites += 1
            if (acceptNormalWrites) state = RingerState.NORMAL
            if (normalThrowsAfterApplying) throw IllegalStateException("Binder reply lost")
        }
    }

    private fun key(courseId: Long) = TriggerKey(
        profileId = 1,
        courseId = courseId,
        occurrenceDate = LocalDate.of(2026, 9, 2),
        kind = TriggerKind.UNMUTE,
    )
}
