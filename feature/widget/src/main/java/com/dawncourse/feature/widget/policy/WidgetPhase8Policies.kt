package com.dawncourse.feature.widget.policy

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 计算当天课程状态变化所需的下一条未来边界。 */
internal object WidgetTimelineBoundaryPolicy {
    fun nextFutureBoundaryMillis(
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        today: LocalDate,
        zoneId: ZoneId,
        nowMillis: Long,
    ): Long? = courses.asSequence()
        .flatMap { course ->
            sequenceOf(
                sectionTimes.getOrNull(course.startSection - 1)?.startTime,
                sectionTimes.getOrNull(course.startSection + course.duration - 2)?.endTime,
            )
        }
        .mapNotNull(::parseTime)
        .map { boundary ->
            today.plusDays(boundary.dayOffset)
                .atTime(boundary.time)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }
        .filter { boundaryMillis -> boundaryMillis > nowMillis }
        .minOrNull()

    private fun parseTime(value: String?): BoundaryTime? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val parts = normalized.split(':')
        if (parts.size == 2 && parts[0].toIntOrNull() == 24) {
            val minute = parts[1].toIntOrNull()
            return if (minute == 0) BoundaryTime(LocalTime.MIDNIGHT, dayOffset = 1L) else null
        }
        return TIME_FORMATTERS.firstNotNullOfOrNull { formatter ->
            runCatching { BoundaryTime(LocalTime.parse(normalized, formatter), dayOffset = 0L) }
                .getOrNull()
        }
    }

    private data class BoundaryTime(val time: LocalTime, val dayOffset: Long)

    private val TIME_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("H:mm"),
        DateTimeFormatter.ofPattern("HH:mm"),
    )
}

/** 精确闹钟尝试后的下一课程刷新调度结果。 */
internal enum class WidgetNextUpdateSchedulingResult {
    EXACT_SCHEDULED,
    EXACT_UNAVAILABLE,
    EXACT_FAILED,
    NO_FUTURE_BOUNDARY,
    STALE_IGNORED,
}

/** Android AlarmManager / WorkManager 的最小可替换操作边界。 */
internal interface WidgetNextUpdateOperations {
    suspend fun cancelExactAlarm()
    fun canScheduleExactAlarm(): Boolean
    suspend fun scheduleExactAlarm(triggerAtMillis: Long)
    suspend fun cancelFallbackWork()
    suspend fun enqueueFallbackWork(delayMillis: Long)
}

/**
 * 线性化下一课程刷新责任：任一时刻只保留精确 Alarm 或唯一 Work fallback。
 *
 * 设置新 Alarm 前先撤销同一 PendingIntent 的旧记录；若权限缺失或调用失败，旧 Alarm
 * 会保持清理状态，再由 Work 接管。无未来边界时同时收敛两条链路，跨日交给午夜刷新。
 */
internal object WidgetNextUpdateCoordinator {
    suspend fun reconcile(
        triggerAtMillis: Long?,
        nowMillis: Long,
        operations: WidgetNextUpdateOperations,
        checkpoint: () -> Unit = {},
    ): WidgetNextUpdateSchedulingResult {
        checkpoint()
        if (triggerAtMillis == null || triggerAtMillis <= nowMillis) {
            operations.cancelExactAlarm()
            checkpoint()
            operations.cancelFallbackWork()
            checkpoint()
            return WidgetNextUpdateSchedulingResult.NO_FUTURE_BOUNDARY
        }
        checkpoint()
        if (!operations.canScheduleExactAlarm()) {
            checkpoint()
            operations.cancelExactAlarm()
            checkpoint()
            operations.enqueueFallbackWork((triggerAtMillis - nowMillis).coerceAtLeast(1L))
            checkpoint()
            return WidgetNextUpdateSchedulingResult.EXACT_UNAVAILABLE
        }

        operations.cancelExactAlarm()
        checkpoint()
        try {
            operations.scheduleExactAlarm(triggerAtMillis)
            checkpoint()
            operations.cancelFallbackWork()
            checkpoint()
            return WidgetNextUpdateSchedulingResult.EXACT_SCHEDULED
        } catch (cancellation: CancellationException) {
            // 补偿只由 SerializedWidgetRefreshCoordinator 统一执行，避免清理异常替换原始取消。
            throw cancellation
        } catch (stale: StaleWidgetRefreshRequest) {
            throw stale
        } catch (_: Throwable) {
            operations.cancelExactAlarm()
            checkpoint()
            operations.enqueueFallbackWork((triggerAtMillis - nowMillis).coerceAtLeast(1L))
            checkpoint()
            return WidgetNextUpdateSchedulingResult.EXACT_FAILED
        }
    }
}

