package com.dawncourse.core.data.local.startup

import android.util.AtomicFile
import java.io.File

/** 可替换的 no-backup 原子文件边界，JVM 测试无需依赖 Android 文件 API。 */
internal interface IntegrityVerificationStatePersistence {
    /** 对读改写和删除提供同一进程/跨进程互斥。 */
    fun <T> withExclusiveLock(block: () -> T): T

    /** 文件不存在时返回 null。 */
    fun readOrNull(): ByteArray?

    /** 原子替换完整内容；失败时必须保留旧内容。 */
    fun writeAtomically(bytes: ByteArray)

    /** 删除 base marker 及 AtomicFile 自身管理的残留。 */
    fun deleteAtomically()
}

/** 基于既有 [AndroidAtomicByteStore] 的 Android 原子状态文件。 */
internal class AndroidIntegrityVerificationStatePersistence(
    private val file: File,
) : IntegrityVerificationStatePersistence {
    private val delegate = AndroidAtomicByteStore(file)
    private val atomicFile = AtomicFile(file)

    override fun <T> withExclusiveLock(block: () -> T): T = delegate.withExclusiveLock(block)

    override fun readOrNull(): ByteArray? = delegate.readOrNull()

    override fun writeAtomically(bytes: ByteArray) = delegate.writeAtomically(bytes)

    override fun deleteAtomically() {
        atomicFile.delete()
        check(!atomicFile.baseFile.exists()) { "无法清除完整性恢复责任" }
    }
}

/** 上次持久状态在本次启动原子置为进行中之前的安全快照。 */
internal data class IntegrityVerificationStartupSnapshot(
    /** true 表示上次进程没有完成数据库启动责任。 */
    val previousDatabaseStartupIncomplete: Boolean,
    /** true 表示原状态损坏或读取异常。 */
    val persistentStateUnreadable: Boolean,
    /** 上次成功双扫描时间；证据不可靠时为 null。 */
    val lastSuccessfulVerificationEpochMillis: Long?,
)

/**
 * 不进入 Room 的版本化完整性状态协议。
 *
 * 每次启动先原子写入 `startup_in_progress=true`；只有本次要求的双扫描和其它 Ready
 * 前置条件都成功后，才用一次原子写入同时更新时间并清除责任。
 */
