package com.dawncourse.feature.widget.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dawncourse.feature.widget.DawnWidget
import java.util.concurrent.TimeUnit

import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.dawncourse.feature.widget.MidnightUpdateReceiver

/**
 * Widget 更新工作器
 *
 * 使用 WorkManager 执行后台更新任务，确保 Widget 内容的及时刷新。
 * 主要应对系统杀后台后 Widget 长期不刷新的情况。
 */
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

    /**
     * 立即触发一次更新
     */
    fun triggerImmediateUpdate(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /**
     * 注册时间变化广播监听器
     *
     * 用于在用户手动修改系统时间/日期/时区时，立即刷新 Widget 并重置午夜闹钟。
     * 解决“手动修改日期后 Widget 不刷新”的问题。
     *
     * 注意：由于 Android 8.0+ 限制，ACTION_TIME_CHANGED 和 ACTION_DATE_CHANGED 无法在 Manifest 中静态注册，
     * 必须通过 Context.registerReceiver 动态注册。通常在 Application.onCreate 中调用。
     */
    fun registerTimeChangeReceiver(context: Context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIME_CHANGED ||
                    intent.action == Intent.ACTION_DATE_CHANGED ||
                    intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    
                    // 1. 重置午夜更新闹钟 (因为“午夜”时刻可能变了，或者已错过)
                    MidnightUpdateReceiver.scheduleNextMidnightUpdate(ctx)
                    
                    // 2. 立即刷新 Widget
                    updateWidgetNow(ctx)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // 注册到 Application Context (跟随应用生命周期)
        context.applicationContext.registerReceiver(receiver, filter)
    }

    /**
     * 立即执行 Widget 刷新（使用协程直接更新，非 WorkManager）
     * 适用于需要立即响应的交互场景，如：App 回到前台、时间变更广播等。
     */
    fun updateWidgetNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DawnWidget().updateAll(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
