package com.dawncourse.feature.widget.worker

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.dawncourse.feature.widget.DawnWidgetReceiver

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
    private const val TAG = "WidgetSyncManager"
    private const val UNIQUE_WORK_NAME = "DawnWidgetUpdateWork"
    private const val NEXT_UPDATE_REQUEST_CODE = 10001
    private const val ACTION_FORCE_UPDATE = "com.dawncourse.widget.FORCE_UPDATE"

    /**
     * 调度后台自动刷新任务
     * 策略：每 4 小时刷新一次（保底），配合 Widget 自身的 updatePeriodMillis (30分钟)
     * WorkManager 主要负责系统杀后台后的存活保底
     */
    fun scheduleUpdate(context: Context) {
        // 兜底原因：本方法会在冷启动的 App Startup 阶段被调用。
        // WorkManager.getInstance() 在未初始化时会抛 IllegalStateException，
        // 部分 OEM ROM 的 JobScheduler 也可能在 enqueue 时抛异常。
        // 小组件刷新属于增强功能，任何失败都不应影响 App 启动。
        runCatching {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                4, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // 如果已存在则保持，避免重复调度
                request
            )
        }.onFailure {
            Log.w(TAG, "scheduleUpdate failed", it)
        }
    }

    fun cancelUpdate(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }.onFailure {
            Log.w(TAG, "cancelUpdate failed", it)
        }
    }

    /**
     * 立即触发一次更新
     */
    fun triggerImmediateUpdate(context: Context) {
        updateWidgetNow(context)
    }

    fun scheduleNextCourseUpdate(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DawnWidgetReceiver::class.java).apply {
            action = ACTION_FORCE_UPDATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NEXT_UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nowMillis = System.currentTimeMillis()
        if (triggerAtMillis <= nowMillis) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
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
        //
        // 必须使用 ContextCompat.registerReceiver 并显式指定导出标记：
        // targetSdk 34 (Android 14) 起，未显式声明 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED
        // 的动态注册会抛 SecurityException。这里只监听系统广播，不需要对外暴露。
        //
        // 同时整体兜底：本方法在冷启动的 App Startup 阶段被调用，
        // 部分 OEM ROM 会对启动早期的 registerReceiver 施加额外限制，
        // 抛出异常不应导致进程被杀。
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            Log.w(TAG, "registerTimeChangeReceiver failed", it)
        }
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
