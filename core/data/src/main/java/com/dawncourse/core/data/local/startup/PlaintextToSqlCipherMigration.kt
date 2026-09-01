package com.dawncourse.core.data.local.startup

import java.io.File

/** sqlite_schema 中参与迁移一致性校验的稳定字段。 */
data class DatabaseSchemaIdentity(
    val type: String,
    val name: String,
    val tableName: String,
    val sql: String
)

/** 数据库逻辑内容指纹；表和 schema 必须由实现按稳定顺序返回。 */
data class DatabaseMigrationSnapshot(
    val userVersion: Int,
    /** SQLite auto_vacuum 模式；sqlcipher_export 不会自动复制该 PRAGMA。 */
    val autoVacuum: Int,
    val schema: List<DatabaseSchemaIdentity>,
    val userTableRowCounts: Map<String, Long>
)

/** 数据库快照及其结构/加密完整性结果。 */
data class DatabaseMigrationVerification(
    val snapshot: DatabaseMigrationSnapshot,
    val integrityOk: Boolean,
    val cipherIntegrityOk: Boolean?
)

/** 单次迁移的全部文件均位于主数据库目录，确保换入不会跨文件系统。 */
data class DatabaseMigrationAttempt(
    val id: String,
    val mainDatabase: File,
    val plaintextPreimage: File,
    val encryptedTemp: File
)

/** 持久化 journal 的状态；SWAP_PENDING 之后的任意崩溃都必须恢复 pre-image。 */
enum class DatabaseMigrationStage {
    INITIALIZED,
    PREIMAGE_READY,
    ENCRYPTED_TEMP_READY,
    SWAP_PENDING,
    SWAPPED_NOT_VERIFIED,
    COMPLETE,
    ROLLED_BACK
}

/** 启动时对上次未完成 journal 的恢复结果。 */
enum class DatabaseMigrationRecovery {
    NoWork,
    Recovered,
    Failed
}

/** 迁移失败的稳定分类，不暴露路径、口令或底层异常。 */
enum class DatabaseMigrationFailure {
    CrashRecoveryFailed,
    SourcePreparationFailed,
    PreimageFailed,
    ExportFailed,
    ValidationFailed,
    SwapFailed,
    ReopenValidationFailed,
    RollbackFailed
}

/** 明文数据库原子加密迁移的稳定结果。 */
sealed interface PlaintextToSqlCipherMigrationResult {
    /**
     * 加密库换入且使用相同口令重开验证成功；旧明文 pre-image 仍保留。
     *
     * journal 仍停留在 [DatabaseMigrationStage.SWAPPED_NOT_VERIFIED]，尚未标记 COMPLETE：
     * 调用方必须在 Room 完整打开（含 schema 迁移）也成功后调用
     * [PlaintextToSqlCipherMigrator.confirmComplete]；若 Room 打开失败，应改用
     * [PlaintextToSqlCipherMigrator.abandonAfterOpenFailure] 物理回滚到明文 pre-image，
     * 而不是让一个未经 Room 验证的加密库永久无法回滚。
     */
    data class Success(
        val retainedPlaintextPreimage: File,
        val attempt: DatabaseMigrationAttempt
    ) : PlaintextToSqlCipherMigrationResult

    /** 必须停止启动并进入可见恢复入口。 */
    data class RecoveryRequired(
        val reason: DatabaseMigrationFailure
    ) : PlaintextToSqlCipherMigrationResult
}

/** 文件事务边界；生产实现必须使用同目录原子移动与跨进程锁。 */
interface DatabaseMigrationFileOperations {
    /** 串行化恢复、导出与换入整个过程。 */
    fun <T> withExclusiveLock(block: () -> T): T

    /** 在读取主库状态前恢复上一次未完成的换入。 */
    fun recoverIncompleteMigration(): DatabaseMigrationRecovery

    /** 创建唯一 attempt 并持久化 INITIALIZED journal。 */
    fun beginAttempt(): DatabaseMigrationAttempt

    /** 在 checkpoint/close 后创建且保留明文 pre-image。 */
    fun createPlaintextPreimage(attempt: DatabaseMigrationAttempt)

