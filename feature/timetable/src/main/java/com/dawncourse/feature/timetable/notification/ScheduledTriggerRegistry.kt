package com.dawncourse.feature.timetable.notification

import android.content.Context
import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerOrdering
import com.dawncourse.core.domain.model.TriggerPrecision
import com.dawncourse.core.domain.model.TriggerUriCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已下发系统触发器的可枚举持久化注册表。 */
interface ScheduledTriggerRegistry {
    /** 读取健康触发器与损坏证据，单条异常不得吞掉整个快照。 */
    suspend fun read(): ScheduledTriggerRegistrySnapshot

    /** 一次性替换健康记录并将已隔离项写入最小审计；失败必须抛出。 */
    suspend fun replaceAll(
        triggers: List<ScheduledTrigger>,
        quarantinedEntryNames: Set<String> = emptySet()
    )
}

/** Reconciler 可消费的完整注册表快照。 */
data class ScheduledTriggerRegistrySnapshot(
    val records: List<ScheduledTrigger>,
    val corruptedKeys: Set<TriggerKey> = emptySet(),
    val corruptedEntryNames: Set<String> = emptySet()
)

/** 注册表解码结果，显式保留损坏条目名便于测试与诊断。 */
data class DecodedScheduledTriggerRecords(
    /** 成功解码的记录。 */
    val records: List<ScheduledTrigger>,
    /** 属于本注册表但无法解码的条目名。 */
    val corruptedEntryNames: Set<String>,
    /** URI 可解析但 value 损坏的 Key，可用于 cancel 或 Desired 覆盖。 */
    val corruptedKeys: Set<TriggerKey>
)

/** SharedPreferences 单条触发器记录协议。 */
object ScheduledTriggerRecordCodec {
    private const val KEY_PREFIX = "trigger:"
    private const val VALUE_VERSION = "v1"
    private const val VALUE_SEPARATOR = '|'

    /** 生成包含完整 TriggerKey URI 的偏好键。 */
    fun preferenceKey(key: TriggerKey): String = KEY_PREFIX + TriggerUriCodec.encode(key)

    /** 编码触发时刻与实际精度。 */
    fun encodeValue(trigger: ScheduledTrigger): String = listOf(
        VALUE_VERSION,
        trigger.triggerAt.toEpochMilli().toString(),
        trigger.precision.name
    ).joinToString(VALUE_SEPARATOR.toString())

    /** 对专用偏好文件的所有条目逐条解码并隔离损坏数据。 */
    fun decodeAll(entries: Map<String, Any?>): DecodedScheduledTriggerRecords {
        val records = mutableListOf<ScheduledTrigger>()
        val corrupted = mutableSetOf<String>()
        val corruptedKeys = mutableSetOf<TriggerKey>()
        entries.forEach { (entryName, rawValue) ->
            if (!entryName.startsWith(KEY_PREFIX)) return@forEach
            val trigger = decodeOne(entryName, rawValue)
            if (trigger == null) {
                corrupted += entryName
                entryName.removePrefix(KEY_PREFIX)
                    .let(TriggerUriCodec::decode)
                    ?.let(corruptedKeys::add)
            } else {
                records += trigger
            }
        }
        return DecodedScheduledTriggerRecords(
            records = records.distinctBy { trigger -> trigger.key }
                .sortedWith(TriggerOrdering.scheduledComparator),
            corruptedEntryNames = corrupted,
            corruptedKeys = corruptedKeys
        )
    }

    /** 解码单条记录，任一字段损坏时返回 null。 */
    private fun decodeOne(entryName: String, rawValue: Any?): ScheduledTrigger? {
        val key = TriggerUriCodec.decode(entryName.removePrefix(KEY_PREFIX)) ?: return null
        val parts = (rawValue as? String)?.split(VALUE_SEPARATOR) ?: return null
        if (parts.size != 3 || parts[0] != VALUE_VERSION) return null
        val triggerAt = parts[1].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        val precision = TriggerPrecision.entries.firstOrNull { value -> value.name == parts[2] } ?: return null
        return ScheduledTrigger(key, triggerAt, precision)
    }
}

/** 使用专用 SharedPreferences 实现的可枚举注册表。 */
@Singleton
class SharedPreferencesScheduledTriggerRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) : ScheduledTriggerRegistry {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    /** 在 IO 线程解码快照，损坏条目不影响健康条目。 */
    override suspend fun read(): ScheduledTriggerRegistrySnapshot = withContext(Dispatchers.IO) {
        val decoded = ScheduledTriggerRecordCodec.decodeAll(preferences.all)
        ScheduledTriggerRegistrySnapshot(
            records = decoded.records,
            corruptedKeys = decoded.corruptedKeys,
            corruptedEntryNames = decoded.corruptedEntryNames
        )
    }

    /** 使用同一次 commit 替换完整快照，commit=false 转为可见失败。 */
    override suspend fun replaceAll(
        triggers: List<ScheduledTrigger>,
        quarantinedEntryNames: Set<String>
    ): Unit = withContext(Dispatchers.IO) {
        val existingTriggerEntries = preferences.all.keys.filter { entryName ->
            entryName.startsWith(TRIGGER_KEY_PREFIX)
        }
        val editor = preferences.edit()
        existingTriggerEntries.forEach(editor::remove)
        triggers.sortedWith(TriggerOrdering.scheduledComparator).forEach { trigger ->
            editor.putString(
                ScheduledTriggerRecordCodec.preferenceKey(trigger.key),
                ScheduledTriggerRecordCodec.encodeValue(trigger)
            )
        }
        if (quarantinedEntryNames.isNotEmpty()) {
            val previousCount = preferences.getLong(QUARANTINE_TOTAL_COUNT, 0L)
            editor.putLong(
                QUARANTINE_TOTAL_COUNT,
                previousCount + quarantinedEntryNames.size.toLong()
            )
            editor.putLong(QUARANTINE_LAST_SEEN_AT, System.currentTimeMillis())
        }
        if (!editor.commit()) {
            throw IOException("触发器注册表持久化失败")
        }
    }

    private companion object {
        /** 与设置、数据库独立的注册表文件。 */
        const val PREFERENCES_NAME = "scheduled_course_triggers"
        const val TRIGGER_KEY_PREFIX = "trigger:"
        const val QUARANTINE_TOTAL_COUNT = "quarantine_total_count"
        const val QUARANTINE_LAST_SEEN_AT = "quarantine_last_seen_at"
    }
}
