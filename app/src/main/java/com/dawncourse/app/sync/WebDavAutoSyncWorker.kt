package com.dawncourse.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.WebDavAutoSyncIntervalUnit
import com.dawncourse.core.domain.model.WebDavAutoSyncMode
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlin.math.max

/**
 * WebDAV 自动同步 Worker
 *
 * 负责在后台触发“上传备份”，避免 UI 阻塞。
 */
class WebDavAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Worker 中使用的 Hilt 入口
     *
     * Worker 无法直接注入时，通过 EntryPoint 取出依赖。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        /** 设置仓库，用于读取自动同步开关 */
        fun settingsRepository(): SettingsRepository
        /** WebDAV 同步仓库，用于触发上传逻辑 */
        fun webDavSyncRepository(): WebDavSyncRepository
    }

    override suspend fun doWork(): Result {
        // 通过 Hilt EntryPoint 获取依赖
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java
        )
        // 读取当前设置，若未开启则直接结束
        val settings = entryPoint.settingsRepository().settings.first()
        if (!settings.enableWebDavAutoSync) return Result.success()

        // 触发上传备份，认证失败时不重试，其他异常交由系统重试
        val result = entryPoint.webDavSyncRepository().uploadBackup(forceUpload = false)
        return if (result.success) {
            Result.success()
        } else if (result.code == SyncErrorCode.NO_CREDENTIALS || result.code == SyncErrorCode.AUTH_FAILED) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}

/**
 * WebDAV 自动同步调度器
 *
 * 根据用户配置选择一次性或周期任务。
 */
object WebDavAutoSyncScheduler {
    private const val WORK_NAME = "WebDavAutoSyncWork"

    /**
     * 调度自动同步任务
     *
     * 开关关闭时会自动取消任务。
     */
    fun schedule(context: Context, settings: AppSettings) {
        if (!settings.enableWebDavAutoSync) {
            cancel(context)
            return
        }

        // 先清理旧任务，避免重复调度
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)

        when (settings.webDavAutoSyncMode) {
            WebDavAutoSyncMode.FIXED_TIME -> scheduleFixedTime(context, settings.webDavAutoSyncFixedAt)
            WebDavAutoSyncMode.INTERVAL -> scheduleInterval(context, settings.webDavAutoSyncIntervalValue, settings.webDavAutoSyncIntervalUnit)
        }
    }

    /**
     * 取消自动同步任务
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 固定日期同步：一次性任务
     */
    private fun scheduleFixedTime(context: Context, timestamp: Long) {
        if (timestamp <= 0L) return
        val delayMillis = max(0L, timestamp - System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<WebDavAutoSyncWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * 间隔同步：周期任务
     *
     * WorkManager 最小周期为 15 分钟，因此会做下限保护。
     */
    private fun scheduleInterval(context: Context, value: Int, unit: WebDavAutoSyncIntervalUnit) {
        val safeValue = max(1, value)
        val intervalMinutes = when (unit) {
            WebDavAutoSyncIntervalUnit.MINUTES -> safeValue
            WebDavAutoSyncIntervalUnit.HOURS -> safeValue * 60
            WebDavAutoSyncIntervalUnit.DAYS -> safeValue * 60 * 24
        }
        val safeIntervalMinutes = max(15, intervalMinutes)
        val request = PeriodicWorkRequestBuilder<WebDavAutoSyncWorker>(
            safeIntervalMinutes.toLong(),
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
