package com.dawncourse.core.data.local.startup

import java.io.File

/** v1 passphrase 数据库切换到 v2 raw key 的持久阶段。 */
enum class SqlCipherRekeyStage {
    INITIALIZED,
    LEGACY_PREIMAGE_READY,
    RAW_ENVELOPE_STAGED,
    RAW_TEMP_VERIFIED,
    SWAP_PENDING,
    RAW_SWAPPED_NOT_ROOM_VERIFIED,
    ROOM_REOPEN_VERIFIED,
    ENVELOPE_COMMIT_PENDING,
    /** 任何物理反向替换前先持久化；崩溃后只能继续回滚，不能误判为 raw 前向提交。 */
    ROLLBACK_PENDING,
    COMPLETE,
    ROLLED_BACK
}

/** 固定 attempt 对应的数据库与信封产物。 */
data class SqlCipherRekeyAttempt(
    val id: String,
    val mainDatabase: File,
    val legacyDatabasePreimage: File,
    val rawDatabaseTemp: File,
    val legacyEnvelopePreimage: File,
    val stagedRawEnvelope: File
)

/** 冷启动时对未完成 rekey 的收敛结果。 */
sealed interface SqlCipherRekeyRecovery {
    data object NoWork : SqlCipherRekeyRecovery
    data object RecoveredToLegacy : SqlCipherRekeyRecovery
    data object RecoveredToRaw : SqlCipherRekeyRecovery
    data object Failed : SqlCipherRekeyRecovery
}

/** rekey 文件事务边界；实现必须联合提交数据库和密钥信封。 */
interface SqlCipherRekeyFileOperations {
    fun <T> withExclusiveLock(block: () -> T): T
    fun recoverIncompleteRekey(): SqlCipherRekeyRecovery
    fun beginAttempt(): SqlCipherRekeyAttempt
    fun createLegacyPreimages(attempt: SqlCipherRekeyAttempt)
    fun stageRawEnvelope(attempt: SqlCipherRekeyAttempt): DatabaseKeyMaterial.RawKeyLiteral
    fun recordStage(attempt: SqlCipherRekeyAttempt, stage: SqlCipherRekeyStage)
    fun swapRawIntoMain(attempt: SqlCipherRekeyAttempt)
    fun commitRawEnvelope(attempt: SqlCipherRekeyAttempt)
    fun rollbackToLegacy(attempt: SqlCipherRekeyAttempt): Boolean

    /** 显式恢复/放弃已安装全新 v2 库后，永久退休旧 rekey journal 与 pre-image。 */
    fun retireAfterExplicitRecovery(): Boolean

    /** COMPLETE 只在后续成功冷开后清理，保留完整回滚窗口。 */
    fun cleanupAfterVerifiedColdOpen(): Boolean = true
}

/** 独立 SQLCipher rekey 后端；不得通过明文 ATTACH/export 路径实现。 */
interface SqlCipherRekeyBackend {
    fun checkpointAndCloseLegacy(
        database: File,
        legacy: DatabaseKeyMaterial.LegacyPassphrase
    )

    fun rekeyCopyAndVerify(
        legacyDatabase: File,
        rawDatabase: File,
        legacy: DatabaseKeyMaterial.LegacyPassphrase,
        raw: DatabaseKeyMaterial.RawKeyLiteral
    )
}

/** rekey 的稳定结果。 */
sealed interface SqlCipherRekeyResult {
    data class Success(
        val attempt: SqlCipherRekeyAttempt,
        val rawKeyMaterial: DatabaseKeyMaterial.RawKeyLiteral
    ) : SqlCipherRekeyResult

    data object RecoveryRequired : SqlCipherRekeyResult
}

/**
 * v1 -> v2 的 fail-closed 协调器。
 *
 * 成功返回时数据库已换为 raw，但活动信封仍是 v1。只有调用方用 raw 完成 Room 首次打开后，
 * [confirmRoomVerifiedAndCommit] 才把 v2 信封提交到活动路径。
 */
class SqlCipherRekeyMigrator(
    private val files: SqlCipherRekeyFileOperations,
    private val backend: SqlCipherRekeyBackend
) {
    fun rekey(legacy: DatabaseKeyMaterial.LegacyPassphrase): SqlCipherRekeyResult = try {
        files.withExclusiveLock { rekeyWhileLocked(legacy) }
    } catch (_: Throwable) {
        SqlCipherRekeyResult.RecoveryRequired
    }

    private fun rekeyWhileLocked(
        legacy: DatabaseKeyMaterial.LegacyPassphrase
    ): SqlCipherRekeyResult {
        when (files.recoverIncompleteRekey()) {
            SqlCipherRekeyRecovery.Failed,
            SqlCipherRekeyRecovery.RecoveredToRaw -> return SqlCipherRekeyResult.RecoveryRequired

            SqlCipherRekeyRecovery.NoWork,
            SqlCipherRekeyRecovery.RecoveredToLegacy -> Unit
        }
        val attempt = runCatching(files::beginAttempt).getOrElse {
            return SqlCipherRekeyResult.RecoveryRequired
        }
        var raw: DatabaseKeyMaterial.RawKeyLiteral? = null
        return try {
            backend.checkpointAndCloseLegacy(attempt.mainDatabase, legacy)
            files.createLegacyPreimages(attempt)
            files.recordStage(attempt, SqlCipherRekeyStage.LEGACY_PREIMAGE_READY)
            raw = files.stageRawEnvelope(attempt)
            files.recordStage(attempt, SqlCipherRekeyStage.RAW_ENVELOPE_STAGED)
            backend.rekeyCopyAndVerify(
                legacyDatabase = attempt.legacyDatabasePreimage,
                rawDatabase = attempt.rawDatabaseTemp,
                legacy = legacy,
                raw = raw
            )
            files.recordStage(attempt, SqlCipherRekeyStage.RAW_TEMP_VERIFIED)
            files.recordStage(attempt, SqlCipherRekeyStage.SWAP_PENDING)
            files.swapRawIntoMain(attempt)
            files.recordStage(attempt, SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED)
            SqlCipherRekeyResult.Success(attempt, raw).also { raw = null }
        } catch (_: Throwable) {
            files.rollbackToLegacy(attempt)
            SqlCipherRekeyResult.RecoveryRequired
        } finally {
            raw?.close()
        }
    }

    /** Room 已使用 raw key 完整打开后，才联合提交 v2 信封与 COMPLETE journal。 */
    fun confirmRoomVerifiedAndCommit(attempt: SqlCipherRekeyAttempt): Boolean = try {
        files.withExclusiveLock {
            files.recordStage(attempt, SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED)
            files.recordStage(attempt, SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING)
            files.commitRawEnvelope(attempt)
            files.recordStage(attempt, SqlCipherRekeyStage.COMPLETE)
        }
        true
    } catch (_: Throwable) {
        false
    }

    fun rollback(attempt: SqlCipherRekeyAttempt): Boolean = try {
        files.withExclusiveLock { files.rollbackToLegacy(attempt) }
    } catch (_: Throwable) {
        false
    }
}
