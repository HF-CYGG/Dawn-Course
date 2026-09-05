package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 应用静音恢复责任的显式持久状态。 */
enum class MuteSessionStatus {
    /** 课程仍在进行，等待正常 UNMUTE 边界。 */
    ACTIVE,

    /** 自动恢复失败但仍处于有限重试窗口。 */
    RECOVERY_PENDING,

    /** 自动重试已耗尽，必须由用户明确处理。 */
    EXHAUSTED_USER_ACTION_REQUIRED
}

/** 一条可跨进程重启恢复的应用静音责任。 */
data class MuteSessionRecord(
    /** 对应课程 occurrence 的完整 UNMUTE Key。 */
    val key: TriggerKey,
    /** 当前恢复阶段。 */
    val status: MuteSessionStatus,
    /** 已失败的自动恢复次数。 */
    val recoveryAttempt: Int,
    /** 独立于 Alarm registry 持久保存的课程结束恢复时刻。 */
    val recoveryAt: Instant? = null,
    /** v3 系统状态所有权；重叠会话复制同一份租约，最后一个会话负责恢复。 */
    val ownership: MuteSystemOwnership = MuteSystemOwnership.legacyV2(),
)

/** 应用施加静音效果的方式。 */
enum class MuteApplicationMode {
    /** Android 15+ 由 setInterruptionFilter 创建/更新的应用隐式 AutomaticZenRule。 */
    APP_OWNED_DND,
    /** 无 DND 权限或隐式规则激活失败时，仅把 NORMAL 降级到 VIBRATE。 */
    RINGER_VIBRATE_FALLBACK,
    /** v2 兼容责任：只能恢复应用此前设置的 VIBRATE，绝不能调用 DND ALL。 */
    LEGACY_V2_VIBRATE,
    /** 损坏或未知记录的隔离态，禁止自动修改任何系统状态。 */
    UNKNOWN_QUARANTINED,
}

/** 跨进程保存的系统静音租约。 */
data class MuteSystemOwnership(
    val mode: MuteApplicationMode,
    val originalRingerState: RingerState,
    val ownedRingerState: RingerState?,
    val appDndActivationOwned: Boolean,
) {
    companion object {
        fun legacyV2(): MuteSystemOwnership = MuteSystemOwnership(
            mode = MuteApplicationMode.LEGACY_V2_VIBRATE,
            originalRingerState = RingerState.NORMAL,
            ownedRingerState = RingerState.VIBRATE,
            appDndActivationOwned = false,
        )

        fun quarantined(): MuteSystemOwnership = MuteSystemOwnership(
            mode = MuteApplicationMode.UNKNOWN_QUARANTINED,
            originalRingerState = RingerState.NORMAL,
            ownedRingerState = null,
            appDndActivationOwned = false,
        )
    }
}

/** 可替换的持久化边界，供纯 JVM 状态机与并发测试使用。 */
interface MuteSessionPersistence {
    /** 返回全部恢复责任，包括不再阻断后续课程的 EXHAUSTED 责任。 */
    fun records(): Set<MuteSessionRecord>

    /** 观察完整责任快照；Android Store 会以 SharedPreferences listener 实现。 */
    fun observeRecords(): Flow<Set<MuteSessionRecord>> = emptyFlow()

    /** 仅 ACTIVE/PENDING 会阻断当前重叠课程的最终恢复。 */
    fun activeKeys(): Set<TriggerKey> = records()
        .filterNot { record -> record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED }
        .mapTo(mutableSetOf()) { record -> record.key }

    /** Reconciler 不得取消的全部责任 Key。 */
    fun protectedKeys(): Set<TriggerKey> = records().mapTo(mutableSetOf()) { record -> record.key }

    /** 查找单条恢复责任。 */
    fun record(key: TriggerKey): MuteSessionRecord? = records().firstOrNull { record -> record.key == key }

    /** 新建 ACTIVE 责任。 */
    fun add(key: TriggerKey): Boolean

    /** 新建 ACTIVE 责任并持久保存独立恢复边界。 */
    fun add(key: TriggerKey, recoveryAt: Instant?): Boolean = add(key)

    /** v3 新建责任，同时原子保存系统状态租约。 */
    fun add(
        key: TriggerKey,
        recoveryAt: Instant?,
        ownership: MuteSystemOwnership,
    ): Boolean = add(key, recoveryAt)

    /** DND 激活失败转入震动时，更新全部活动重叠会话的同一租约。 */
    fun replaceOwnershipForActiveSessions(ownership: MuteSystemOwnership) = Unit

