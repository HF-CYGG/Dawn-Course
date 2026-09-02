package com.dawncourse.feature.timetable.notification

import android.content.Context
import android.content.SharedPreferences
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** 消费静音会话后的持久化结果。 */
data class ConsumedMuteSession(
    /** 指定会话是否由应用所有并成功消费。 */
    val consumed: Boolean,
    /** 消费后仍会阻断最终恢复的 ACTIVE/PENDING 会话数。 */
    val remainingCount: Int
)

/** SharedPreferences 中单条静音责任的 v3 协议，并严格兼容 v2。 */
object MuteSessionRecordCodec {
    private const val KEY_PREFIX = "session:"
    private const val VALUE_VERSION_V2 = "v2"
    private const val VALUE_VERSION_V3 = "v3"
    private const val SEPARATOR = '|'

    /** 生成包含完整 UNMUTE URI 的偏好键。 */
    fun preferenceKey(key: TriggerKey): String = KEY_PREFIX + TriggerUriCodec.encode(key)

    /** 编码显式状态、恢复边界与系统状态租约。 */
    fun encodeValue(record: MuteSessionRecord): String = listOf(
        VALUE_VERSION_V3,
        record.status.name,
        record.recoveryAttempt.toString(),
        record.recoveryAt?.toEpochMilli()?.toString().orEmpty(),
        record.ownership.mode.name,
        record.ownership.originalRingerState.name,
        record.ownership.ownedRingerState?.name.orEmpty(),
        record.ownership.appDndActivationOwned.toString(),
    ).joinToString(SEPARATOR.toString())

    /** 严格解码 v3，并将 v2 映射为只恢复震动的兼容责任。 */
    fun decode(entryName: String, rawValue: Any?): MuteSessionRecord? {
        if (!entryName.startsWith(KEY_PREFIX)) return null
        val key = TriggerUriCodec.decode(entryName.removePrefix(KEY_PREFIX))
            ?.takeIf { value -> value.kind == TriggerKind.UNMUTE }
            ?: return null
        val parts = (rawValue as? String)?.split(SEPARATOR)
            ?: return quarantined(key)
        return when (parts.firstOrNull()) {
            VALUE_VERSION_V2 -> decodeV2(key, parts)
            VALUE_VERSION_V3 -> decodeV3(key, parts)
            else -> quarantined(key)
        }
    }

    private fun decodeV2(key: TriggerKey, parts: List<String>): MuteSessionRecord {
        if (parts.size !in 3..4) return quarantined(key)
        return decodeCommon(key, parts, MuteSystemOwnership.legacyV2())
    }

    private fun decodeV3(key: TriggerKey, parts: List<String>): MuteSessionRecord {
        if (parts.size != 8) return quarantined(key)
        val mode = enumValueOrNull<MuteApplicationMode>(parts[4]) ?: return quarantined(key)
        val original = enumValueOrNull<RingerState>(parts[5]) ?: return quarantined(key)
        val owned = if (parts[6].isBlank()) null else {
            enumValueOrNull<RingerState>(parts[6]) ?: return quarantined(key)
        }
        val dndOwned = parts[7].toBooleanStrictOrNull() ?: return quarantined(key)
        val ownership = MuteSystemOwnership(mode, original, owned, dndOwned)
        if (!isValidOwnership(ownership)) return quarantined(key)
        return decodeCommon(key, parts, ownership)
    }