private class StaleWidgetRefreshRequest : RuntimeException()

/** 在任何 Android 对象创建前登记的请求代际。 */
internal class WidgetNextUpdateRequest internal constructor(internal val id: Long)

/**
 * 单进程内统一登记内容与下一边界责任的 latest-wins 代际。
 *
 * 登记与发布使用同一把对象锁：只要较晚请求完成登记，较早 resolver 即使随后完成，
 * 也不能再发布内容；调度提交再由协程 Mutex 串行化并复核同一代际。
 */
internal class SerializedWidgetRefreshCoordinator<T>(
    private val onCompensationFailure: (operation: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    private var generation = 0L
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<T?>(null)
    val state: StateFlow<T?> = mutableState.asStateFlow()

    @Synchronized
    fun registerRequest(): WidgetNextUpdateRequest {
        generation += 1L
        return WidgetNextUpdateRequest(generation)
    }

    /** Recovery 等强制内容切换必须在同一临界区登记并发布，不能被普通刷新插入。 */
    @Synchronized
    fun registerAndPublish(content: T): WidgetNextUpdateRequest {
        generation += 1L
        mutableState.value = content
        return WidgetNextUpdateRequest(generation)
    }

    @Synchronized
    fun publish(request: WidgetNextUpdateRequest, content: T): Boolean {
        if (request.id != generation) return false
        mutableState.value = content
        return true
    }

    suspend fun reconcile(
        request: WidgetNextUpdateRequest,
        triggerAtMillis: Long?,
        nowMillis: () -> Long,
        operations: WidgetNextUpdateOperations,
    ): WidgetNextUpdateSchedulingResult = mutex.withLock {
        if (!isLatest(request)) {
            return@withLock WidgetNextUpdateSchedulingResult.STALE_IGNORED
        }
        try {
            WidgetNextUpdateCoordinator.reconcile(
                triggerAtMillis = triggerAtMillis,
                nowMillis = nowMillis(),
                operations = operations,
                checkpoint = {
                    if (!isLatest(request)) throw StaleWidgetRefreshRequest()
                },
            )
        } catch (cancellation: CancellationException) {
            // WorkManager enqueue 的 Operation 可能在协程取消后继续落库；后排的 cancel
            // Operation 必须在 NonCancellable 中等待完成，覆盖 exact 与 fallback 两条路径。
            compensateResponsibilities(operations)
            throw cancellation
        } catch (_: StaleWidgetRefreshRequest) {
            // 新请求在旧请求的挂起副作用期间登记：先补偿清空旧责任，再让新请求接管。
            compensateResponsibilities(operations)
            WidgetNextUpdateSchedulingResult.STALE_IGNORED
        }
    }

    private suspend fun compensateResponsibilities(operations: WidgetNextUpdateOperations) {
        withContext(NonCancellable) {
            try {
                operations.cancelExactAlarm()
            } catch (failure: Throwable) {
                runCatching { onCompensationFailure("exact alarm", failure) }
            }
            try {
                operations.cancelFallbackWork()
            } catch (failure: Throwable) {
                runCatching { onCompensationFailure("fallback work", failure) }
            }
        }
    }

    @Synchronized
    private fun isLatest(request: WidgetNextUpdateRequest): Boolean = request.id == generation
}

/** Widget 在启动阶段选择的唯一内容路径。 */
internal enum class WidgetContentSource {
    STARTUP_SNAPSHOT,
    DATABASE,
    STARTING_RETRY,
    RECOVERY_SAFE_UI,
}

/** 快照与数据库门禁的 fail-closed 内容源决策。 */
internal object WidgetContentSourcePolicy {
    fun decide(
        hasStartupSnapshot: Boolean,
        databaseReadiness: OperationalDataReadiness,
    ): WidgetContentSource = when {
        databaseReadiness == OperationalDataReadiness.RECOVERY_REQUIRED -> {
            WidgetContentSource.RECOVERY_SAFE_UI
        }
        databaseReadiness == OperationalDataReadiness.READY -> WidgetContentSource.DATABASE
        hasStartupSnapshot -> WidgetContentSource.STARTUP_SNAPSHOT
        else -> WidgetContentSource.STARTING_RETRY
    }
}

/** Widget-only 进程的启动收敛最多执行三次，不让 WorkManager 无限退避。 */
internal object WidgetStartupRetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 2

    fun shouldRetry(
        readiness: OperationalDataReadiness,
        runAttemptCount: Int,
        usedStartupSnapshot: Boolean = false,
    ): Boolean = readiness != OperationalDataReadiness.RECOVERY_REQUIRED &&
        (readiness == OperationalDataReadiness.STARTING || usedStartupSnapshot) &&
        runAttemptCount < MAX_RETRY_ATTEMPTS

    fun shouldRetryFailure(isStartupRetry: Boolean, runAttemptCount: Int): Boolean =
        !isStartupRetry || runAttemptCount < MAX_RETRY_ATTEMPTS
}

