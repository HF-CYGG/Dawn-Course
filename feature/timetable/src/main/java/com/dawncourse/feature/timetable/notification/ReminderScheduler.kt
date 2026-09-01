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
        }.onFailure { Log.w(TAG, "scheduleDailyWork failed", it) }
    }

    fun triggerImmediateWork(context: Context, forceReplay: Boolean = false) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DailySchedulerWorker>()
                .setInputData(createImmediateWorkData(forceReplay))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                IMMEDIATE_WORK_POLICY,
                request
            )
        }.onFailure { Log.w(TAG, "triggerImmediateWork failed", it) }
    }

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
}
