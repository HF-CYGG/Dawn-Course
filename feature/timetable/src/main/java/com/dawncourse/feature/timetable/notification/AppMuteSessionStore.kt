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

/** SharedPreferences 中单条静音责任的 v2 协议。 */
object MuteSessionRecordCodec {
    private const val KEY_PREFIX = "session:"
    private const val VALUE_VERSION = "v2"
    private const val SEPARATOR = '|'

    /** 生成包含完整 UNMUTE URI 的偏好键。 */
    fun preferenceKey(key: TriggerKey): String = KEY_PREFIX + TriggerUriCodec.encode(key)

    /** 编码显式状态和失败次数。 */
    fun encodeValue(record: MuteSessionRecord): String = listOf(
        VALUE_VERSION,
        record.status.name,
        record.recoveryAttempt.toString(),
        record.recoveryAt?.toEpochMilli()?.toString().orEmpty()
    ).joinToString(SEPARATOR.toString())

    /** 严格解码 v2 记录。 */
    fun decode(entryName: String, rawValue: Any?): MuteSessionRecord? {
        if (!entryName.startsWith(KEY_PREFIX)) return null
        val key = TriggerUriCodec.decode(entryName.removePrefix(KEY_PREFIX))
            ?.takeIf { value -> value.kind == TriggerKind.UNMUTE }
            ?: return null
        val parts = (rawValue as? String)?.split(SEPARATOR)
            ?: return quarantined(key)
        if (parts.size !in 3..4 || parts[0] != VALUE_VERSION) return quarantined(key)
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
        return if (valid) MuteSessionRecord(key, status, attempt, recoveryAt) else quarantined(key)
    }

    /** value 损坏时保留由偏好键证明的责任，并强制进入用户处理隔离态。 */
    private fun quarantined(key: TriggerKey): MuteSessionRecord = MuteSessionRecord(
        key = key,
        status = MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
        recoveryAttempt = MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS
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

    /** 判断是否属于 v2 会话记录。 */
    fun isRecordEntry(entryName: String): Boolean = entryName.startsWith(KEY_PREFIX)
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
        return add(key, recoveryAt = null)
    }

    /** 新建责任，并在重复 MUTE 时补齐先前缺失的独立恢复时刻。 */
    @Synchronized
    override fun add(key: TriggerKey, recoveryAt: Instant?): Boolean {
        require(key.kind == TriggerKind.UNMUTE) { "静音会话必须使用 UNMUTE Key" }
        ensureLegacyMigrated()
        val current = record(key)
        if (current != null) {
            if (current.recoveryAt == null && recoveryAt != null) {
                put(current.copy(recoveryAt = recoveryAt))
            }
            return false
        }
        put(MuteSessionRecord(key, MuteSessionStatus.ACTIVE, 0, recoveryAt))
        return true
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
            ?.takeIf { value -> value.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED }
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

    /** 写入或覆盖单条 v2 记录。 */
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

    /** 原子迁移旧协议；已有 v2 记录优先，避免覆盖已处理状态。 */
    @Synchronized
    private fun ensureLegacyMigrated() {
        if (migrationChecked) return
        if (preferences.getBoolean(MIGRATION_COMPLETE, false)) {
            migrationChecked = true
            return
        }
        val legacyUris = preferences.getStringSet(LEGACY_ACTIVE_KEYS, emptySet())?.toSet().orEmpty()
        val editor = preferences.edit()
        legacyUris.forEach { uri ->
            val attempt = runCatching {
                preferences.getInt(LEGACY_RECOVERY_ATTEMPT_PREFIX + uri, 0)
            }.getOrDefault(0)
            val record = MuteSessionRecordCodec.fromLegacy(uri, attempt) ?: return@forEach
            val newKey = MuteSessionRecordCodec.preferenceKey(record.key)
            if (!preferences.contains(newKey)) {
                editor.putString(newKey, MuteSessionRecordCodec.encodeValue(record))
            }
        }
        preferences.all.keys
            .filter { name -> name.startsWith(LEGACY_RECOVERY_ATTEMPT_PREFIX) }
            .forEach(editor::remove)
        editor.remove(LEGACY_ACTIVE_KEYS)
        editor.putBoolean(MIGRATION_COMPLETE, true)
        if (!editor.commit()) throw IOException("静音会话协议迁移失败")
        migrationChecked = true
    }

    private companion object {
        const val PREFERENCES_NAME = "app_owned_mute_sessions"
        const val LEGACY_ACTIVE_KEYS = "active_unmute_keys"
        const val LEGACY_RECOVERY_ATTEMPT_PREFIX = "recovery_attempt:"
        const val MIGRATION_COMPLETE = "mute_session_v2_migrated"
    }
}
