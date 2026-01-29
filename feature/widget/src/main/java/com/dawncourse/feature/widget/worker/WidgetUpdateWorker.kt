package com.dawncourse.feature.widget.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dawncourse.feature.widget.DawnWidget
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 触发 Widget 更新，重新执行 provideGlance
        DawnWidget().updateAll(context)
        return Result.success()
    }
}

object WidgetSyncManager {
    private const val UNIQUE_WORK_NAME = "DawnWidgetUpdateWork"

    /**
     * 调度后台自动刷新任务
     * 策略：每 4 小时刷新一次（保底），配合 Widget 自身的 updatePeriodMillis (30分钟)
     * WorkManager 主要负责系统杀后台后的存活保底
     */
    fun scheduleUpdate(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            4, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // 如果已存在则保持，避免重复调度
            request
        )
    }

    fun cancelUpdate(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
