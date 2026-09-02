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
import com.dawncourse.core.domain.model.TriggerPrecision
import com.dawncourse.core.domain.model.TriggerUriCodec
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次性触发广播（AlarmManager 拉起的 REMINDER / MUTE）在进程冷启动窗口内没等到
 * 数据库就绪时的持久重试协议。
 *
 * UNMUTE 有独立的 [MuteRecoveryWorker] 负责，不进入本 Worker。
 */
object TriggerReadinessRetryInputPolicy {
    /** Work Data 中完整 TriggerKey URI 的键名。 */
    const val INPUT_TRIGGER_URI = "trigger_uri"
    /** 下发时记录的闹钟精度（可空）；随补投任务持久保存，避免启动对账清除注册表后丢失。 */
    const val INPUT_PRECISION = "trigger_precision"

    /** 只接受 REMINDER / MUTE Key。 */
    fun createInputData(key: TriggerKey, precision: TriggerPrecision?): Data {
        require(key.kind == TriggerKind.REMINDER || key.kind == TriggerKind.MUTE) {
            "触发就绪重试 Worker 只接受 REMINDER 或 MUTE Key"
        }
        return if (precision == null) {
            workDataOf(INPUT_TRIGGER_URI to TriggerUriCodec.encode(key))
        } else {
            workDataOf(
                INPUT_TRIGGER_URI to TriggerUriCodec.encode(key),
                INPUT_PRECISION to precision.name
            )
        }
    }

    /** 解码时同时校验类型与规范编码，拒绝宽松等价 URI。 */
    fun decode(data: Data): TriggerKey? {
        val raw = data.getString(INPUT_TRIGGER_URI) ?: return null
        val key = TriggerUriCodec.decode(raw)
            ?.takeIf { it.kind == TriggerKind.REMINDER || it.kind == TriggerKind.MUTE }
            ?: return null
        return key.takeIf { TriggerUriCodec.encode(it) == raw }
    }

    /** 解出随任务保存的精度，损坏或缺失时返回 null。 */
    fun decodePrecision(data: Data): TriggerPrecision? =
        data.getString(INPUT_PRECISION)?.let { name ->
            TriggerPrecision.entries.firstOrNull { it.name == name }
        }
}

/** Receiver 已消费一次性 Alarm 后必须持久保存的补投责任。 */
data class TriggerReadinessRetryRecord(
    /** 完整、带日期和 Profile 的稳定身份。 */
    val key: TriggerKey,
    /** 原始 Alarm 实际精度；MUTE 或旧记录可为空。 */
    val precision: TriggerPrecision?
)

/** 与 WorkManager 解耦的补投 journal，确保异步入队失败后仍有冷启动恢复入口。 */
interface TriggerReadinessRetryJournal {
    /** 返回全部仍未确认交给 WorkManager 的补投责任。 */
    fun records(): Set<TriggerReadinessRetryRecord>

    /** 在调用 WorkManager 前同步持久化责任。 */
    fun put(record: TriggerReadinessRetryRecord)

    /** 仅在 WorkManager Operation 成功完成后清理责任。 */
    fun remove(key: TriggerKey)
}

