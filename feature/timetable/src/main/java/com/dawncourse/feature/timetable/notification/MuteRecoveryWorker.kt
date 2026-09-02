package com.dawncourse.feature.timetable.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 专用静音恢复 Worker 的严格输入协议。 */
object MuteRecoveryWorkerInputPolicy {
    /** Work Data 中完整 TriggerKey URI 的键名。 */
    const val INPUT_TRIGGER_URI = "trigger_uri"

    /** 只允许 UNMUTE Key 进入恢复 Worker。 */
    fun createInputData(key: TriggerKey): Data {
        require(key.kind == TriggerKind.UNMUTE) { "静音恢复 Worker 只接受 UNMUTE Key" }
        return workDataOf(INPUT_TRIGGER_URI to TriggerUriCodec.encode(key))
    }

    /** 解码时同时验证类型和规范编码，拒绝宽松等价 URI。 */
    fun decode(data: Data): TriggerKey? {
        val raw = data.getString(INPUT_TRIGGER_URI) ?: return null
        val key = TriggerUriCodec.decode(raw)
            ?.takeIf { value -> value.kind == TriggerKind.UNMUTE }
            ?: return null
        return key.takeIf { value -> TriggerUriCodec.encode(value) == raw }
    }
}

/** UI、Receiver 与 Worker 共用的持久恢复任务调度边界。 */
interface MuteRecoveryWorkScheduler {
    /** 按已失败次数安排下一次有限恢复。 */
    suspend fun enqueueRetry(key: TriggerKey, attempt: Int): Boolean

    /** 用户明确重试时立即执行。 */
    suspend fun enqueueNow(key: TriggerKey): Boolean

    /** ACTIVE 责任按独立持久结束时刻执行。 */
    suspend fun enqueueAt(key: TriggerKey, runAt: Instant): Boolean

    /** 责任已清理时取消尚未执行的任务。 */
    fun cancel(key: TriggerKey)
}

/** 使用唯一 WorkManager 任务持久保存完整 Key 的恢复调度器。 */
@Singleton
class WorkManagerMuteRecoveryScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : MuteRecoveryWorkScheduler {
    override suspend fun enqueueRetry(key: TriggerKey, attempt: Int): Boolean {
        require(attempt in 1 until MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        return enqueueAt(key, Instant.now().plusSeconds(recoveryDelayMinutes(attempt) * 60L))
    }

    override suspend fun enqueueNow(key: TriggerKey): Boolean = enqueueAt(key, Instant.now())

    override suspend fun enqueueAt(key: TriggerKey, runAt: Instant): Boolean {
        val delayMillis = calculateDelayMillis(Instant.now(), runAt)
        val builder = OneTimeWorkRequestBuilder<MuteRecoveryWorker>()
            .setInputData(MuteRecoveryWorkerInputPolicy.createInputData(key))
        if (delayMillis > 0) builder.setInitialDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(
            workName(key),
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
        return awaitWorkManagerOperation(operation)
    }

    override fun cancel(key: TriggerKey) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    companion object {
        /** 有限恢复使用短线性延迟，避免 Receiver 内即时自旋。 */
        internal fun recoveryDelayMinutes(attempt: Int): Long = attempt.toLong()

        /** 唯一任务名包含完整稳定 Key，但不得写入日志。 */
        internal fun workName(key: TriggerKey): String =
            "MuteRecovery:${TriggerUriCodec.encode(key)}"

        /** 过去时刻立即执行，未来时刻使用非负延迟。 */
        internal fun calculateDelayMillis(now: Instant, runAt: Instant): Long =
            Duration.between(now, runAt).toMillis().coerceAtLeast(0L)
    }
}

/** 完成回调直接执行，内部只读取已经完成的 Future，不阻塞调用线程。 */
private val workManagerOperationExecutor = Executor { command -> command.run() }

/** 把 WorkManager Operation.result 转换为可取消的非阻塞挂起结果。 */
internal suspend fun awaitWorkManagerOperation(operation: Operation): Boolean {
    val future = operation.result
    return awaitEnqueueCompletion { complete ->
        future.addListener(
            {
                complete(runCatching { future.get(); true }.getOrDefault(false))
            },
            workManagerOperationExecutor
        )
    }
}

/** 可由 JVM fake 驱动的异步入队完成适配器。 */
internal suspend fun awaitEnqueueCompletion(
    register: (((Boolean) -> Unit) -> Unit)
): Boolean = suspendCancellableCoroutine { continuation ->
    register { success ->
        if (continuation.isActive) continuation.resume(success)
    }
}

/**
 * 只恢复一个持久 Key 的专用 Worker；不读取触发器注册表，也不触发每日全量 reconcile。
 */
@HiltWorker
class MuteRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val silenceHelper: SilenceHelper,
    private val coordinator: MuteSessionCoordinator,
    private val notificationHelper: MuteRecoveryNotificationHelper
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val key = MuteRecoveryWorkerInputPolicy.decode(inputData) ?: return Result.failure()
        return try {
            when (silenceHelper.unmuteOwnedSession(applicationContext, key)) {
                is MuteRecoveryOutcome.RetryRequired -> {
                    // 同一持久 Work 由 WorkManager 退避重试；显式状态保证第三次后必然收敛为 success。
                    Result.retry()
                }
                MuteRecoveryOutcome.Exhausted -> {
                    notificationHelper.refreshForCurrentState()
                    Result.success()
                }
                MuteRecoveryOutcome.Recovered,
                MuteRecoveryOutcome.ResponsibilityReleased,
                MuteRecoveryOutcome.NoAction -> {
                    notificationHelper.refreshForCurrentState()
                    Result.success()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.e(TAG, "静音恢复 Worker 处理失败: ${failure.javaClass.simpleName}")
            // REPLACE 会重置 WorkManager.runAttemptCount，因此异常也必须推进持久在责任
            // 记录上的 recoveryAttempt。只有确认已进入 EXHAUSTED 或责任已不存在，才允许
            // 收敛成功；持久写入或警示刷新失败都继续重试。
            try {
                when (coordinator.recordWorkerFailure(key)) {
                    is MuteRecoveryOutcome.RetryRequired -> Result.retry()
                    MuteRecoveryOutcome.Exhausted,
                    MuteRecoveryOutcome.NoAction -> {
                        notificationHelper.refreshForCurrentState()
                        Result.success()
                    }
                    MuteRecoveryOutcome.Recovered,
                    MuteRecoveryOutcome.ResponsibilityReleased -> Result.retry()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (persistenceFailure: Exception) {
                Log.e(TAG, "静音恢复失败次数持久化失败: ${persistenceFailure.javaClass.simpleName}")
                Result.retry()
            }
        }
    }

    private companion object {
        /** 日志标签不包含 TriggerKey 或课程数据。 */
        const val TAG = "MuteRecoveryWorker"
    }
}
