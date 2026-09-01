package com.dawncourse.feature.timetable.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 一次性触发广播（AlarmManager 拉起的 REMINDER / MUTE）在进程冷启动窗口内没等到
 * 数据库就绪时的持久重试协议。
 *
 * UNMUTE 有独立的 [MuteRecoveryWorker] 负责，不进入本 Worker。
 */
object TriggerReadinessRetryInputPolicy {
    /** Work Data 中完整 TriggerKey URI 的键名。 */
    const val INPUT_TRIGGER_URI = "trigger_uri"

    /** 只接受 REMINDER / MUTE Key。 */
    fun createInputData(key: TriggerKey): Data {
        require(key.kind == TriggerKind.REMINDER || key.kind == TriggerKind.MUTE) {
            "触发就绪重试 Worker 只接受 REMINDER 或 MUTE Key"
        }
        return workDataOf(INPUT_TRIGGER_URI to TriggerUriCodec.encode(key))
    }

    /** 解码时同时校验类型与规范编码，拒绝宽松等价 URI。 */
    fun decode(data: Data): TriggerKey? {
        val raw = data.getString(INPUT_TRIGGER_URI) ?: return null
        val key = TriggerUriCodec.decode(raw)
            ?.takeIf { it.kind == TriggerKind.REMINDER || it.kind == TriggerKind.MUTE }
            ?: return null
        return key.takeIf { TriggerUriCodec.encode(it) == raw }
    }
}

/** Receiver 在启动窗口内没等到数据库就绪时，把完整 Key 交出去的持久重试边界。 */
interface TriggerReadinessRetryScheduler {
    /** 安排一次唯一、可被 WorkManager 退避重试的“就绪后补投”。 */
    fun enqueue(key: TriggerKey)
}

/** 用唯一 WorkManager 任务持久保存完整 Key 的就绪重试调度器。 */
@Singleton
class WorkManagerTriggerReadinessRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : TriggerReadinessRetryScheduler {
    override fun enqueue(key: TriggerKey) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<TriggerReadinessRetryWorker>()
                .setInputData(TriggerReadinessRetryInputPolicy.createInputData(key))
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(key),
                // 同一 Key 已在排队/运行的补投继续沿用，不重复堆叠。
                ExistingWorkPolicy.KEEP,
                request
            )
        }.onFailure { Log.w(TAG, "触发就绪重试入队失败: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "TriggerReadinessRetry"
        /** 退避基数：启动通常几秒内完成，30s 线性退避足够且不至于长时间占用。 */
        internal const val BACKOFF_SECONDS = 30L

        /** 唯一任务名包含完整稳定 Key，但不得写入日志。 */
        internal fun workName(key: TriggerKey): String =
            "TriggerReadinessRetry:${TriggerUriCodec.encode(key)}"
    }
}

/**
 * 只补投一个错过就绪窗口的一次性触发广播：等待数据库就绪后，用与原闹钟完全一致的
 * 显式 Intent 重新投递给对应 Receiver，由 Receiver 走完整的领域二次校验（开关、当前
 * 学期、课程、时间窗口、去重）。本 Worker 不读取触发器注册表，也不触发每日全量 reconcile。
 */
@HiltWorker
class TriggerReadinessRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val operationalDataGate: OperationalDataGate
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val key = TriggerReadinessRetryInputPolicy.decode(inputData) ?: return Result.failure()
        return try {
            when (operationalDataGate.awaitReadiness(READINESS_AWAIT_TIMEOUT_MS)) {
                OperationalDataReadiness.READY -> {
                    redeliver(key)
                    Result.success()
                }
                OperationalDataReadiness.STARTING -> {
                    // 启动仍在进行：交给 WorkManager 退避重试；超过上限后收敛为 success，
                    // 避免数据库长期卡在 STARTING 时无限占用后台配额。
                    if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.success() else Result.retry()
                }
                OperationalDataReadiness.RECOVERY_REQUIRED -> {
                    // 需要前台恢复流程，补投一次性广播无意义；等恢复完成后由启动对账处理。
                    Result.success()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.e(TAG, "触发就绪重试补投失败: ${failure.javaClass.simpleName}")
            Result.retry()
        }
    }

    /** 用与 AlarmManager 原 PendingIntent 相同的显式 component/action/data 重新广播。 */
    private fun redeliver(key: TriggerKey) {
        val receiver = when (key.kind) {
            TriggerKind.REMINDER -> ReminderReceiver::class.java
            TriggerKind.MUTE -> SilenceReceiver::class.java
            TriggerKind.UNMUTE -> return
        }
        val intent = Intent(applicationContext, receiver).apply {
            action = TriggerIntentPolicy.expectedAction(key.kind)
            data = Uri.parse(TriggerUriCodec.encode(key))
        }
        applicationContext.sendBroadcast(intent)
    }

    private companion object {
        /** 日志标签不包含 TriggerKey 或课程数据。 */
        const val TAG = "TriggerReadinessRetry"
        /** Worker 自身有独立生命周期，可用比 Receiver goAsync 窗口更长的等待。 */
        const val READINESS_AWAIT_TIMEOUT_MS = 20_000L
        /** 含首次共 5 次尝试；超过后不再重试。 */
        const val MAX_ATTEMPTS = 5
    }
}
