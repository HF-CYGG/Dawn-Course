package com.dawncourse.core.data.local.startup

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 基于同目录 pre-image、严格 journal 与原子 rename 的数据库迁移文件事务。
 *
 * 所有公开操作都应由 [withExclusiveLock] 包围；文件名只由受限 attempt UUID 派生，
 * journal 内容从不作为任意路径使用，避免损坏 journal 将操作范围引出数据库目录。
 */
class AtomicDatabaseMigrationFiles(
    private val databaseFile: File,
    private val attemptIdSource: () -> String = { UUID.randomUUID().toString() }
) : DatabaseMigrationFileOperations {
    private val databaseDirectory = requireNotNull(databaseFile.absoluteFile.parentFile) {
        "数据库必须具有父目录"
    }
    private val journalFile = File(databaseDirectory, "${databaseFile.name}.sqlcipher-migration.journal")
    private val lockFile = File(databaseDirectory, "${databaseFile.name}.sqlcipher-migration.lock")
    private val fileTransaction = AtomicDatabaseFileTransaction(databaseFile, lockFile)

    /** 先串行化同进程线程，再获取同目录 FileChannel 锁串行化其它进程。 */
    override fun <T> withExclusiveLock(block: () -> T): T = fileTransaction.withExclusiveLock(block)

    /** 未完成 journal 一律优先恢复明文 pre-image；损坏 journal 安全失败且不改主库。 */
    override fun recoverIncompleteMigration(): DatabaseMigrationRecovery {
        val journal = when (val readResult = readJournal()) {
            JournalReadResult.Missing -> return DatabaseMigrationRecovery.NoWork
            JournalReadResult.Invalid -> return DatabaseMigrationRecovery.Failed
            is JournalReadResult.Available -> readResult.journal
        }
        return when (journal.stage) {
            DatabaseMigrationStage.COMPLETE,
            DatabaseMigrationStage.ROLLED_BACK -> DatabaseMigrationRecovery.NoWork

            DatabaseMigrationStage.INITIALIZED -> {
                val attempt = attemptFor(journal.attemptId)
                recoverBeforeSwap(attempt)
            }

            DatabaseMigrationStage.PREIMAGE_READY,
            DatabaseMigrationStage.ENCRYPTED_TEMP_READY -> recoverBeforeSwap(attemptFor(journal.attemptId))

            DatabaseMigrationStage.SWAP_PENDING,
            DatabaseMigrationStage.SWAPPED_NOT_VERIFIED -> recoveryResult(attemptFor(journal.attemptId))
        }
    }

    /** 创建唯一 attempt 并先持久化 INITIALIZED，保证后续任一崩溃都有可解释状态。 */
    override fun beginAttempt(): DatabaseMigrationAttempt {
        val existing = readJournal()
        if (existing is JournalReadResult.Invalid) error("数据库迁移 journal 已损坏")
        if (existing is JournalReadResult.Available &&
            existing.journal.stage !in setOf(DatabaseMigrationStage.COMPLETE, DatabaseMigrationStage.ROLLED_BACK)
        ) {
            error("上一次数据库迁移尚未恢复")
        }
        val attemptId = attemptIdSource().lowercase()
        require(ATTEMPT_ID.matches(attemptId)) { "数据库迁移 attempt ID 格式无效" }
        val attempt = attemptFor(attemptId)
        require(!attempt.plaintextPreimage.exists() && !attempt.encryptedTemp.exists()) {
            "数据库迁移 attempt 文件已存在"
        }
        writeJournal(MigrationJournal(attemptId, DatabaseMigrationStage.INITIALIZED))
        return attempt
    }

    /** checkpoint 完成后复制并 fsync 明文主库；pre-image 永不覆盖。 */
    override fun createPlaintextPreimage(attempt: DatabaseMigrationAttempt) {
        validateAttempt(attempt)
        require(databaseFile.isFile) { "明文数据库不存在" }
        require(!attempt.plaintextPreimage.exists()) { "明文 pre-image 已存在" }
        fileTransaction.copyImmutable(databaseFile, attempt.plaintextPreimage)
    }

    /** 仅允许同一 attempt 的单向阶段转换，journal 写入本身使用 fsync + 原子移动。 */
    override fun recordStage(attempt: DatabaseMigrationAttempt, stage: DatabaseMigrationStage) {
        validateAttempt(attempt)
        val current = (readJournal() as? JournalReadResult.Available)?.journal
            ?: error("数据库迁移 journal 不存在或损坏")
        require(current.attemptId == attempt.id) { "journal attempt 不匹配" }
        require(isAllowedTransition(current.stage, stage)) {
            "非法数据库迁移阶段转换：${current.stage} -> $stage"
        }
        writeJournal(MigrationJournal(attempt.id, stage))
    }

    /** 先保留明文 sidecar，再要求加密 temp 无热 WAL/journal，最后执行同目录原子替换。 */
    override fun swapEncryptedIntoMain(attempt: DatabaseMigrationAttempt) {
        validateAttempt(attempt)
        require(attempt.plaintextPreimage.isFile) { "明文 pre-image 不存在" }
        require(attempt.encryptedTemp.isFile) { "加密 temp 不存在" }
        verifyNoHotEncryptedSidecars(attempt.encryptedTemp)
        archiveSidecars(databaseFile, attempt.plaintextPreimage, ".at-swap")
        atomicMove(attempt.encryptedTemp, databaseFile, replaceExisting = true)
        forceDirectoryBestEffort(databaseDirectory)
    }

    /** 保留失败加密主库，并从不可变 pre-image 的新副本原子恢复主路径。 */
    override fun rollbackToPlaintextPreimage(attempt: DatabaseMigrationAttempt): Boolean = runCatching {
        validateAttempt(attempt)
        require(attempt.plaintextPreimage.isFile) { "无法恢复：明文 pre-image 不存在" }
        val restoreTemp = File(databaseDirectory, "${databaseFile.name}.restore.${attempt.id}")
        Files.copy(
            attempt.plaintextPreimage.toPath(),
            restoreTemp.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
        )
        forceFile(restoreTemp)
        archiveSidecars(databaseFile, attempt.plaintextPreimage, ".rollback-${UUID.randomUUID()}")
        val failedEncrypted = File(databaseDirectory, "${databaseFile.name}.failed-encrypted.${attempt.id}")
        if (databaseFile.exists() && !failedEncrypted.exists()) {
            atomicMove(databaseFile, failedEncrypted, replaceExisting = false)
        }
        atomicMove(restoreTemp, databaseFile, replaceExisting = true)
        forceDirectoryBestEffort(databaseDirectory)
        recordStage(attempt, DatabaseMigrationStage.ROLLED_BACK)
    }.isSuccess

    /**
     * 只清理 COMPLETE attempt 的明文副本，并要求调用方已在本次冷启动重新验证主库。
     * 首次换入所在启动不得调用本方法，从而至少保留一个完整冷启动回滚窗口。
     */
    override fun cleanupAfterVerifiedColdOpen(): Boolean = runCatching {
        val journal = (readJournal() as? JournalReadResult.Available)?.journal
            ?: return@runCatching true
        if (journal.stage != DatabaseMigrationStage.COMPLETE) return@runCatching true
        val attempt = attemptFor(journal.attemptId)
        cleanupAttemptArtifacts(attempt, includeFailedEncrypted = false)
    }.getOrDefault(false)

    /**
     * 仅接受 ROLLED_BACK journal；调用资格必须由已提交的显式恢复/放弃事务证明。
     * 普通冷开不得调用，避免在用户尚未选择恢复路径时删除最后的明文回滚副本。
     */
    override fun cleanupRolledBackAfterExplicitRecoveryAndVerifiedColdOpen(): Boolean = runCatching {
        val journal = (readJournal() as? JournalReadResult.Available)?.journal
            ?: return@runCatching true
        if (journal.stage != DatabaseMigrationStage.ROLLED_BACK) return@runCatching true
        val attempt = attemptFor(journal.attemptId)
        cleanupAttemptArtifacts(attempt, includeFailedEncrypted = true)
    }.getOrDefault(false)

    /** 将严格 UUID 映射到固定同目录文件名。 */
    private fun attemptFor(attemptId: String): DatabaseMigrationAttempt {
        require(ATTEMPT_ID.matches(attemptId)) { "数据库迁移 attempt ID 格式无效" }
        return DatabaseMigrationAttempt(
            id = attemptId,
            mainDatabase = databaseFile,
            plaintextPreimage = File(databaseDirectory, "${databaseFile.name}.plaintext-preimage.$attemptId"),
            encryptedTemp = File(databaseDirectory, "${databaseFile.name}.encrypted-temp.$attemptId")
        )
    }

    /** 防止调用方构造越界路径或混用另一个 attempt。 */
    private fun validateAttempt(attempt: DatabaseMigrationAttempt) {
        require(attempt == attemptFor(attempt.id)) { "数据库迁移 attempt 路径不匹配" }
    }

    /** 删除当前 journal 精确指向的产物；失败加密库只会出现在 ROLLED_BACK 清理路径。 */
    private fun cleanupAttemptArtifacts(
        attempt: DatabaseMigrationAttempt,
        includeFailedEncrypted: Boolean,
    ): Boolean {
        val cleanupTargets = buildList {
            add(attempt.plaintextPreimage)
            add(File(attempt.plaintextPreimage.path + ".copying"))
            SIDECAR_SUFFIXES.forEach { suffix ->
                add(File(attempt.plaintextPreimage.path + suffix + ".at-swap"))
                add(File(attempt.plaintextPreimage.path + suffix + ".closed"))
            }
            if (includeFailedEncrypted) {
                val failedEncrypted = File(databaseDirectory, "${databaseFile.name}.failed-encrypted.${attempt.id}")
                add(failedEncrypted)
                SIDECAR_SUFFIXES.forEach { suffix -> add(File(failedEncrypted.path + suffix)) }
                val rollbackSidecarPrefixes = SIDECAR_SUFFIXES.map { suffix ->
                    "${attempt.plaintextPreimage.name}$suffix.rollback-"
                }
                databaseDirectory.listFiles()
                    ?.filter { artifact ->
                        rollbackSidecarPrefixes.any(artifact.name::startsWith)
                    }
                    ?.let(::addAll)
            }
        }
        cleanupTargets.forEach(::deletePrivateArtifact)
        deletePrivateArtifact(journalFile)
        forceDirectoryBestEffort(databaseDirectory)
        return true
    }

    /** 恢复函数只返回稳定结果，不让文件异常逃逸到启动入口。 */
    private fun recoveryResult(attempt: DatabaseMigrationAttempt): DatabaseMigrationRecovery =
        if (rollbackToPlaintextPreimage(attempt)) {
            DatabaseMigrationRecovery.Recovered
        } else {
            DatabaseMigrationRecovery.Failed
        }

    /** 换入前主路径从未被修改：仍存在时只结束 attempt，不用副本覆盖可信原件。 */
    private fun recoverBeforeSwap(attempt: DatabaseMigrationAttempt): DatabaseMigrationRecovery {
        if (attempt.mainDatabase.exists()) {
            return runCatching {
                // INITIALIZED 崩溃可能留下尚未 rename 的明文复制文件；原主库仍可信时立即清理。
                deletePrivateArtifact(File(attempt.plaintextPreimage.path + ".copying"))
                deletePrivateArtifact(attempt.encryptedTemp)
                recordStage(attempt, DatabaseMigrationStage.ROLLED_BACK)
            }.fold(
                onSuccess = { DatabaseMigrationRecovery.Recovered },
                onFailure = { DatabaseMigrationRecovery.Failed }
            )
        }
        return if (attempt.plaintextPreimage.exists()) {
            recoveryResult(attempt)
        } else {
            DatabaseMigrationRecovery.Failed
        }
    }

    /** 加密 temp 的 WAL/journal 非空表示仍有未合并数据，禁止换入；其它 sidecar 保留归档。 */
    private fun verifyNoHotEncryptedSidecars(encryptedTemp: File) {
        listOf("-wal", "-journal").forEach { suffix ->
            val sidecar = File(encryptedTemp.path + suffix)
            require(!sidecar.exists() || sidecar.length() == 0L) { "加密 temp 存在未合并 sidecar" }
        }
        archiveSidecars(encryptedTemp, encryptedTemp, ".closed")
    }

    /** 将旧库 sidecar 原子移到 pre-image 命名空间，避免污染新换入的加密主库。 */
    private fun archiveSidecars(sourceBase: File, archiveBase: File, marker: String) {
        SIDECAR_SUFFIXES.forEach { suffix ->
            val source = File(sourceBase.path + suffix)
            if (source.exists()) {
                val archive = File(archiveBase.path + suffix + marker)
                require(!archive.exists()) { "sidecar 归档文件已存在" }
                atomicMove(source, archive, replaceExisting = false)
            }
        }
    }

    /** 严格解析固定三行 journal；未知字段、路径、超长输入或非法阶段全部拒绝。 */
    private fun readJournal(): JournalReadResult {
        if (!journalFile.exists()) return JournalReadResult.Missing
        return try {
            if (!journalFile.isFile || journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
                return JournalReadResult.Invalid
            }
            val lines = journalFile.readLines(Charsets.UTF_8)
            if (lines.size != 3 || lines[0] != JOURNAL_MAGIC) return JournalReadResult.Invalid
            val attemptId = lines[1].removePrefix(ATTEMPT_PREFIX)
            val stageName = lines[2].removePrefix(STAGE_PREFIX)
            if (lines[1] == attemptId || lines[2] == stageName || !ATTEMPT_ID.matches(attemptId)) {
                return JournalReadResult.Invalid
            }
            val stage = runCatching { DatabaseMigrationStage.valueOf(stageName) }.getOrNull()
                ?: return JournalReadResult.Invalid
            JournalReadResult.Available(MigrationJournal(attemptId, stage))
        } catch (_: Throwable) {
            JournalReadResult.Invalid
        }
    }

    /** 使用同目录临时文件、fsync 与 ATOMIC_MOVE 更新 journal。 */
    private fun writeJournal(journal: MigrationJournal) {
        val encoded = buildString {
            appendLine(JOURNAL_MAGIC)
            append(ATTEMPT_PREFIX)
            appendLine(journal.attemptId)
            append(STAGE_PREFIX)
            append(journal.stage.name)
        }.toByteArray(Charsets.UTF_8)
        fileTransaction.writeAtomically(journalFile, encoded)
    }

    /** 禁止退化为非原子替换；不支持 ATOMIC_MOVE 的文件系统直接失败并由上层恢复。 */
    private fun atomicMove(source: File, target: File, replaceExisting: Boolean) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), target.toPath(), *options)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("当前文件系统不支持数据库原子换入", unsupported)
        }
    }

    /** 强制文件内容与元数据落盘。 */
    private fun forceFile(file: File) {
        FileOutputStream(file, true).channel.use { channel -> channel.force(true) }
    }

    /** Android/Linux 支持目录 fsync；不支持目录 channel 的 JVM 平台仅跳过这一额外屏障。 */
    private fun forceDirectoryBestEffort(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    /** 应用私有且已被 D2D 排除的文件只按精确路径删除；删除失败一律 fail closed。 */
    private fun deletePrivateArtifact(file: File) {
        if (file.exists()) require(file.delete()) { "无法清理数据库迁移遗留文件" }
    }

    /** journal 的内存表示，不包含任意路径。 */
    private data class MigrationJournal(
        val attemptId: String,
        val stage: DatabaseMigrationStage
    )

    /** 严格 journal 读取结果。 */
    private sealed interface JournalReadResult {
        data object Missing : JournalReadResult
        data object Invalid : JournalReadResult
        data class Available(val journal: MigrationJournal) : JournalReadResult
    }

    private companion object {
        /** journal 格式魔数。 */
        const val JOURNAL_MAGIC = "DAWN_SQLCIPHER_MIGRATION_V1"

        /** attempt 行前缀。 */
        const val ATTEMPT_PREFIX = "attempt="

        /** stage 行前缀。 */
        const val STAGE_PREFIX = "stage="

        /** 拒绝异常大 journal。 */
        const val MAX_JOURNAL_BYTES = 512L

        /** 固定 UUID 文本格式，禁止路径分隔符。 */
        val ATTEMPT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** SQLite 可能生成的全部同名 sidecar。 */
        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}

/** 允许的迁移阶段只前进，回滚可从任何未完成阶段进入。 */
private fun isAllowedTransition(
    current: DatabaseMigrationStage,
    next: DatabaseMigrationStage
): Boolean {
    if (next == DatabaseMigrationStage.ROLLED_BACK && current != DatabaseMigrationStage.COMPLETE) return true
    return when (current) {
        DatabaseMigrationStage.INITIALIZED -> next == DatabaseMigrationStage.PREIMAGE_READY
        DatabaseMigrationStage.PREIMAGE_READY -> next == DatabaseMigrationStage.ENCRYPTED_TEMP_READY
        DatabaseMigrationStage.ENCRYPTED_TEMP_READY -> next == DatabaseMigrationStage.SWAP_PENDING
        DatabaseMigrationStage.SWAP_PENDING -> next == DatabaseMigrationStage.SWAPPED_NOT_VERIFIED
        DatabaseMigrationStage.SWAPPED_NOT_VERIFIED -> next == DatabaseMigrationStage.COMPLETE
        DatabaseMigrationStage.COMPLETE,
        DatabaseMigrationStage.ROLLED_BACK -> false
    }
}