internal class IntegrityVerificationStateStore(
    private val persistence: IntegrityVerificationStatePersistence,
) {
    /** 读取上次证据并原子承担本次启动责任；写入失败时调用方必须停止数据库启动。 */
    fun beginStartup(): IntegrityVerificationStartupSnapshot = persistence.withExclusiveLock {
        val read = readState()
        val snapshot = when (read) {
            StoredStateRead.Missing -> IntegrityVerificationStartupSnapshot(
                previousDatabaseStartupIncomplete = false,
                persistentStateUnreadable = false,
                lastSuccessfulVerificationEpochMillis = null,
            )
            StoredStateRead.Invalid -> IntegrityVerificationStartupSnapshot(
                previousDatabaseStartupIncomplete = true,
                persistentStateUnreadable = true,
                lastSuccessfulVerificationEpochMillis = null,
            )
            is StoredStateRead.Available -> IntegrityVerificationStartupSnapshot(
                previousDatabaseStartupIncomplete = read.state.startupInProgress,
                persistentStateUnreadable = false,
                lastSuccessfulVerificationEpochMillis = read.state.lastSuccessEpochMillis,
            )
        }
        persistence.writeAtomically(
            encode(
                StoredState(
                    startupInProgress = true,
                    lastSuccessEpochMillis = snapshot.lastSuccessfulVerificationEpochMillis,
                ),
            ),
        )
        snapshot
    }

    /** 用单次原子写入同时提交成功时间并清除数据库启动进行中责任。 */
    fun completeSuccessfulVerification(nowEpochMillis: Long) = persistence.withExclusiveLock {
        require(nowEpochMillis > 0L) { "完整性校验成功时间无效" }
        persistence.writeAtomically(
            encode(
                StoredState(
                    startupInProgress = false,
                    lastSuccessEpochMillis = nowEpochMillis,
                ),
            ),
        )
    }

    /** 严格三行格式；损坏、超长、未知字段和读取异常统一视为不可读。 */
    private fun readState(): StoredStateRead {
        val bytes = try {
            persistence.readOrNull()
        } catch (_: Throwable) {
            return StoredStateRead.Invalid
        } ?: return StoredStateRead.Missing
        return try {
            if (bytes.size !in 1..MAX_STATE_BYTES) return StoredStateRead.Invalid
            val lines = bytes.toString(Charsets.UTF_8).lines()
            if (lines.size != 3 || lines[0] != STATE_MAGIC) return StoredStateRead.Invalid
            val startupText = lines[1].removePrefix(STARTUP_PREFIX)
            val lastSuccessText = lines[2].removePrefix(LAST_SUCCESS_PREFIX)
            if (lines[1] == startupText || lines[2] == lastSuccessText) {
                return StoredStateRead.Invalid
            }
            val startupInProgress = when (startupText) {
                "true" -> true
                "false" -> false
                else -> return StoredStateRead.Invalid
            }
            val lastSuccess = when (lastSuccessText) {
                NONE -> null
                else -> lastSuccessText.toLongOrNull()?.takeIf { it > 0L }
                    ?: return StoredStateRead.Invalid
            }
            StoredStateRead.Available(StoredState(startupInProgress, lastSuccess))
        } finally {
            bytes.fill(0)
        }
    }

    /** 固定字段编码不携带路径、口令或 SQL 输出。 */
    private fun encode(state: StoredState): ByteArray = buildString {
        appendLine(STATE_MAGIC)
        append(STARTUP_PREFIX)
        appendLine(state.startupInProgress)
        append(LAST_SUCCESS_PREFIX)
        append(state.lastSuccessEpochMillis ?: NONE)
    }.toByteArray(Charsets.UTF_8)

    /** 内存状态只包含责任位和成功时间。 */
    private data class StoredState(
        val startupInProgress: Boolean,
        val lastSuccessEpochMillis: Long?,
    )

    /** 严格区分缺失与损坏，首次安装只有 Missing 不承担上次崩溃责任。 */
    private sealed interface StoredStateRead {
        data object Missing : StoredStateRead
        data object Invalid : StoredStateRead
        data class Available(val state: StoredState) : StoredStateRead
    }

    private companion object {
        const val STATE_MAGIC = "DAWN_DATABASE_INTEGRITY_STATE_V1"
        const val STARTUP_PREFIX = "startup_in_progress="
        const val LAST_SUCCESS_PREFIX = "last_success_epoch_millis="
        const val NONE = "none"
        const val MAX_STATE_BYTES = 256
    }
}

/** 后台校验失败专用恢复责任；与备份补偿 marker 使用不同文件和固定原因。 */
internal class IntegrityRecoveryRequiredStore(
    private val persistence: IntegrityVerificationStatePersistence,
) {
    /** 任意非空或不可读 marker 都必须进入恢复，损坏内容不能被忽略。 */
    fun requiresRecovery(): Boolean = persistence.withExclusiveLock {
        try {
            persistence.readOrNull() != null
        } catch (_: Throwable) {
            true
        }
    }

    /** 原子写入并严格回读固定 marker；失败由上层切换到 marker 重试模式。 */
    fun markRequiredAndConfirm() = persistence.withExclusiveLock {
        persistence.writeAtomically(MARKER_BYTES.copyOf())
        val actual = persistence.readOrNull()
        check(actual != null && actual.contentEquals(MARKER_BYTES)) {
            "完整性恢复责任未能确认写入"
        }
        actual.fill(0)
    }

    /** 显式恢复或放弃提交时清除，并确认下次冷启动不会重复进入恢复。 */
    fun clearRequiredAndConfirm() = persistence.withExclusiveLock {
        persistence.deleteAtomically()
        check(persistence.readOrNull() == null) { "完整性恢复责任未能确认清除" }
    }

    private companion object {
        val MARKER_BYTES = (
            "DAWN_INTEGRITY_RECOVERY_V1\n" +
                "reason=INTEGRITY_VERIFICATION_FAILED"
            ).toByteArray(Charsets.UTF_8)
    }
}
