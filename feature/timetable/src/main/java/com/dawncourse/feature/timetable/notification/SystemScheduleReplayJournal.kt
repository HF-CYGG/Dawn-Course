package com.dawncourse.feature.timetable.notification

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 系统时间、时区、升级或重启后必须完成一次 force replay 的持久责任。 */
internal interface SystemScheduleReplayJournal {
    /** 返回当前责任代际；null 表示没有待确认事件。 */
    fun pendingToken(): String?

    /** 创建新代际并同步落盘；失败返回 null。 */
    fun markPending(): String?

    /** 仅清除仍与 [token] 相同的代际；新事件已经覆盖时保留新责任并返回成功。 */
    fun clearPendingIfMatches(token: String): Boolean
}

/** 独立 SharedPreferences marker；只有实际 Alarm 对账成功后才允许清理。 */
internal class AppSystemScheduleReplayJournal(context: Context) : SystemScheduleReplayJournal {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun pendingToken(): String? = synchronized(MARKER_LOCK) {
        preferences.getString(KEY_FORCE_REPLAY_TOKEN, null)?.takeIf(String::isNotBlank)
    }

    override fun markPending(): String? = synchronized(MARKER_LOCK) {
        val token = UUID.randomUUID().toString()
        token.takeIf {
            preferences.edit().putString(KEY_FORCE_REPLAY_TOKEN, token).commit()
        }
    }

    override fun clearPendingIfMatches(token: String): Boolean = synchronized(MARKER_LOCK) {
        val current = preferences.getString(KEY_FORCE_REPLAY_TOKEN, null)
        if (current != token) return@synchronized true
        preferences.edit().remove(KEY_FORCE_REPLAY_TOKEN).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "dc_system_schedule_replay"
        const val KEY_FORCE_REPLAY_TOKEN = "force_replay_token"
        /** 同一进程内不同 journal 实例的 compare-and-clear 必须线性化。 */
        val MARKER_LOCK = Any()
    }
}

/** Worker 开始时捕获的责任代际；读取异常必须触发幂等重放并保留重试。 */
internal data class SystemScheduleReplayClaim(
    val token: String?,
    val readFailed: Boolean,
) {
    val isPending: Boolean get() = token != null || readFailed
}

/**
 * marker 与 WorkManager 是相互独立的两条持久通道：任一成功即可接管系统事件责任。
 * WorkManager 成功入队时也不在这里清 marker，必须等 TriggerReconciler 真正完成重放。
 */
internal suspend fun persistAndEnqueueSystemScheduleReplay(
    journal: SystemScheduleReplayJournal,
    enqueue: suspend () -> Boolean,
): Boolean {
    val markerPersisted = runCatching { journal.markPending() != null }.getOrDefault(false)
    val workEnqueued = try {
        enqueue()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    return markerPersisted || workEnqueued
}

/** 持久 marker 必须覆盖普通 input=false 的即时/周期 Worker。 */
internal fun shouldForceReplay(inputForceReplay: Boolean, markerPending: Boolean): Boolean =
    inputForceReplay || markerPending

/** 捕获本轮可确认的 token；读取失败时保守重放，但绝不猜测并清除未知代际。 */
internal fun captureSystemScheduleReplayClaim(
    journal: SystemScheduleReplayJournal,
): SystemScheduleReplayClaim = runCatching {
    SystemScheduleReplayClaim(token = journal.pendingToken(), readFailed = false)
}.getOrElse {
    SystemScheduleReplayClaim(token = null, readFailed = true)
}

/** 只有真实对账成功后才清 marker；清理异常按失败处理并保留幂等重放责任。 */
internal fun acknowledgeSystemScheduleReplay(
    journal: SystemScheduleReplayJournal,
    claim: SystemScheduleReplayClaim,
    triggerReconciled: Boolean,
): Boolean {
    if (claim.readFailed) return false
    val token = claim.token ?: return true
    if (!triggerReconciled) return true
    return runCatching { journal.clearPendingIfMatches(token) }.getOrDefault(false)
}