    /** 原子持久化迁移阶段。 */
    fun recordStage(attempt: DatabaseMigrationAttempt, stage: DatabaseMigrationStage)

    /** 将已验证的加密 temp 原子换入主数据库路径。 */
    fun swapEncryptedIntoMain(attempt: DatabaseMigrationAttempt)

    /** 从明文 pre-image 原子恢复主路径；不得删除或覆盖 pre-image。 */
    fun rollbackToPlaintextPreimage(attempt: DatabaseMigrationAttempt): Boolean

    /** 下一次冷启动完整验证加密主库后，清理已完成迁移遗留的明文副本。 */
    fun cleanupAfterVerifiedColdOpen(): Boolean = true
}

/** Android SQLite/SQLCipher API 的可替换边界。 */
interface PlaintextToSqlCipherMigrationBackend {
    /** 合并 WAL、切回 DELETE journal 并关闭所有明文句柄。 */
    fun checkpointAndClosePlaintext(database: File)

    /** 独立重开明文 pre-image，生成稳定快照并执行 integrity_check。 */
    fun inspectPlaintext(database: File): DatabaseMigrationVerification

    /** 使用 sqlcipher_export 从明文 pre-image 生成同目录加密 temp。 */
    fun exportPlaintextToEncrypted(
        plaintextDatabase: File,
        encryptedDatabase: File,
        passphrase: SqlCipherPassphrase,
        sourceSnapshot: DatabaseMigrationSnapshot
    ): DatabaseMigrationVerification

    /** 使用同一口令重开换入后的主数据库并重新验证。 */
    fun inspectEncrypted(
        database: File,
        passphrase: SqlCipherPassphrase
    ): DatabaseMigrationVerification
}

/**
 * 明文 SQLite 到 SQLCipher 的 fail-closed 原子迁移协调器。
 *
 * 此类不创建、覆盖或删除密钥信封，只在受控作用域内消费调用方提供的口令。
 */
