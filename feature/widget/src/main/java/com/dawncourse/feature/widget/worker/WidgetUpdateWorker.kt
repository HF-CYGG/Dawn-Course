package com.dawncourse.feature.widget.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dawncourse.feature.widget.DawnWidget
import com.dawncourse.feature.widget.DawnWidgetForceUpdateReceiver
import com.dawncourse.feature.widget.DawnWidgetReceiver
import com.dawncourse.feature.widget.MidnightUpdateReceiver
import com.dawncourse.feature.widget.WidgetTimeline
import com.dawncourse.feature.widget.WidgetTimelineResolution
import com.dawncourse.feature.widget.policy.SerializedWidgetRefreshCoordinator
import com.dawncourse.feature.widget.policy.WidgetContentSource
import com.dawncourse.feature.widget.policy.WidgetInstanceCleanupPolicy
import com.dawncourse.feature.widget.policy.WidgetInstanceTopologyCoordinator
import com.dawncourse.feature.widget.policy.WidgetNextUpdateOperations
import com.dawncourse.feature.widget.policy.WidgetNextUpdateRequest
import com.dawncourse.feature.widget.policy.WidgetStartupRetryPolicy
import com.dawncourse.core.domain.repository.OperationalDataGate
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val operationalDataGate: OperationalDataGate,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val isStartupRetry = inputData.getBoolean(INPUT_STARTUP_RETRY, false)
        // 任务可能在最后一个 Widget 被移除后才开始执行，执行前再次守住实例边界。
        if (!WidgetSyncManager.hasWidgetInstances(applicationContext)) {
            return Result.success()
        }
        // 触发 Widget 更新，重新执行 provideGlance
        return try {
            val refresh = WidgetSyncManager.refreshWidgetContent(applicationContext)
            if (isStartupRetry &&
                WidgetStartupRetryPolicy.shouldRetry(
                    readiness = operationalDataGate.readiness(),
                    runAttemptCount = runAttemptCount,
                    usedStartupSnapshot = refresh.source == WidgetContentSource.STARTUP_SNAPSHOT,
                )
            ) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "Widget worker update failed", failure)
            if (WidgetStartupRetryPolicy.shouldRetryFailure(isStartupRetry, runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        internal const val INPUT_STARTUP_RETRY = "startup_retry"
    }
}

/**
 * 下一课程边界的 Work fallback 只交付内部广播，不在自身生命周期内执行 Glance。
 * 这样本次刷新重排下一责任时，不会取消/替换仍在提交内容的当前 Worker。
 */
class WidgetBoundaryFallbackWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!WidgetSyncManager.hasWidgetInstances(applicationContext)) return Result.success()
        return try {
            WidgetSyncManager.enqueueBoundaryDelivery(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w("WidgetBoundaryWorker", "enqueue durable boundary delivery failed", failure)
            Result.retry()
        }
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
    private const val UNIQUE_NEXT_UPDATE_WORK_NAME = "DawnWidgetNextCourseUpdateWork"
    private const val UNIQUE_BOUNDARY_DELIVERY_WORK_NAME = "DawnWidgetBoundaryDeliveryWork"
    internal const val STARTUP_RETRY_WORK_NAME = "DawnWidgetStartupRetryWork"
    internal const val STARTUP_RETRY_DELAY_MILLIS = 2_000L
    internal val STARTUP_RETRY_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    private const val NEXT_UPDATE_REQUEST_CODE = 10001
    private val refreshCoordinator = SerializedWidgetRefreshCoordinator<WidgetTimeline> { operation, failure ->
        Log.w(TAG, "compensating $operation cleanup failed", failure)
    }
    private val instanceTopologyCoordinator = WidgetInstanceTopologyCoordinator()
    internal val widgetTimelineState get() = refreshCoordinator.state

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
            instanceTopologyCoordinator.clearResponsibilities {
                submitResponsibilityCancellations(context.applicationContext)
            }
        }.onFailure { Log.w(TAG, "cancelUpdate failed", it) }
    }

    /** Receiver 生命周期内等待全部 Work 取消落库，再同步清除遗留精确 Alarm。 */
    internal suspend fun cancelUpdateAndAwait(context: Context) {
        val appContext = context.applicationContext
        WidgetInstanceCleanupPolicy.execute(
            clearResponsibilities = {
                val cancellations = instanceTopologyCoordinator.clearResponsibilities {
                    submitResponsibilityCancellations(appContext)
                }
                cancellations.forEach { operation -> awaitWorkManagerOperation(operation) }
            },
            hasWidgetInstances = { hasWidgetInstances(appContext) },
            restoreAfterCleanup = { restoreAfterSystemEvent(appContext) },
        )
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
        instanceTopologyCoordinator.restoreIfPresent(
            hasWidgetInstances = { hasWidgetInstances(appContext) },
            restoreResponsibilities = {
                val plan = WidgetRestorePolicy.planFor(hasWidget = true)
                if (plan.schedulePeriodicWork) scheduleUpdate(appContext)
                if (plan.scheduleMidnightAlarm) {
                    runCatching { MidnightUpdateReceiver.scheduleNextMidnightUpdate(appContext) }
                        .onFailure { Log.w(TAG, "scheduleNextMidnightUpdate failed", it) }
                }
                if (plan.enqueueImmediateUpdate) enqueueImmediateWidgetUpdate(appContext)
            },
        )
    }

    /** 在实例拓扑锁内按固定顺序提交全部取消，Operation 在锁外等待完成。 */
    private fun submitResponsibilityCancellations(context: Context): List<Operation> {
        invalidateRefreshes()
        val workManager = WorkManager.getInstance(context)
        val cancellations = listOf(
            UNIQUE_WORK_NAME,
            UNIQUE_NEXT_UPDATE_WORK_NAME,
            UNIQUE_BOUNDARY_DELIVERY_WORK_NAME,
            STARTUP_RETRY_WORK_NAME,
            IMMEDIATE_RESTORE_WORK_NAME,
        ).map(workManager::cancelUniqueWork)
        cancelNextCourseAlarm(context)
        MidnightUpdateReceiver.cancelNextMidnightUpdate(context)
        return cancellations
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
        }.onFailure { Log.w(TAG, "query DawnWidget instances failed", it) }
            .getOrDefault(false)
    }

    /**
     * 将系统恢复后的立即刷新交给 WorkManager，避免 Receiver 返回后裸协程被系统终止。
     */
    internal fun enqueueImmediateWidgetUpdate(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_RESTORE_WORK_NAME,
                IMMEDIATE_RESTORE_WORK_POLICY,
                request
            )
        }.onFailure { Log.w(TAG, "enqueueImmediateRestoreUpdate failed", it) }
    }

    /** APPWIDGET_UPDATE 的 goAsync 路径等待 WorkManager 确认请求已持久化。 */
    internal suspend fun enqueueImmediateWidgetUpdateAndAwait(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        awaitWorkManagerOperation(
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_RESTORE_WORK_NAME,
                IMMEDIATE_RESTORE_WORK_POLICY,
                request,
            ),
        )
    }

    /** 数据库仍在启动且快照缺失时，只保留一个短延迟自愈刷新。 */
    fun scheduleStartupRetry(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(
                    androidx.work.workDataOf(WidgetUpdateWorker.INPUT_STARTUP_RETRY to true),
                )
                .setInitialDelay(STARTUP_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                STARTUP_RETRY_WORK_NAME,
                STARTUP_RETRY_WORK_POLICY,
                request,
            )
        }.onFailure { Log.w(TAG, "schedule startup widget retry failed", it) }
    }

    /**
     * 将边界 Alarm/Work 的一次触发持久交接给独立更新 Work。
     * 它不使用下一边界的 unique name，因此更新过程重排下一责任时不会取消自己。
     */
    internal suspend fun enqueueBoundaryDelivery(context: Context) {
        val delivery = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        awaitWorkManagerOperation(
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_BOUNDARY_DELIVERY_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                delivery,
            ),
        )
    }

    /** 先解析并发布最新内容，再唤醒/启动 Glance session。 */
    internal suspend fun refreshWidgetContent(context: Context): WidgetTimelineResolution {
        val appContext = context.applicationContext
        val widget = DawnWidget()
        val resolution = widget.refreshTimeline(appContext)
        widget.updateAll(appContext)
        return resolution
    }

    /**
     * 立即触发一次更新
     */
    fun triggerImmediateUpdate(context: Context) {
        restoreAfterSystemEvent(context)
    }

    internal fun registerNextCourseUpdateRequest(): WidgetNextUpdateRequest =
        refreshCoordinator.registerRequest()

    /** 立即淘汰所有在途 resolver；它们随后不能发布内容或重新提交 Alarm/Work。 */
    internal fun invalidateRefreshes() {
        refreshCoordinator.registerRequest()
    }

    internal fun publishWidgetTimeline(
        request: WidgetNextUpdateRequest,
        timeline: WidgetTimeline,
    ): Boolean = refreshCoordinator.publish(request, timeline)

    internal suspend fun scheduleNextCourseUpdate(
        context: Context,
        request: WidgetNextUpdateRequest,
        triggerAtMillis: Long?,
    ) {
        val appContext = context.applicationContext
        if (!hasWidgetInstances(appContext)) {
            cancelUpdateAndAwait(appContext)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val workManager = WorkManager.getInstance(appContext)
        val intent = nextCourseUpdateIntent(context)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NEXT_UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            refreshCoordinator.reconcile(
                request = request,
                triggerAtMillis = triggerAtMillis,
                nowMillis = System::currentTimeMillis,
                operations = object : WidgetNextUpdateOperations {
                    override suspend fun cancelExactAlarm() {
                        try {
                            alarmManager.cancel(pendingIntent)
                        } catch (failure: Throwable) {
                            Log.w(TAG, "cancel next-course alarm failed", failure)
                            throw failure
                        }
                    }

                    override fun canScheduleExactAlarm(): Boolean {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
                        return runCatching { alarmManager.canScheduleExactAlarms() }
                            .onFailure { Log.w(TAG, "exact alarm capability query failed", it) }
                            .getOrDefault(false)
                    }

                    override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
                        try {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                triggerAtMillis,
                                pendingIntent,
                            )
                        } catch (failure: Throwable) {
                            Log.w(TAG, "schedule exact next-course alarm failed", failure)
                            throw failure
                        }
                    }

                    override suspend fun cancelFallbackWork() {
                        awaitWorkManagerOperation(
                            workManager.cancelUniqueWork(UNIQUE_NEXT_UPDATE_WORK_NAME),
                        )
                    }

                    override suspend fun enqueueFallbackWork(delayMillis: Long) {
                        val fallbackWork = OneTimeWorkRequestBuilder<WidgetBoundaryFallbackWorker>()
                            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                            .build()
                        awaitWorkManagerOperation(
                            workManager.enqueueUniqueWork(
                                UNIQUE_NEXT_UPDATE_WORK_NAME,
                                ExistingWorkPolicy.REPLACE,
                                fallbackWork,
                            ),
                        )
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "reconcile next-course update failed", failure)
        }
    }

    /** 清除下一课程结束时的精确闹钟，供测试变体在重置隔离状态时收敛调度。 */
    fun cancelNextCourseUpdate(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NEXT_UPDATE_WORK_NAME)
        }.onFailure { Log.w(TAG, "cancel next-course fallback work failed", it) }
        cancelNextCourseAlarm(context)
    }

    private fun cancelNextCourseAlarm(context: Context) {
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

    /**
     * 数据门在线关闭时同步发布安全代际；物理快照删除即使阻塞，也不能延迟撤下课程。
     * Alarm/Work 清理与 Glance 唤醒在应用级作用域继续，不阻塞启动恢复状态机。
     */
    fun enterRecoveryState(context: Context) {
        val appContext = context.applicationContext
        val widget = DawnWidget()
        val request = refreshCoordinator.registerAndPublish(widget.recoverySafeTimeline())
        recoverySurfaceScope.launch {
            try {
                if (!hasWidgetInstances(appContext)) {
                    cancelUpdateAndAwait(appContext)
                    return@launch
                }
                scheduleNextCourseUpdate(appContext, request, triggerAtMillis = null)
                widget.updateAll(appContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "publish recovery-safe Widget failed", failure)
            }
        }
    }

    // 用 Intent(Context, Class) 构造显式广播 Intent：显式 component 能让静态分析
    // 明确识别目标组件，避免 AlarmManager 持有的 PendingIntent 被判定为隐式 Intent。
    private fun nextCourseUpdateIntent(context: Context): Intent =
        Intent(context, DawnWidgetForceUpdateReceiver::class.java).apply {
            action = DawnWidgetForceUpdateReceiver.ACTION_FORCE_UPDATE
            setPackage(context.packageName)
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
                refreshWidgetContent(context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "updateWidgetNow failed", failure)
            }
        }
    }

    internal val widgetExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "Widget coroutine failed", throwable)
    }
    private val recoverySurfaceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + widgetExceptionHandler)
}

/** WorkManager Operation 的非阻塞、可取消挂起桥接；失败必须向调度交接层传播。 */
internal suspend fun awaitWorkManagerOperation(operation: Operation) {
    val future = operation.result
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                try {
                    future.get()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (failure: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(failure.cause ?: failure)
                    }
                }
            },
            DIRECT_EXECUTOR,
        )
    }
}

private val DIRECT_EXECUTOR = Executor { command -> command.run() }
