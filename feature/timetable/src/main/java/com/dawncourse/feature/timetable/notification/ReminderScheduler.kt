package com.dawncourse.feature.timetable.notification

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/**
 * 每日提醒调度器（WorkManager）
 *
 * 目标：让 [DailySchedulerWorker] 尽量在“每天固定时间”运行（优先 06:00，本地时区）。
 *
 * 说明：
 * - WorkManager 的 PeriodicWorkRequest 本质上是“尽量按周期执行”，并不保证精确到点。
 * - 这里通过设置 initialDelay，让“首次执行时间”对齐到下一次 06:00，后续以 24h 周期尽量保持接近。
 * - 本调度器会被 [com.dawncourse.app.MainActivity] 在设置变化时调用，因此必须可重复调用且不会产生多条重复任务。
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    private const val WORK_NAME = "DailyReminderWorker"
    /**
     * 系统事件触发的即时对账任务唯一名称。
     *
     * 与每日周期任务分离，避免系统时间变化时影响下一次 06:00 的保底调度。
     */
    internal const val IMMEDIATE_WORK_NAME = "DailyReminderImmediateReconcile"
    /** 系统事件要求忽略注册表 keep 状态并重放全部 Desired。 */
    internal const val INPUT_FORCE_REPLAY = "force_replay"

    /**
     * 系统事件进入唯一串行链，最终闹钟状态由后续任务收敛。
     */
    internal val IMMEDIATE_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
    /** 每个已消费边界都追加到同一串行链尾，避免 RUNNING 期间的新续排被静默丢弃。 */
    internal const val CONTINUATION_WORK_NAME = "CourseTriggerScheduleContinuation"
    internal val CONTINUATION_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
    private val TARGET_LOCAL_TIME: LocalTime = LocalTime.of(6, 0)

    /**
     * 调度每日任务：
     * - 首次执行：对齐到下一次本地时间 06:00
     * - 周期：24 小时
     */
    fun scheduleDailyWork(context: Context) {
        runCatching {
            val zoneId = ZoneId.systemDefault()
            val initialDelayMillis = calculateInitialDelayMillis(
                zoneId = zoneId,
                targetLocalTime = TARGET_LOCAL_TIME,
                now = ZonedDateTime.now(zoneId)
            )

            val request = PeriodicWorkRequestBuilder<DailySchedulerWorker>(24, TimeUnit.HOURS)
                // 通过初始延迟把首次运行时间对齐到下一次 06:00（本地时间）
                // 注意：这只能“尽量对齐”，系统仍可能因省电策略/资源约束推迟执行
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setInputData(createPeriodicWorkData())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            // 一次性系统/课程边界事件若只成功写入 marker、未成功交给 WorkManager，
            // 应用启动时立即补投。系统事件需要重放全部 Desired；Surface 事件只需刷新状态。
            val systemReplayPending = captureSystemScheduleReplayClaim(
                AppSystemScheduleReplayJournal(context),
            ).isPending
            val surfaceRefreshPending = captureCourseSurfaceRefreshClaim(
                AppCourseSurfaceRefreshJournal(context),
            ).isPending
            if (systemReplayPending) {
                enqueueImmediateWork(context, forceReplay = true)
            } else if (surfaceRefreshPending) {
                enqueueImmediateWork(context, forceReplay = false)
            }
        }.onFailure { Log.w(TAG, "scheduleDailyWork failed", it) }
    }

    fun triggerImmediateWork(context: Context, forceReplay: Boolean = false) {
        runCatching {
            if (forceReplay) {
                runCatching { AppSystemScheduleReplayJournal(context).markPending() }
            }
            enqueueImmediateWork(context, forceReplay)
        }.onFailure { Log.w(TAG, "triggerImmediateWork failed", it) }
    }

    /**
     * 单次 Alarm 被消费后把续排追加到唯一串行链，并在 Receiver 结束 goAsync 前确认持久化。
     *
     * APPEND_OR_REPLACE 保证已有 Worker 处于 RUNNING 时仍存在一个尾部 reconcile；
     * [DailySchedulerExecutionLock] 再与周期/系统即时 Worker 统一串行，避免并发全量对账。
     */
    suspend fun triggerContinuationWorkAndAwait(context: Context): Boolean = try {
        withTimeoutOrNull(IMMEDIATE_ENQUEUE_TIMEOUT_MILLIS) {
            awaitWorkManagerOperation(enqueueContinuationWork(context))
        } ?: false
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Log.w(TAG, "trigger continuation work failed", failure)
        false
    }

    private fun enqueueContinuationWork(context: Context) =
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTINUATION_WORK_NAME,
            CONTINUATION_WORK_POLICY,
            OneTimeWorkRequestBuilder<DailySchedulerWorker>()
                .setInputData(createImmediateWorkData(forceReplay = false))
                .build(),
        )

    /** 系统广播必须等到 WorkManager 确认命令已持久化后才能结束 goAsync。 */
    suspend fun triggerImmediateWorkAndAwait(
        context: Context,
        forceReplay: Boolean = false,
    ): Boolean = try {
        val enqueue = suspend { enqueueImmediateWorkAndAwait(context, forceReplay) }
        if (forceReplay) {
            persistAndEnqueueSystemScheduleReplay(
                journal = AppSystemScheduleReplayJournal(context),
                enqueue = enqueue,
            )
        } else {
            enqueue()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Log.w(TAG, "triggerImmediateWorkAndAwait failed", failure)
        false
    }

    /** 课程边界 Alarm 被消费前先持久化责任，再确认即时刷新任务已经入队。 */
    suspend fun triggerCourseSurfaceRefreshWorkAndAwait(context: Context): Boolean = try {
        persistAndEnqueueCourseSurfaceRefresh(
            journal = AppCourseSurfaceRefreshJournal(context),
        ) {
            enqueueImmediateWorkAndAwait(context, forceReplay = false)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Log.w(TAG, "triggerCourseSurfaceRefreshWorkAndAwait failed", failure)
        false
    }

    private suspend fun enqueueImmediateWorkAndAwait(
        context: Context,
        forceReplay: Boolean,
    ): Boolean = withTimeoutOrNull(IMMEDIATE_ENQUEUE_TIMEOUT_MILLIS) {
        awaitWorkManagerOperation(enqueueImmediateWork(context, forceReplay))
    } ?: false

    private fun enqueueImmediateWork(context: Context, forceReplay: Boolean) =
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            IMMEDIATE_WORK_POLICY,
            OneTimeWorkRequestBuilder<DailySchedulerWorker>()
                .setInputData(createImmediateWorkData(forceReplay))
                .build(),
        )

    /** 构建可单元测试的即时对账输入。 */
    internal fun createImmediateWorkData(forceReplay: Boolean): Data = workDataOf(
        INPUT_FORCE_REPLAY to forceReplay
    )

    /** 每日保底任务必须重放 Desired，以修复系统 Alarm 与本地注册表的不一致。 */
    internal fun createPeriodicWorkData(): Data = workDataOf(
        INPUT_FORCE_REPLAY to true
    )

    /**
     * 取消调度任务
     */
    fun cancelWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }.onFailure { Log.w(TAG, "cancelWork failed", it) }
    }

    /**
     * 计算“距离下一次目标时间”的初始延迟（毫秒）。
     *
     * 规则：
     * - 如果当前时间早于今日目标时间，则对齐到“今天的目标时间”
     * - 否则对齐到“明天的目标时间”
     *
     * 该函数不依赖 Android API，便于在纯 JVM 环境编写单元测试。
     */
    internal fun calculateInitialDelayMillis(
        zoneId: ZoneId,
        targetLocalTime: LocalTime,
        now: ZonedDateTime
    ): Long {
        val nowInZone = now.withZoneSameInstant(zoneId)
        val todayTarget = nowInZone.toLocalDate().atTime(targetLocalTime).atZone(zoneId)
        val nextTarget = if (nowInZone.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1)
        return Duration.between(nowInZone, nextTarget).toMillis().coerceAtLeast(0L)
    }

    private const val IMMEDIATE_ENQUEUE_TIMEOUT_MILLIS = 5_000L
}