class PlaintextToSqlCipherMigrator(
    private val files: DatabaseMigrationFileOperations,
    private val backend: PlaintextToSqlCipherMigrationBackend
) {
    /**
     * 执行恢复、导出、校验、换入和换入后重开验证。
     *
     * 本方法不取得 [passphrase] 所有权；调用方必须在结果返回后关闭它，后续 Room 接线若要
     * 继续打开数据库，则应在同一受控作用域完成打开后再清零。
     */
    fun migrate(passphrase: SqlCipherPassphrase): PlaintextToSqlCipherMigrationResult =
        try {
            files.withExclusiveLock { migrateWhileLocked(passphrase) }
        } catch (_: Throwable) {
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.CrashRecoveryFailed)
        }

    /** 调用方持有跨进程锁时执行完整状态转换。 */
    private fun migrateWhileLocked(
        passphrase: SqlCipherPassphrase
    ): PlaintextToSqlCipherMigrationResult {
        if (files.recoverIncompleteMigration() == DatabaseMigrationRecovery.Failed) {
            return PlaintextToSqlCipherMigrationResult.RecoveryRequired(
                DatabaseMigrationFailure.CrashRecoveryFailed
            )
        }
        val attempt = try {
            files.beginAttempt()
        } catch (_: Throwable) {
            return PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.PreimageFailed)
        }
        try {
            backend.checkpointAndClosePlaintext(attempt.mainDatabase)
        } catch (_: Throwable) {
            // checkpoint/close 未成功时尚未创建或换入任何副本，原主库仍在原位；
            // 此时尝试从不存在的 pre-image 回滚反而会掩盖真实失败原因。
            return failBeforeSwap(attempt, DatabaseMigrationFailure.SourcePreparationFailed)
        }
        try {
            files.createPlaintextPreimage(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
        } catch (_: Throwable) {
            return failBeforeSwap(attempt, DatabaseMigrationFailure.PreimageFailed)
        }
        val source = try {
            backend.inspectPlaintext(attempt.plaintextPreimage)
        } catch (_: Throwable) {
            return failBeforeSwap(attempt, DatabaseMigrationFailure.ValidationFailed)
        }
        if (!source.integrityOk || source.cipherIntegrityOk != null) {
            return failBeforeSwap(attempt, DatabaseMigrationFailure.ValidationFailed)
        }
        val exported = try {
            backend.exportPlaintextToEncrypted(
                plaintextDatabase = attempt.plaintextPreimage,
                encryptedDatabase = attempt.encryptedTemp,
                passphrase = passphrase,
                sourceSnapshot = source.snapshot
            )
        } catch (_: Throwable) {
            return failBeforeSwap(attempt, DatabaseMigrationFailure.ExportFailed)
        }
        if (!exported.matchesEncrypted(source.snapshot)) {
            return failBeforeSwap(attempt, DatabaseMigrationFailure.ValidationFailed)
        }
        try {
            files.recordStage(attempt, DatabaseMigrationStage.ENCRYPTED_TEMP_READY)
            files.recordStage(attempt, DatabaseMigrationStage.SWAP_PENDING)
            files.swapEncryptedIntoMain(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.SWAPPED_NOT_VERIFIED)
        } catch (_: Throwable) {
            return failAndRollback(attempt, DatabaseMigrationFailure.SwapFailed)
        }
        val reopened = try {
            backend.inspectEncrypted(attempt.mainDatabase, passphrase)
        } catch (_: Throwable) {
            return failAndRollback(attempt, DatabaseMigrationFailure.ReopenValidationFailed)
        }
        if (!reopened.matchesEncrypted(source.snapshot)) {
            return failAndRollback(attempt, DatabaseMigrationFailure.ReopenValidationFailed)
        }
        // 停在 SWAPPED_NOT_VERIFIED：调用方的 Room 打开/迁移还没有验证，过早标记 COMPLETE
        // 会让这条 journal 状态从此无法回滚，即使明文 pre-image 仍然完好。
        return PlaintextToSqlCipherMigrationResult.Success(attempt.plaintextPreimage, attempt)
    }

    /**
     * 调用方（Room 打开/迁移）也验证成功后才提交完成状态。
     *
     * 只有完成本方法后，下一次冷启动才会清理明文 pre-image；提交失败时保守返回 false，
     * 调用方应转入恢复而不是当作已完成处理。
     */
    fun confirmComplete(attempt: DatabaseMigrationAttempt): Boolean = try {
        files.withExclusiveLock { files.recordStage(attempt, DatabaseMigrationStage.COMPLETE) }
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * 调用方（Room 打开/迁移）失败时，journal 仍在 SWAPPED_NOT_VERIFIED，物理回滚到明文
     * pre-image 仍然合法；不得让一个未经完整验证的加密库锁死用户仍然完好的旧数据。
     */
    fun abandonAfterOpenFailure(attempt: DatabaseMigrationAttempt): Boolean = try {
        files.withExclusiveLock { files.rollbackToPlaintextPreimage(attempt) }
    } catch (_: Throwable) {
        false
    }

    /** 换入前主路径从未修改，只结束 attempt；不用 pre-image 覆盖仍可信的主库。 */
    private fun failBeforeSwap(
        attempt: DatabaseMigrationAttempt,
        failure: DatabaseMigrationFailure
    ): PlaintextToSqlCipherMigrationResult {
        runCatching { files.recordStage(attempt, DatabaseMigrationStage.ROLLED_BACK) }
        return PlaintextToSqlCipherMigrationResult.RecoveryRequired(failure)
    }

    /** 任一失败都尝试恢复 pre-image；恢复失败升级为更高优先级故障。 */
    private fun failAndRollback(
        attempt: DatabaseMigrationAttempt,
        failure: DatabaseMigrationFailure
    ): PlaintextToSqlCipherMigrationResult {
        val restored = runCatching { files.rollbackToPlaintextPreimage(attempt) }.getOrDefault(false)
        return PlaintextToSqlCipherMigrationResult.RecoveryRequired(
            if (restored) failure else DatabaseMigrationFailure.RollbackFailed
        )
    }

    /** 加密目标必须同时通过逻辑完整性、HMAC 完整性和全量内容指纹校验。 */
    private fun DatabaseMigrationVerification.matchesEncrypted(
        expected: DatabaseMigrationSnapshot
    ): Boolean = integrityOk && cipherIntegrityOk == true && snapshot == expected
}
