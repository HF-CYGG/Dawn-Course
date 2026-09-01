package com.dawncourse.feature.widget.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.dawncourse.feature.widget.MidnightUpdateReceiver
import com.dawncourse.feature.widget.DawnWidgetReceiver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness

/**
 * Widget 更新工作器
 *
 * 使用 WorkManager 执行后台更新任务，确保 Widget 内容的及时刷新。
 * 主要应对系统杀后台后 Widget 长期不刷新的情况。
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val operationalDataGate: OperationalDataGate
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        when (operationalDataGate.readiness()) {
            OperationalDataReadiness.STARTING -> return Result.retry()
            OperationalDataReadiness.RECOVERY_REQUIRED -> return Result.success()
            OperationalDataReadiness.READY -> Unit
        }
        // 任务可能在最后一个 Widget 被移除后才开始执行，执行前再次守住实例边界。
        if (!WidgetSyncManager.hasWidgetInstances(applicationContext)) {
            return Result.success()
        }
        // 触发 Widget 更新，重新执行 provideGlance
        return try {
            DawnWidget().updateAll(applicationContext)
            Result.success()
        } catch (failure: Throwable) {
            Log.w(TAG, "Widget worker update failed", failure)
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "WidgetUpdateWorker"
    }
}

object WidgetSyncManager {
    private const val TAG = "WidgetSyncManager"
    private const val UNIQUE_WORK_NAME = "DawnWidgetUpdateWork"
    /**
     * 系统恢复事件使用的即时 Widget 更新任务唯一名称。
     */
    internal const val IMMEDIATE_RESTORE_WORK_NAME = "DawnWidgetSystemRestore"

    /**
     * 连续系统事件只保留最新一次 Widget 刷新请求。
     */
    internal val IMMEDIATE_RESTORE_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    private const val NEXT_UPDATE_REQUEST_CODE = 10001
    private const val ACTION_FORCE_UPDATE = "com.dawncourse.widget.FORCE_UPDATE"

    /**
     * 调度后台自动刷新任务
     * 策略：每 4 小时刷新一次（保底），配合 Widget 自身的 updatePeriodMillis (30分钟)
     * WorkManager 主要负责系统杀后台后的存活保底
     */
    fun scheduleUpdate(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                4, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }.onFailure { Log.w(TAG, "scheduleUpdate failed", it) }
    }

    fun cancelUpdate(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }.onFailure { Log.w(TAG, "cancelUpdate failed", it) }
    }

    /**
     * 在开机、应用覆盖安装或系统时间变化后恢复 Widget 更新链路。
     *
     * 只查询 AppWidgetManager 的实例 ID，不读取课程数据、Repository 或 DAO；
     * 没有实例时不创建周期 Work、不设置午夜闹钟，也不触发刷新。
     *
     * @param context 应用上下文或广播上下文。
     */
    fun restoreAfterSystemEvent(context: Context) {
        val appContext = context.applicationContext
        val plan = WidgetRestorePolicy.planFor(hasWidgetInstances(appContext))
        if (plan.schedulePeriodicWork) scheduleUpdate(appContext)
        if (plan.scheduleMidnightAlarm) {
            runCatching { MidnightUpdateReceiver.scheduleNextMidnightUpdate(appContext) }
                .onFailure { Log.w(TAG, "scheduleNextMidnightUpdate failed", it) }
        }
        if (plan.enqueueImmediateUpdate) enqueueImmediateRestoreUpdate(appContext)
    }

    /**
     * 查询系统当前是否仍有 DawnWidget 实例。
     *
     * 查询失败时保守视为无实例，避免在系统恢复阶段制造额外后台唤醒。
     */
    internal fun hasWidgetInstances(context: Context): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            AppWidgetManager.getInstance(appContext).getAppWidgetIds(
                ComponentName(appContext, DawnWidgetReceiver::class.java)
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * 将系统恢复后的立即刷新交给 WorkManager，避免 Receiver 返回后裸协程被系统终止。
     */
    private fun enqueueImmediateRestoreUpdate(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_RESTORE_WORK_NAME,
                IMMEDIATE_RESTORE_WORK_POLICY,
                request
            )
        }.onFailure { Log.w(TAG, "enqueueImmediateRestoreUpdate failed", it) }
    }

    /**
     * 立即触发一次更新
     */
    fun triggerImmediateUpdate(context: Context) {
        restoreAfterSystemEvent(context)
    }

    fun scheduleNextCourseUpdate(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = nextCourseUpdateIntent(context)
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
        } catch (failure: Throwable) {
            Log.w(TAG, "scheduleNextCourseUpdate failed", failure)
        }
    }

    /** 清除下一课程结束时的精确闹钟，供测试变体在重置隔离状态时收敛调度。 */
    fun cancelNextCourseUpdate(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NEXT_UPDATE_REQUEST_CODE,
            nextCourseUpdateIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.cancel(pendingIntent)
        } finally {
            pendingIntent.cancel()
        }
    }

    private fun nextCourseUpdateIntent(context: Context): Intent =
        Intent().apply {
            component = ComponentName(context, DawnWidgetReceiver::class.java)
            action = ACTION_FORCE_UPDATE
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
                    // 所有系统时间变化入口统一经实例判定后恢复。
                    restoreAfterSystemEvent(ctx)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // 注册到 Application Context (跟随应用生命周期)
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { Log.w(TAG, "registerTimeChangeReceiver failed", it) }
    }

    /**
     * 立即执行 Widget 刷新（使用协程直接更新，非 WorkManager）
     * 仅保留给 App 前台等非系统恢复的交互场景使用。
     */
    fun updateWidgetNow(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + widgetExceptionHandler).launch {
            try {
                DawnWidget().updateAll(context)
            } catch (failure: Throwable) {
                Log.w(TAG, "updateWidgetNow failed", failure)
            }
        }
    }

    internal val widgetExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "Widget coroutine failed", throwable)
    }
}