    private fun decodeCommon(
        key: TriggerKey,
        parts: List<String>,
        ownership: MuteSystemOwnership,
    ): MuteSessionRecord {
        val status = MuteSessionStatus.entries.firstOrNull { value -> value.name == parts[1] }
            ?: return quarantined(key)
        val attempt = parts[2].toIntOrNull() ?: return quarantined(key)
        val recoveryAt = if (parts.size == 3 || parts[3].isBlank()) {
            null
        } else {
            parts[3].toLongOrNull()
                ?.let { epochMillis -> runCatching { Instant.ofEpochMilli(epochMillis) }.getOrNull() }
                ?: return quarantined(key)
        }
        val valid = when (status) {
            MuteSessionStatus.ACTIVE -> attempt == 0
            MuteSessionStatus.RECOVERY_PENDING ->
                attempt in 0 until MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED ->
                attempt == MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
        }
        val quarantinedOwnershipValid = ownership.mode != MuteApplicationMode.UNKNOWN_QUARANTINED ||
            status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
        return if (valid && quarantinedOwnershipValid) {
            MuteSessionRecord(key, status, attempt, recoveryAt, ownership)
        } else {
            quarantined(key)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { value -> value.name == raw }

    private fun isValidOwnership(value: MuteSystemOwnership): Boolean = when (value.mode) {
        MuteApplicationMode.APP_OWNED_DND ->
            value.appDndActivationOwned && (
                value.ownedRingerState == null ||
                    value.originalRingerState == RingerState.NORMAL &&
                    value.ownedRingerState == RingerState.VIBRATE
                )
        MuteApplicationMode.RINGER_VIBRATE_FALLBACK,
        MuteApplicationMode.LEGACY_V2_VIBRATE ->
            value.originalRingerState == RingerState.NORMAL &&
                value.ownedRingerState == RingerState.VIBRATE &&
                !value.appDndActivationOwned
        MuteApplicationMode.UNKNOWN_QUARANTINED ->
            value.ownedRingerState == null && !value.appDndActivationOwned
    }

    /** value 损坏时保留由偏好键证明的责任，并强制进入用户处理隔离态。 */
    private fun quarantined(key: TriggerKey): MuteSessionRecord = MuteSessionRecord(
        key = key,
        status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
        recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS,
        ownership = MuteSystemOwnership.quarantined(),
    )

    /** 将旧 active_unmute_keys + recovery_attempt 转换为显式 v2 记录。 */
    fun fromLegacy(uri: String, recoveryAttempt: Int): MuteSessionRecord? {
        val key = TriggerUriCodec.decode(uri)
            ?.takeIf { value -> value.kind == TriggerKind.UNMUTE }
            ?: return null
        val attempt = recoveryAttempt.coerceIn(0, MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        val status = when {
            attempt >= MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS ->
                MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
            attempt > 0 -> MuteSessionStatus.RECOVERY_PENDING
            else -> MuteSessionStatus.ACTIVE
        }
        return MuteSessionRecord(key, status, attempt)
    }

    /** 判断是否属于版本化会话记录。 */
    fun isRecordEntry(entryName: String): Boolean = entryName.startsWith(KEY_PREFIX)
}

/** SharedPreferences 协议元数据只从无类型快照安全读取，损坏值不得导致启动崩溃。 */
object MuteSessionMigrationValueReader {
    fun isV3Complete(values: Map<String, *>): Boolean =
        values["mute_session_v3_migrated"] as? Boolean ?: false

    fun legacyUris(values: Map<String, *>): Set<String> =
        (values["active_unmute_keys"] as? Set<*>)
            ?.filterIsInstance<String>()
            ?.toSet()
            .orEmpty()
}

/** 记录应用实际承担恢复责任的 UNMUTE Key。 */
@Singleton
class AppMuteSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) : MuteSessionPersistence {
    /** 会话专用偏好文件。 */
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    /** 防止同一进程重复执行旧协议迁移。 */
    @Volatile
    private var migrationChecked = false

    /** 返回全部可解码的应用静音责任。 */
    @Synchronized
    override fun records(): Set<MuteSessionRecord> {
        ensureLegacyMigrated()
        return preferences.all.mapNotNullTo(mutableSetOf()) { (name, value) ->
            MuteSessionRecordCodec.decode(name, value)
        }
    }

    /** SharedPreferences listener 驱动的可观察责任快照，退出收集时正确注销。 */
    override fun observeRecords(): Flow<Set<MuteSessionRecord>> = callbackFlow {
        trySend(records())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(records())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** 在改变铃声模式前持久化 ACTIVE 恢复责任，返回是否新增。 */
    @Synchronized
    override fun add(key: TriggerKey): Boolean {
        return add(key, recoveryAt = null, ownership = MuteSystemOwnership.legacyV2())
    }

    /** 新建责任，并在重复 MUTE 时补齐先前缺失的独立恢复时刻。 */
    @Synchronized
    override fun add(key: TriggerKey, recoveryAt: Instant?): Boolean {
        return add(key, recoveryAt, MuteSystemOwnership.legacyV2())
    }

    /** 新建 v3 责任，并在重复 MUTE 时仅补齐恢复边界。 */
    @Synchronized
    override fun add(
        key: TriggerKey,
        recoveryAt: Instant?,
        ownership: MuteSystemOwnership,
    ): Boolean {
        require(key.kind == TriggerKind.UNMUTE) { "静音会话必须使用 UNMUTE Key" }
        ensureLegacyMigrated()
        val current = record(key)
        if (current != null) {
            if (current.recoveryAt == null && recoveryAt != null) {
                put(current.copy(recoveryAt = recoveryAt))
            }
            return false
        }
        put(MuteSessionRecord(key, MuteSessionStatus.ACTIVE, 0, recoveryAt, ownership))
        return true
    }

    /** DND 激活失败后的 fallback 切换必须一次提交所有活动会话租约。 */
    @Synchronized
    override fun replaceOwnershipForActiveSessions(ownership: MuteSystemOwnership) {
        ensureLegacyMigrated()
        val updated = records().filter { record ->
            record.status != MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
        }.map { record -> record.copy(ownership = ownership) }
        if (updated.isEmpty()) return
        val editor = preferences.edit()
        updated.forEach { record ->
            editor.putString(
                MuteSessionRecordCodec.preferenceKey(record.key),
                MuteSessionRecordCodec.encodeValue(record),
            )
        }
        if (!editor.commit()) throw IOException("应用静音会话租约更新失败")
    }

    /** 回滚未能完成的静音会话。 */
    @Synchronized
    override fun remove(key: TriggerKey) {
        ensureLegacyMigrated()
        removeInternal(key)
    }

    /** 消费指定恢复责任并返回剩余活动阻断数。 */
    @Synchronized
    override fun consume(key: TriggerKey): ConsumedMuteSession {
        ensureLegacyMigrated()
        val consumed = removeInternal(key)
        return ConsumedMuteSession(consumed, activeKeys().size)
    }

    /** 返回指定责任已经失败的恢复次数。 */
    @Synchronized
    override fun recoveryAttempt(key: TriggerKey): Int = record(key)?.recoveryAttempt ?: 0

    /** 同步持久化下一次失败次数及对应显式状态。 */
    @Synchronized
    override fun recordRecoveryFailure(key: TriggerKey): Int {
        val current = record(key) ?: throw IOException("静音恢复责任不存在")
        val next = (current.recoveryAttempt + 1)
            .coerceAtMost(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS)
        val status = if (next < MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS) {
            MuteSessionStatus.RECOVERY_PENDING
        } else {
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
        }
        put(current.copy(status = status, recoveryAttempt = next))
        return next
    }

    /** 仅对仍存在的责任清理计数并恢复 ACTIVE。 */
    @Synchronized
    override fun clearRecoveryAttempt(key: TriggerKey) {
        val current = record(key) ?: return
        put(current.copy(status = MuteSessionStatus.ACTIVE, recoveryAttempt = 0))
    }

    /** 用户明确重试时把 EXHAUSTED 重置为 PENDING(0)。 */
    @Synchronized
    override fun prepareUserRetry(key: TriggerKey): Boolean {
        val current = record(key)
            ?.takeIf { value ->
                value.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED &&
                    value.ownership.mode != MuteApplicationMode.UNKNOWN_QUARANTINED
            }
            ?: return false
        put(current.copy(status = MuteSessionStatus.RECOVERY_PENDING, recoveryAttempt = 0))
        return true
    }

    /** 用户确认后清理责任。 */
    @Synchronized
    override fun releaseByUser(key: TriggerKey): Boolean = removeInternal(key)

    /** 仅允许把用户刚重置的 PENDING 责任回滚为 EXHAUSTED。 */
    @Synchronized
    override fun restoreExhaustedAfterFailedRetry(key: TriggerKey): Boolean {
        val current = record(key)
            ?.takeIf { value -> value.status == MuteSessionStatus.RECOVERY_PENDING }
            ?: return false
        put(
            current.copy(
                status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
                recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            )
        )
        return true
    }

    /** 任一入队失败都保留 Key/恢复时刻并升级为用户处理态。 */
    @Synchronized
    override fun requireUserAction(key: TriggerKey): Boolean {
        val current = record(key) ?: return false
        put(
            current.copy(
                status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
                recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
            )
        )
        return true
    }

    /** 写入或覆盖单条 v3 记录。 */
    private fun put(record: MuteSessionRecord) {
        if (!preferences.edit().putString(
                MuteSessionRecordCodec.preferenceKey(record.key),
                MuteSessionRecordCodec.encodeValue(record)
            ).commit()
        ) {
            throw IOException("应用静音会话持久化失败")
        }
    }

    /** 幂等删除单条责任。 */
    private fun removeInternal(key: TriggerKey): Boolean {
        val preferenceKey = MuteSessionRecordCodec.preferenceKey(key)
        if (!preferences.contains(preferenceKey)) return false
        if (!preferences.edit().remove(preferenceKey).commit()) {
            throw IOException("应用静音会话持久化失败")
        }
        return true
    }

    /** 原子迁移旧集合与 v2 value；已有版本化记录优先，避免覆盖已处理状态。 */
    @Synchronized
    private fun ensureLegacyMigrated() {
        if (migrationChecked) return
        val currentValues = preferences.all
        if (MuteSessionMigrationValueReader.isV3Complete(currentValues)) {
            migrationChecked = true
            return
        }
        val legacyUris = MuteSessionMigrationValueReader.legacyUris(currentValues)
        val recordsByKey = currentValues.mapNotNull { (name, value) ->
            MuteSessionRecordCodec.decode(name, value)
        }.associateByTo(linkedMapOf()) { record -> MuteSessionRecordCodec.preferenceKey(record.key) }
        legacyUris.forEach { uri ->
            val attempt = runCatching {
                preferences.getInt(LEGACY_RECOVERY_ATTEMPT_PREFIX + uri, 0)
            }.getOrDefault(0)
            val record = MuteSessionRecordCodec.fromLegacy(uri, attempt) ?: return@forEach
            recordsByKey.putIfAbsent(MuteSessionRecordCodec.preferenceKey(record.key), record)
        }
        val editor = preferences.edit()
        recordsByKey.forEach { (name, record) ->
            editor.putString(name, MuteSessionRecordCodec.encodeValue(record))
        }
        preferences.all.keys
            .filter { name -> name.startsWith(LEGACY_RECOVERY_ATTEMPT_PREFIX) }
            .forEach(editor::remove)
        editor.remove(LEGACY_ACTIVE_KEYS)
        editor.putBoolean(MIGRATION_V2_COMPLETE, true)
        editor.putBoolean(MIGRATION_V3_COMPLETE, true)
        if (!editor.commit()) throw IOException("静音会话协议迁移失败")
        migrationChecked = true
    }

    private companion object {
        const val PREFERENCES_NAME = "app_owned_mute_sessions"
        const val LEGACY_ACTIVE_KEYS = "active_unmute_keys"
        const val LEGACY_RECOVERY_ATTEMPT_PREFIX = "recovery_attempt:"
        const val MIGRATION_V2_COMPLETE = "mute_session_v2_migrated"
        const val MIGRATION_V3_COMPLETE = "mute_session_v3_migrated"
    }
}
