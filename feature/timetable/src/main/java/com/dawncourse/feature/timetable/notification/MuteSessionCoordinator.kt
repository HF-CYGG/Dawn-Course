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
    val recoveryAt: Instant? = null
)

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
    /** 应用首次切换到震动或已有重叠会话时持有恢复责任。 */
    @Synchronized
    fun mute(
        unmuteKey: TriggerKey,
        ringer: RingerController,
        recoveryAt: Instant? = null
    ): Boolean {
        if (!ringer.hasPolicyAccess) return false
        val records = persistence.records()
        val shouldOwn = MuteSessionPolicy.shouldOwnMuteSession(
            isRingerNormal = ringer.state == RingerState.NORMAL,
            hasActiveOwnedSession = records.any { record ->
                record.status != MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
            },
            isRingerVibrate = ringer.state == RingerState.VIBRATE,
            hasExhaustedResponsibility = records.any { record ->
                record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
            }
        )
        if (!shouldOwn) return false
        val added = persistence.add(unmuteKey, recoveryAt)
        if (ringer.state != RingerState.NORMAL) return true
        try {
            ringer.setVibrate()
        } catch (failure: Exception) {
            if (added) persistence.remove(unmuteKey)
            throw failure
        }
        return true
    }

    /** 最后一条活动会话必须先成功恢复铃声，再消费责任。 */
    @Synchronized
    fun unmute(unmuteKey: TriggerKey, ringer: RingerController): MuteRecoveryOutcome {
        val record = persistence.record(unmuteKey) ?: return MuteRecoveryOutcome.NoAction
        if (record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED) {
            return MuteRecoveryOutcome.Exhausted
        }
        if (ringer.state != RingerState.VIBRATE) {
            persistence.consume(unmuteKey)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        val remainingActive = persistence.activeKeys() - unmuteKey
        if (remainingActive.isNotEmpty()) {
            persistence.consume(unmuteKey)
            return MuteRecoveryOutcome.ResponsibilityReleased
        }
        if (!ringer.hasPolicyAccess) return recordFailure(unmuteKey)
        return try {
            ringer.setNormal()
            persistence.consume(unmuteKey)
            MuteRecoveryOutcome.Recovered
        } catch (_: Exception) {
            recordFailure(unmuteKey)
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
}