/** 保证接收器的异步更新失败时仍释放 PendingResult。 */
internal object ForceUpdateCompletionPolicy {
    suspend fun execute(
        updateAll: suspend () -> Unit,
        enqueueRetry: suspend (Throwable) -> Unit,
        finishPendingResult: () -> Unit,
    ) {
        try {
            updateAll()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            enqueueRetry(failure)
        } finally {
            finishPendingResult()
        }
    }
}

/** 最后一个实例删除后，遗留 FORCE 触发只能清理责任，不得解析 snapshot/Room。 */
internal object WidgetForceUpdatePolicy {
    suspend fun execute(
        hasWidgetInstances: Boolean,
        clearResponsibilities: suspend () -> Unit,
        refreshWidgetContent: suspend () -> Unit,
    ) {
        if (hasWidgetInstances) {
            refreshWidgetContent()
        } else {
            clearResponsibilities()
        }
    }
}

/**
 * 最后一个实例移除后的清理必须复核拓扑：清理挂起期间若重新添加实例，
 * 旧清理可能删掉新实例刚提交的 unique Work，因此清理完成后必须重建整条链路。
 */
internal object WidgetInstanceCleanupPolicy {
    suspend fun execute(
        clearResponsibilities: suspend () -> Unit,
        hasWidgetInstances: () -> Boolean,
        restoreAfterCleanup: () -> Unit,
    ) {
        clearResponsibilities()
        if (hasWidgetInstances()) restoreAfterCleanup()
    }
}

/**
 * 将实例拓扑检查与同步责任提交线性化。
 *
 * WorkManager 的 Operation 可以在锁外等待，但所有 enqueue/cancel 的提交顺序必须在同一
 * monitor 内确定；这样最后到达的 onEnabled/onDisabled 拓扑事件一定拥有最终责任。
 */
internal class WidgetInstanceTopologyCoordinator {
    private val monitor = Any()

    fun <T> clearResponsibilities(clear: () -> T): T = synchronized(monitor) { clear() }

    fun restoreIfPresent(
        hasWidgetInstances: () -> Boolean,
        restoreResponsibilities: () -> Unit,
    ) {
        synchronized(monitor) {
            if (hasWidgetInstances()) restoreResponsibilities()
        }
    }
}

/** Receiver 对广播的可观察处理路径。 */
internal enum class WidgetBroadcastAction {
    RESTORE_AFTER_SYSTEM_EVENT,
    REFRESH_WIDGET_CONTENT,
    DELEGATE_TO_GLANCE,
}

/** 将 Android action 字符串收敛为互斥处理责任。 */
internal object WidgetBroadcastActionPolicy {
    fun resolve(action: String?): WidgetBroadcastAction = when (action) {
        ACTION_TIME_CHANGED,
        ACTION_DATE_CHANGED,
        ACTION_TIMEZONE_CHANGED -> WidgetBroadcastAction.RESTORE_AFTER_SYSTEM_EVENT
        ACTION_APPWIDGET_UPDATE -> WidgetBroadcastAction.REFRESH_WIDGET_CONTENT
        else -> WidgetBroadcastAction.DELEGATE_TO_GLANCE
    }

    private const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"
    private const val ACTION_DATE_CHANGED = "android.intent.action.DATE_CHANGED"
    private const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    private const val ACTION_APPWIDGET_UPDATE = "android.appwidget.action.APPWIDGET_UPDATE"
}