    /** 回滚未能建立的责任。 */
    fun remove(key: TriggerKey)

    /** 消费指定责任并返回剩余活动阻断数。 */
    fun consume(key: TriggerKey): ConsumedMuteSession

    /** 返回已失败次数。 */
    fun recoveryAttempt(key: TriggerKey): Int

    /** 记录一次失败并完成 PENDING/EXHAUSTED 状态转换。 */
    fun recordRecoveryFailure(key: TriggerKey): Int

    /** 清理失败次数；责任不存在时为幂等操作。 */
    fun clearRecoveryAttempt(key: TriggerKey)

    /** 用户明确重试时把 EXHAUSTED 重置为 PENDING(0)。 */
    fun prepareUserRetry(key: TriggerKey): Boolean

    /** 用户确认已手动恢复或放弃责任。 */
    fun releaseByUser(key: TriggerKey): Boolean

    /** 用户重试 Work 未能提交时恢复 EXHAUSTED，避免责任从 UI 静默消失。 */
    fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean

    /** 任一恢复 Work 无法入队时升级为显式用户处理责任。 */
    fun requireUserAction(key: TriggerKey): Boolean
}

/** 与 Android AudioManager 解耦的铃声状态。 */
enum class RingerState { NORMAL, VIBRATE, SILENT }

/** 铃声系统调用边界。 */
interface RingerController {
    /** 当前系统铃声模式。 */
    val state: RingerState

    /** 是否拥有勿扰策略访问权限。 */
    val hasPolicyAccess: Boolean

    /** 当前平台能否用应用隐式 AutomaticZenRule，而不修改全局 DND。 */
    val supportsAppOwnedDnd: Boolean
        get() = false

    /** 激活 Dawn Course 自己的隐式规则；不得修改其他应用或用户规则。 */
    fun activateAppOwnedDnd(): Boolean = false

    /** 只撤销 Dawn Course 自己的隐式规则贡献。 */
    fun deactivateAppOwnedDnd(): Boolean = false

    /** 切换为震动。 */
    fun setVibrate()

    /** 恢复为正常响铃。 */
    fun setNormal()
}

/** UNMUTE 结果决定专用 Worker 是否安排下一次有限恢复。 */
sealed interface MuteRecoveryOutcome {
    /** 系统铃声已恢复且责任已清理。 */
    data object Recovered : MuteRecoveryOutcome

    /** 用户已改变铃声或仍有其他重叠会话，当前责任已释放。 */
    data object ResponsibilityReleased : MuteRecoveryOutcome

    /** Key 已不存在，无需执行。 */
    data object NoAction : MuteRecoveryOutcome

    /** 仍可安排下一次专用恢复 Worker。 */
    data class RetryRequired(val attempt: Int) : MuteRecoveryOutcome

    /** 自动恢复次数耗尽，必须由用户处理。 */
    data object Exhausted : MuteRecoveryOutcome
}

/**
 * 单例锁内原子执行会话持久化与铃声读改写，避免重叠课程丢失恢复责任。
 */