/** 应用私有 SharedPreferences 实现；系统备份和 D2D 已对 sharedpref 域默认拒绝。 */
@Singleton
class AppTriggerReadinessRetryJournal @Inject constructor(
    @ApplicationContext context: Context
) : TriggerReadinessRetryJournal {
    /** 独立文件避免被调度注册表的全量 replace 覆盖。 */
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun records(): Set<TriggerReadinessRetryRecord> = preferences.all.mapNotNullTo(
        linkedSetOf()
    ) { (name, rawValue) ->
        val rawUri = name.removePrefix(ENTRY_PREFIX).takeIf { name.startsWith(ENTRY_PREFIX) }
            ?: return@mapNotNullTo null
        val key = TriggerUriCodec.decode(rawUri)
            ?.takeIf { value ->
                (value.kind == TriggerKind.REMINDER || value.kind == TriggerKind.MUTE) &&
                    TriggerUriCodec.encode(value) == rawUri
            }
            ?: return@mapNotNullTo null
        val precision = (rawValue as? String)
            ?.takeIf(String::isNotBlank)
            ?.let { nameValue -> TriggerPrecision.entries.firstOrNull { it.name == nameValue } }
        TriggerReadinessRetryRecord(key, precision)
    }

    @Synchronized
    override fun put(record: TriggerReadinessRetryRecord) {
        require(record.key.kind == TriggerKind.REMINDER || record.key.kind == TriggerKind.MUTE) {
            "触发就绪补投 journal 只接受 REMINDER 或 MUTE Key"
        }
        val committed = preferences.edit()
            .putString(preferenceKey(record.key), record.precision?.name.orEmpty())
            .commit()
        if (!committed) throw IOException("触发就绪补投责任持久化失败")
    }

    @Synchronized
    override fun remove(key: TriggerKey) {
        val name = preferenceKey(key)
        if (!preferences.contains(name)) return
        if (!preferences.edit().remove(name).commit()) {
            throw IOException("触发就绪补投责任清理失败")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "dc_trigger_readiness_retry"
        const val ENTRY_PREFIX = "retry:"

        fun preferenceKey(key: TriggerKey): String = ENTRY_PREFIX + TriggerUriCodec.encode(key)
    }
}

/**
 * 先持久化 journal，再等待异步入队；失败或取消时保留记录，成功才尝试清理。
 *
 * 清理失败不会把已成功持久化到 WorkManager 的任务伪装成失败；残留 journal 只会在
 * 下次启动产生一次由唯一 Work 去重的幂等重放。
 */
internal suspend fun persistAndEnqueueReadinessRetry(
    record: TriggerReadinessRetryRecord,
    journal: TriggerReadinessRetryJournal,
    enqueue: suspend () -> Boolean
): Boolean {
    val journalPersisted = runCatching { journal.put(record) }.isSuccess
    val enqueued = try {
        enqueue()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    if (journalPersisted && enqueued) runCatching { journal.remove(record.key) }
    return enqueued
}

/** Receiver 在启动窗口内没等到数据库就绪时，把完整 Key 交出去的持久重试边界。 */
interface TriggerReadinessRetryScheduler {
    /**
     * 安排一次唯一、可被 WorkManager 退避重试的“就绪后补投”。
     *
     * [precision] 为下发该 occurrence 时记录的实际闹钟精度：随任务持久保存，
     * 即使就绪后启动对账先清掉注册表记录，补投仍能据此判定非精确迟到宽限。
     */
    suspend fun enqueue(key: TriggerKey, precision: TriggerPrecision?): Boolean

    /** 冷启动/每日对账时重放尚未确认交给 WorkManager 的 journal。 */
    suspend fun reconcilePending(): Boolean
}

/** 重放全部责任且不短路；任一入队未确认时由上层 DailyScheduler 汇总为 retry。 */
internal suspend fun reconcileTriggerReadinessRetryRecords(
    records: Set<TriggerReadinessRetryRecord>,
    enqueue: suspend (TriggerReadinessRetryRecord) -> Boolean
): Boolean {
    var allEnqueued = true
    records.forEach { record ->
        if (!enqueue(record)) allEnqueued = false
    }
    return allEnqueued
}

/** 用唯一 WorkManager 任务持久保存完整 Key 的就绪重试调度器。 */
@Singleton
class WorkManagerTriggerReadinessRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journal: AppTriggerReadinessRetryJournal
) : TriggerReadinessRetryScheduler {
    override suspend fun enqueue(key: TriggerKey, precision: TriggerPrecision?): Boolean =
        withContext(Dispatchers.IO) {
            val record = TriggerReadinessRetryRecord(key, precision)
            val enqueued = try {
                persistAndEnqueueReadinessRetry(record, journal) {
                    enqueuePersisted(record)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "触发就绪重试责任持久化失败: ${failure.javaClass.simpleName}")
                false
            }
            if (!enqueued) Log.w(TAG, "触发就绪重试入队未确认，保留 journal 等待重放")
            enqueued
        }

    override suspend fun reconcilePending(): Boolean = withContext(Dispatchers.IO) {
        reconcileTriggerReadinessRetryRecords(journal.records()) { record ->
            val enqueued = enqueuePersisted(record)
            if (enqueued) runCatching { journal.remove(record.key) }
            enqueued
        }
    }

    /** WorkManager 命令只等待广播剩余窗口内的有限时间，超时由 journal 兜底。 */
    private suspend fun enqueuePersisted(record: TriggerReadinessRetryRecord): Boolean {
        val request = OneTimeWorkRequestBuilder<TriggerReadinessRetryWorker>()
            .setInputData(
                TriggerReadinessRetryInputPolicy.createInputData(record.key, record.precision)
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(
            workName(record.key),
            // 同一 Key 已在排队/运行的补投继续沿用，不重复堆叠。
            ExistingWorkPolicy.KEEP,
            request
        )
        return withTimeoutOrNull(ENQUEUE_AWAIT_TIMEOUT_MS) {
            awaitWorkManagerOperation(operation)
        } ?: false
    }

    companion object {
        private const val TAG = "TriggerReadinessRetry"
        /** 退避基数：启动通常几秒内完成，30s 线性退避足够且不至于长时间占用。 */
        internal const val BACKOFF_SECONDS = 30L
        /** Receiver 已等待数据库 8 秒，最多再等 1 秒确认 WorkManager 已持久化。 */
        internal const val ENQUEUE_AWAIT_TIMEOUT_MS = 1_000L

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
        val precision = TriggerReadinessRetryInputPolicy.decodePrecision(inputData)
        return try {
            when (operationalDataGate.awaitReadiness(READINESS_AWAIT_TIMEOUT_MS)) {
                OperationalDataReadiness.READY -> {
                    redeliver(key, precision)
                    Result.success()
                }
                // 数据库仍在启动、或需要前台恢复：一次性 Alarm 已被系统消费，启动后的
                // 常规对账只会重排 triggerAt > now 的触发器，无法恢复“已错过但课程仍在
                // 进行”的 REMINDER/MUTE。因此保留可重试的持久责任，交给 WorkManager 退避
                // 重试直到 READY——不设尝试上限。一旦 READY，redeliver 会让 Receiver 做
                // 完整领域二次校验：occurrence 已彻底过期就自然不投递并收敛为 success，
                // 不会无限重试有效工作；只有设备长期无法就绪时才持续退避（间隔可达数小时，
                // 单次工作极小）。
                OperationalDataReadiness.STARTING,
                OperationalDataReadiness.RECOVERY_REQUIRED -> Result.retry()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.e(TAG, "触发就绪重试补投失败: ${failure.javaClass.simpleName}")
            Result.retry()
        }
    }

    /**
     * 用与 AlarmManager 原 PendingIntent 相同的显式 component/action/data 重新广播，
     * 并把持久保存的原始精度作为 extra 一并带上——就绪后启动对账可能已清掉注册表记录，
     * Receiver 优先用该 extra 判定非精确迟到宽限。
     */
    private fun redeliver(key: TriggerKey, precision: TriggerPrecision?) {
        val receiver = when (key.kind) {
            TriggerKind.REMINDER -> ReminderReceiver::class.java
            TriggerKind.MUTE -> SilenceReceiver::class.java
            TriggerKind.UNMUTE -> return
        }
        val intent = Intent(applicationContext, receiver).apply {
            action = TriggerIntentPolicy.expectedAction(key.kind)
            data = Uri.parse(TriggerUriCodec.encode(key))
            if (precision != null) {
                putExtra(ReminderReceiver.EXTRA_TRIGGER_PRECISION, precision.name)
            }
        }
        applicationContext.sendBroadcast(intent)
    }

    private companion object {
        /** 日志标签不包含 TriggerKey 或课程数据。 */
        const val TAG = "TriggerReadinessRetry"
        /** Worker 自身有独立生命周期，可用比 Receiver goAsync 窗口更长的等待。 */
        const val READINESS_AWAIT_TIMEOUT_MS = 20_000L
    }
}