@Singleton
class MuteSessionCoordinator @Inject constructor(
    private val persistence: MuteSessionPersistence
) {
    /** 首个会话建立系统状态租约，重叠会话复制租约且不重复写系统状态。 */
    @Synchronized
    fun mute(
        unmuteKey: TriggerKey,
        ringer: RingerController,
        recoveryAt: Instant? = null
    ): Boolean {
        val records = persistence.records()
        val inheritedOwnership = records.firstOrNull { record ->
            record.status != MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
        }?.ownership ?: records.firstOrNull { record ->
            record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED &&
                record.ownership.ownedRingerState == ringer.state
        }?.ownership
        if (inheritedOwnership != null) {
            persistence.add(unmuteKey, recoveryAt, inheritedOwnership)
            return true
        }

        val originalRinger = ringer.state
        if (ringer.supportsAppOwnedDnd && ringer.hasPolicyAccess) {
            val dndOwnership = MuteSystemOwnership(
                mode = MuteApplicationMode.APP_OWNED_DND,
                originalRingerState = originalRinger,
                ownedRingerState = null,
                appDndActivationOwned = true,
            )
            val added = persistence.add(unmuteKey, recoveryAt, dndOwnership)
            var activationUncertain = false
            try {
                if (ringer.activateAppOwnedDnd()) return true
            } catch (_: Exception) {
                // Binder 异常不能证明 PRIORITY 未在 system_server 生效；必须保留 ALL 清理责任。
                activationUncertain = true
            }
            if (originalRinger != RingerState.NORMAL) {
                if (activationUncertain) return true
                if (added) persistence.remove(unmuteKey)
                return false
            }
            val fallback = if (activationUncertain) {
                dndOwnership.copy(ownedRingerState = RingerState.VIBRATE)
            } else {
                fallbackOwnership(originalRinger)
            }
            persistence.replaceOwnershipForActiveSessions(fallback)
            return setFallbackVibrateOrRollback(
                unmuteKey = unmuteKey,
                ringer = ringer,
                added = added,
                originalRinger = originalRinger,
                dndOnlyOwnershipOnFallbackFailure = dndOwnership.takeIf { activationUncertain },
            )
        }

        if (originalRinger != RingerState.NORMAL) return false
        val added = persistence.add(unmuteKey, recoveryAt, fallbackOwnership(originalRinger))
        return setFallbackVibrateOrRollback(
            unmuteKey = unmuteKey,
            ringer = ringer,
            added = added,
            originalRinger = originalRinger,
        )
    }

    private fun setFallbackVibrateOrRollback(
        unmuteKey: TriggerKey,
        ringer: RingerController,
        added: Boolean,
        originalRinger: RingerState,
        dndOnlyOwnershipOnFallbackFailure: MuteSystemOwnership? = null,
    ): Boolean {
        try {
            ringer.setVibrate()
        } catch (failure: Exception) {
            val stateAfterFailure = readRingerStateOrNull(ringer)
            if (stateAfterFailure == RingerState.VIBRATE) return true
            if (dndOnlyOwnershipOnFallbackFailure != null && stateAfterFailure == originalRinger) {
                persistence.replaceOwnershipForActiveSessions(dndOnlyOwnershipOnFallbackFailure)
                return true
            }
            if (dndOnlyOwnershipOnFallbackFailure != null) return true
            if (stateAfterFailure == originalRinger) {
                if (added) persistence.remove(unmuteKey)
                throw failure
            }
            // 无法确认实际状态时 fail-closed 保留责任，让已安排的 UNMUTE 完成收敛。
            return true
        }
        val stateAfterWrite = readRingerStateOrNull(ringer)
        if (stateAfterWrite != RingerState.VIBRATE) {
            if (dndOnlyOwnershipOnFallbackFailure != null && stateAfterWrite == originalRinger) {
                persistence.replaceOwnershipForActiveSessions(dndOnlyOwnershipOnFallbackFailure)
                return true
            }
            if (dndOnlyOwnershipOnFallbackFailure != null) return true
            if (stateAfterWrite == null) return true
            if (added) persistence.remove(unmuteKey)
            return false
        }
        return true
    }

    /** 最后一条活动会话只恢复记录中由应用拥有的系统状态。 */
    @Synchronized
    fun unmute(unmuteKey: TriggerKey, ringer: RingerController): MuteRecoveryOutcome {
        val record = persistence.record(unmuteKey) ?: return MuteRecoveryOutcome.NoAction
        if (record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED) {
            return MuteRecoveryOutcome.Exhausted
        }
        val remainingActive = persistence.activeKeys() - unmuteKey
        if (remainingActive.isNotEmpty()) {
            persistence.consume(unmuteKey)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        return when (record.ownership.mode) {
            MuteApplicationMode.APP_OWNED_DND -> restoreOwnedDnd(unmuteKey, record, ringer)
            MuteApplicationMode.RINGER_VIBRATE_FALLBACK,
            MuteApplicationMode.LEGACY_V2_VIBRATE -> restoreOwnedRinger(unmuteKey, record, ringer)
            MuteApplicationMode.UNKNOWN_QUARANTINED -> MuteRecoveryOutcome.Exhausted
        }
    }

    private fun restoreOwnedDnd(
        key: TriggerKey,
        record: MuteSessionRecord,
        ringer: RingerController,
    ): MuteRecoveryOutcome {
        if (!record.ownership.appDndActivationOwned) {
            persistence.consume(key)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        if (!ringer.supportsAppOwnedDnd || !ringer.hasPolicyAccess) return recordFailure(key)
        return try {
            if (!ringer.deactivateAppOwnedDnd()) return recordFailure(key)
            restoreOwnedRingerAfterDnd(key, record, ringer)
        } catch (_: Exception) {
            recordFailure(key)
        }
    }

    /** DND 结果不确定时可能同时建立震动降级；两份责任都确认清理后才消费。 */
    private fun restoreOwnedRingerAfterDnd(
        key: TriggerKey,
        record: MuteSessionRecord,
        ringer: RingerController,
    ): MuteRecoveryOutcome {
        if (record.ownership.ownedRingerState == null) {
            persistence.consume(key)
            return MuteRecoveryOutcome.Recovered
        }
        return restoreOwnedRinger(key, record, ringer)
    }

    private fun restoreOwnedRinger(
        key: TriggerKey,
        record: MuteSessionRecord,
        ringer: RingerController,
    ): MuteRecoveryOutcome {
        if (record.ownership.ownedRingerState != RingerState.VIBRATE) {
            persistence.consume(key)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        val currentRingerState = readRingerStateOrNull(ringer) ?: return recordFailure(key)
        if (currentRingerState != RingerState.VIBRATE) {
            persistence.consume(key)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        return try {
            when (record.ownership.originalRingerState) {
                RingerState.NORMAL -> {
                    try {
                        ringer.setNormal()
                    } catch (_: Exception) {
                        if (readRingerStateOrNull(ringer) != RingerState.NORMAL) {
                            return recordFailure(key)
                        }
                    }
                    if (readRingerStateOrNull(ringer) != RingerState.NORMAL) {
                        return recordFailure(key)
                    }
                }
                RingerState.VIBRATE -> Unit
                RingerState.SILENT -> return recordFailure(key)
            }
            persistence.consume(key)
            MuteRecoveryOutcome.Recovered
        } catch (_: Exception) {
            recordFailure(key)
        }
    }

    /** 一个发布周期的旧版恢复桥；存在任一新式 ACTIVE/PENDING 会话时禁止执行。 */
    @Synchronized
    fun recoverLegacyUnmute(ringer: RingerController): Boolean {
        if (persistence.activeKeys().isNotEmpty()) return false
        if (!ringer.hasPolicyAccess || ringer.state != RingerState.VIBRATE) return false
        return try {
            ringer.setNormal()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 用户授权后显式把耗尽责任重新放回受控恢复窗口。 */
    @Synchronized
    fun prepareUserRetry(key: TriggerKey): Boolean = persistence.prepareUserRetry(key)

    /** 用户确认已手动恢复或放弃责任后显式清理。 */
    @Synchronized
    fun releaseByUser(key: TriggerKey): Boolean = persistence.releaseByUser(key)

    /** 用户重试 Work 提交失败时回滚为显式用户处理状态。 */
    @Synchronized
    fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean =
        persistence.restoreExhaustedAfterFailedRetry(key)

    /** 恢复 Work 无法持久入队时发布显式用户责任。 */
    @Synchronized
    fun requireUserAction(key: TriggerKey): Boolean = persistence.requireUserAction(key)

    /**
     * Worker 自身发生基础设施异常时也使用持久失败次数推进状态机。
     *
     * 不能依赖 WorkManager 的 runAttemptCount：系统事件会以新的唯一 Work 替换旧请求，
     * 该计数会归零；持久在静音责任上的 recoveryAttempt 才能保证有限重试最终收敛。
     */
    @Synchronized
    fun recordWorkerFailure(key: TriggerKey): MuteRecoveryOutcome {
        if (persistence.record(key) == null) return MuteRecoveryOutcome.NoAction
        return recordFailure(key)
    }

    /** 记录失败并把第 N 次失败转换为用户必须处理的耗尽状态。 */
    private fun recordFailure(key: TriggerKey): MuteRecoveryOutcome {
        val attempt = persistence.recordRecoveryFailure(key)
        return if (attempt < MAX_RECOVERY_ATTEMPTS) {
            MuteRecoveryOutcome.RetryRequired(attempt)
        } else {
            MuteRecoveryOutcome.Exhausted
        }
    }

    companion object {
        /** 自动恢复最多尝试三次。 */
        const val MAX_RECOVERY_ATTEMPTS = 3
    }

    private fun fallbackOwnership(original: RingerState): MuteSystemOwnership =
        MuteSystemOwnership(
            mode = MuteApplicationMode.RINGER_VIBRATE_FALLBACK,
            originalRingerState = original,
            ownedRingerState = RingerState.VIBRATE,
            appDndActivationOwned = false,
        )

    private fun readRingerStateOrNull(ringer: RingerController): RingerState? =
        runCatching { ringer.state }.getOrNull()
}
