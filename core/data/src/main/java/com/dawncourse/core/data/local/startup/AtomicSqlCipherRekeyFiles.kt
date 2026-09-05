package com.dawncourse.core.data.local.startup

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * v1/v2 密钥信封与数据库文件的联合 rekey 事务。
 *
 * 数据库与信封分别在自己的目录内用 fsync + 原子替换；外层启动锁和本类 rekey 锁保证
 * 两者之间的可恢复顺序。任一中断最终只收敛到 v1+legacy 库或 v2+raw 库。
 */
class AtomicSqlCipherRekeyFiles(
    private val databaseFile: File,
    private val activeEnvelopeFile: File,
    private val keyProvider: KeyEncryptionKeyProvider = AndroidKeystoreAesGcmKeyProvider(),
    private val randomByteSource: SecureRandomByteSource = JcaSecureRandomByteSource(),
    private val attemptIdSource: () -> String = { UUID.randomUUID().toString() },
    private val atomicByteStoreFactory: (File) -> AtomicByteStore = ::AndroidAtomicByteStore,
) : SqlCipherRekeyFileOperations {
    private val databaseDirectory = requireNotNull(databaseFile.absoluteFile.parentFile)
    private val envelopeDirectory = requireNotNull(activeEnvelopeFile.absoluteFile.parentFile)
    private val journalFile = File(databaseDirectory, "${databaseFile.name}.sqlcipher-rekey.journal")
    private val databaseTransaction = AtomicDatabaseFileTransaction(
        databaseFile,
        File(databaseDirectory, "${databaseFile.name}.sqlcipher-rekey.lock")
    )
    private val envelopeTransaction = AtomicDatabaseFileTransaction(
        activeEnvelopeFile,
        File(envelopeDirectory, "${activeEnvelopeFile.name}.rekey.lock")
    )

    override fun <T> withExclusiveLock(block: () -> T): T =
        databaseTransaction.withExclusiveLock(block)

    override fun recoverIncompleteRekey(): SqlCipherRekeyRecovery {
        val journal = when (val read = readJournal()) {
            JournalRead.Missing -> {
                return when {
                    discardIsolatedJournalTemp() -> SqlCipherRekeyRecovery.NoWork
                    hasOrphanArtifacts() -> SqlCipherRekeyRecovery.Failed
                    else -> SqlCipherRekeyRecovery.NoWork
                }
            }
            JournalRead.Invalid -> return SqlCipherRekeyRecovery.Failed
            is JournalRead.Available -> read.journal
        }
        val attempt = attemptFor(journal.attemptId)
        return when (journal.stage) {
            SqlCipherRekeyStage.COMPLETE,
            SqlCipherRekeyStage.ROLLED_BACK -> SqlCipherRekeyRecovery.NoWork

            SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING -> {
                if (activeEnvelopeFormatVersion() == RAW_ENVELOPE_VERSION && databaseFile.isFile) {
                    runCatching { recordStage(attempt, SqlCipherRekeyStage.COMPLETE) }.fold(
                        onSuccess = { SqlCipherRekeyRecovery.RecoveredToRaw },
                        onFailure = { SqlCipherRekeyRecovery.Failed }
                    )
                } else {
                    rollbackRecovery(attempt)
                }
            }

            SqlCipherRekeyStage.INITIALIZED,
            SqlCipherRekeyStage.LEGACY_PREIMAGE_READY,
            SqlCipherRekeyStage.RAW_ENVELOPE_STAGED,
            SqlCipherRekeyStage.RAW_TEMP_VERIFIED,
            SqlCipherRekeyStage.SWAP_PENDING,
            SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED,
            SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED -> rollbackRecovery(attempt)

            SqlCipherRekeyStage.ROLLBACK_PENDING -> rollbackRecovery(attempt)
        }
    }

    override fun beginAttempt(): SqlCipherRekeyAttempt {
        val existing = readJournal()
        if (existing is JournalRead.Invalid) error("rekey journal 已损坏")
        if (existing is JournalRead.Available &&
            existing.journal.stage !in setOf(SqlCipherRekeyStage.COMPLETE, SqlCipherRekeyStage.ROLLED_BACK)
        ) {
            error("上一次 rekey 尚未恢复")
        }
        if (existing is JournalRead.Available && existing.journal.stage == SqlCipherRekeyStage.ROLLED_BACK) {
            cleanupAttemptArtifacts(attemptFor(existing.journal.attemptId))
        }
        require(databaseFile.isFile) { "legacy 数据库不存在" }
        require(activeEnvelopeFile.isFile && activeEnvelopeFormatVersion() == LEGACY_ENVELOPE_VERSION) {
            "活动信封不是 v1 legacy 模式"
        }
        val id = attemptIdSource().lowercase()
        require(ATTEMPT_ID.matches(id)) { "rekey attempt ID 格式无效" }
        val attempt = attemptFor(id)
        require(attempt.artifacts().none(File::exists)) { "rekey attempt 产物已存在" }
        writeJournal(RekeyJournal(id, SqlCipherRekeyStage.INITIALIZED))
        return attempt
    }

    override fun createLegacyPreimages(attempt: SqlCipherRekeyAttempt) {
        validateAttempt(attempt)
        databaseTransaction.copyImmutable(databaseFile, attempt.legacyDatabasePreimage)
        envelopeTransaction.copyImmutable(activeEnvelopeFile, attempt.legacyEnvelopePreimage)
        // raw 工作副本仍由共用文件事务创建；rekey 后端只在副本内调用 SQLCipher rekey。
        databaseTransaction.copyImmutable(attempt.legacyDatabasePreimage, attempt.rawDatabaseTemp)
    }

    override fun stageRawEnvelope(
        attempt: SqlCipherRekeyAttempt
    ): DatabaseKeyMaterial.RawKeyLiteral {
        validateAttempt(attempt)
        val result = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStoreFactory(attempt.stagedRawEnvelope),
            keyProvider = keyProvider,
            randomByteSource = randomByteSource
        ).createNewWithExistingKey()
        val material = (result as? NewPassphraseResult.Available)?.keyMaterial
            ?: error("无法创建 staged v2 信封")
        return material as? DatabaseKeyMaterial.RawKeyLiteral
            ?: run {
                material.close()
                error("staged 信封未返回 raw key")
            }
    }

    override fun recordStage(attempt: SqlCipherRekeyAttempt, stage: SqlCipherRekeyStage) {
        validateAttempt(attempt)
        val current = (readJournal() as? JournalRead.Available)?.journal
            ?: error("rekey journal 不存在或损坏")
        require(current.attemptId == attempt.id) { "rekey journal attempt 不匹配" }
        require(isAllowedTransition(current.stage, stage)) {
            "非法 rekey 阶段转换：${current.stage} -> $stage"
        }
        writeJournal(RekeyJournal(attempt.id, stage))
    }

    override fun swapRawIntoMain(attempt: SqlCipherRekeyAttempt) {
        validateAttempt(attempt)
        require(attempt.legacyDatabasePreimage.isFile) { "legacy 数据库 pre-image 不存在" }
        databaseTransaction.requireNoHotSidecars(attempt.rawDatabaseTemp)
        databaseTransaction.archiveSidecars(databaseFile, attempt.legacyDatabasePreimage, ".at-rekey")
        databaseTransaction.atomicReplace(attempt.rawDatabaseTemp, databaseFile)
    }

    override fun commitRawEnvelope(attempt: SqlCipherRekeyAttempt) {
        validateAttempt(attempt)
        require(attempt.stagedRawEnvelope.isFile) { "staged v2 信封不存在" }
        require(activeEnvelopeFormatVersion() == LEGACY_ENVELOPE_VERSION) { "活动信封已意外变化" }
        val staged = attempt.stagedRawEnvelope.readBytes()
        try {
            require(envelopeFormatVersion(staged) == RAW_ENVELOPE_VERSION) { "staged 信封不是 v2" }
            envelopeTransaction.writeAtomically(activeEnvelopeFile, staged)
        } finally {
            staged.fill(0)
        }
    }

    override fun rollbackToLegacy(attempt: SqlCipherRekeyAttempt): Boolean = runCatching {
        validateAttempt(attempt)
        val current = (readJournal() as? JournalRead.Available)?.journal
            ?: error("rekey journal 不存在或损坏")
        require(current.attemptId == attempt.id) { "rekey journal attempt 不匹配" }
        if (current.stage == SqlCipherRekeyStage.ROLLED_BACK) return@runCatching
        require(current.stage != SqlCipherRekeyStage.COMPLETE) { "已提交的 raw rekey 不得反向回滚" }

        // INITIALIZED 尚未创建任何 pre-image，也未触碰主库/信封。验证完整 v1 pair 后可直接
        // 终止 attempt；不写 ROLLBACK_PENDING，因而不存在重启后丢失“无需物理恢复”语义。
        if (current.stage == SqlCipherRekeyStage.INITIALIZED) {
            require(databaseFile.isFile) { "INITIALIZED 主数据库不存在" }
            require(activeEnvelopeFormatVersion() == LEGACY_ENVELOPE_VERSION) {
                "INITIALIZED 活动信封不是 v1"
            }
            // 阶段尚未推进前 createLegacyPreimages 可能只完成了部分原子副本；此时 main 与
            // active v1 envelope 从未被触碰，attempt-scoped 文件只是可丢弃的未提交工作。
            recordStage(attempt, SqlCipherRekeyStage.ROLLED_BACK)
            return@runCatching
        }

        // 必须先把回滚方向写入 journal，再触碰 DB/envelope。否则 v2 envelope 已提交时若
        // 只恢复了 legacy DB 就崩溃，下一启动会把不匹配的组合误判为 raw pair。
        val stageBeforeRollback = current.stage
        if (stageBeforeRollback != SqlCipherRekeyStage.ROLLBACK_PENDING) {
            recordStage(attempt, SqlCipherRekeyStage.ROLLBACK_PENDING)
        }
        if (attempt.legacyDatabasePreimage.isFile) {
            restoreImmutable(
                transaction = databaseTransaction,
                preimage = attempt.legacyDatabasePreimage,
                target = databaseFile,
                restoreTemp = File(databaseDirectory, "${databaseFile.name}.legacy-restore.${attempt.id}")
            )
        } else {
            error("legacy 数据库 pre-image 缺失")
        }
        if (attempt.legacyEnvelopePreimage.isFile) {
            val legacyEnvelope = attempt.legacyEnvelopePreimage.readBytes()
            try {
                require(envelopeFormatVersion(legacyEnvelope) == LEGACY_ENVELOPE_VERSION) {
                    "legacy 信封 pre-image 无效"
                }
                envelopeTransaction.writeAtomically(activeEnvelopeFile, legacyEnvelope)
            } finally {
                legacyEnvelope.fill(0)
            }
        } else {
            error("legacy 信封 pre-image 缺失")
        }
        recordStage(attempt, SqlCipherRekeyStage.ROLLED_BACK)
    }.isSuccess

    override fun retireAfterExplicitRecovery(): Boolean = runCatching {
        // Bootstrap 已经换入并验证了全新恢复库；只有确认新 pair 存在时才允许丢弃旧责任。
        require(databaseFile.isFile) { "显式恢复后的主数据库不存在" }
        require(activeEnvelopeFormatVersion() == RAW_ENVELOPE_VERSION) {
            "显式恢复后的活动信封不是 v2"
        }
        // 用户已经明确恢复/放弃，旧 journal 是否可读不再影响选择。严格匹配固定协议文件名
        // 枚举退休，绝不删除 main、活动 envelope 或目录内其它文件。
        cleanupAllRetiredRekeyArtifacts()
        true
    }.getOrDefault(false)

    override fun cleanupAfterVerifiedColdOpen(): Boolean = runCatching {
        val journal = (readJournal() as? JournalRead.Available)?.journal
            ?: return@runCatching true
        if (journal.stage != SqlCipherRekeyStage.COMPLETE) return@runCatching true
        cleanupAttemptArtifacts(attemptFor(journal.attemptId))
        true
    }.getOrDefault(false)

    private fun rollbackRecovery(attempt: SqlCipherRekeyAttempt): SqlCipherRekeyRecovery =
        if (rollbackToLegacy(attempt)) {
            SqlCipherRekeyRecovery.RecoveredToLegacy
        } else {
            SqlCipherRekeyRecovery.Failed
        }

    private fun restoreImmutable(
        transaction: AtomicDatabaseFileTransaction,
        preimage: File,
        target: File,
        restoreTemp: File
    ) {
        transaction.deletePrivateArtifact(restoreTemp)
        transaction.deletePrivateArtifact(File(restoreTemp.path + ".copying"))
        transaction.copyImmutable(preimage, restoreTemp)
        transaction.archiveSidecars(target, preimage, ".failed-raw-${UUID.randomUUID()}")
        transaction.atomicReplace(restoreTemp, target)
    }

    private fun attemptFor(id: String): SqlCipherRekeyAttempt {
        require(ATTEMPT_ID.matches(id)) { "rekey attempt ID 格式无效" }
        return SqlCipherRekeyAttempt(
            id = id,
            mainDatabase = databaseFile,
            legacyDatabasePreimage = File(databaseDirectory, "${databaseFile.name}.legacy-preimage.$id"),
            rawDatabaseTemp = File(databaseDirectory, "${databaseFile.name}.raw-temp.$id"),
            legacyEnvelopePreimage = File(envelopeDirectory, "${activeEnvelopeFile.name}.legacy-preimage.$id"),
            stagedRawEnvelope = File(envelopeDirectory, "${activeEnvelopeFile.name}.raw-staged.$id")
        )
    }

    private fun validateAttempt(attempt: SqlCipherRekeyAttempt) {
        require(attempt == attemptFor(attempt.id)) { "rekey attempt 路径不匹配" }
    }

    private fun SqlCipherRekeyAttempt.artifacts(): List<File> = listOf(
        legacyDatabasePreimage,
        rawDatabaseTemp,
        legacyEnvelopePreimage,
        stagedRawEnvelope
    )

    private fun cleanupAttemptArtifacts(attempt: SqlCipherRekeyAttempt) {
        validateAttempt(attempt)
        val databaseArtifacts = databaseDirectory.listFiles().orEmpty().filter { file ->
            SqlCipherRekeyArtifactPolicy.isDatabaseArtifact(
                databaseFileName = databaseFile.name,
                artifactName = file.name,
                attemptId = attempt.id,
            )
        }
        val envelopeArtifacts = envelopeDirectory.listFiles().orEmpty().filter { file ->
            SqlCipherRekeyArtifactPolicy.isEnvelopeArtifact(
                envelopeFileName = activeEnvelopeFile.name,
                artifactName = file.name,
                attemptId = attempt.id,
            )
        }
        (databaseArtifacts + envelopeArtifacts + listOf(File(journalFile.path + ".tmp"), journalFile))
            .distinctBy(File::getAbsolutePath)
            .forEach(databaseTransaction::deletePrivateArtifact)
        databaseTransaction.forceDirectoryBestEffort()
        envelopeTransaction.forceDirectoryBestEffort()
    }

    private fun hasOrphanArtifacts(): Boolean {
        return databaseDirectory.listFiles().orEmpty().any { file ->
            SqlCipherRekeyArtifactPolicy.isJournalArtifact(databaseFile.name, file.name) ||
                SqlCipherRekeyArtifactPolicy.isDatabaseArtifact(databaseFile.name, file.name)
        } || envelopeDirectory.listFiles().orEmpty().any { file ->
            SqlCipherRekeyArtifactPolicy.isEnvelopeArtifact(activeEnvelopeFile.name, file.name)
        }
    }

    /** 仅清理一次 journal 原子写入在 beginAttempt 之前留下的孤立工作文件。 */
    private fun discardIsolatedJournalTemp(): Boolean = runCatching {
        val temporary = File(journalFile.path + ".tmp")
        if (!temporary.exists()) return@runCatching false
        val otherArtifacts = databaseDirectory.listFiles().orEmpty().any { file ->
            file != temporary && SqlCipherRekeyArtifactPolicy.isDatabaseArtifact(
                databaseFile.name,
                file.name,
            )
        } || envelopeDirectory.listFiles().orEmpty().any { file ->
            SqlCipherRekeyArtifactPolicy.isEnvelopeArtifact(activeEnvelopeFile.name, file.name)
        }
        if (otherArtifacts) return@runCatching false
        require(databaseFile.isFile && activeEnvelopeFormatVersion() == LEGACY_ENVELOPE_VERSION)
        databaseTransaction.deletePrivateArtifact(temporary)
        databaseTransaction.forceDirectoryBestEffort()
        true
    }.getOrDefault(false)

    /** 显式恢复后即使 journal 损坏/缺失，也可按严格文件协议永久退休旧产物。 */
    private fun cleanupAllRetiredRekeyArtifacts() {
        val databaseEntries = databaseDirectory.listFiles() ?: error("无法枚举数据库目录")
        val envelopeEntries = envelopeDirectory.listFiles() ?: error("无法枚举信封目录")
        databaseEntries.filter { file ->
            SqlCipherRekeyArtifactPolicy.isDatabaseArtifact(databaseFile.name, file.name)
        }.forEach(databaseTransaction::deletePrivateArtifact)
        envelopeEntries.filter { file ->
            SqlCipherRekeyArtifactPolicy.isEnvelopeArtifact(activeEnvelopeFile.name, file.name)
        }.forEach(envelopeTransaction::deletePrivateArtifact)
        // journal 最后删除：此前任一步失败时下次仍能识别存在未退休责任并重试。
        listOf(File(journalFile.path + ".tmp"), journalFile)
            .forEach(databaseTransaction::deletePrivateArtifact)
        databaseTransaction.forceDirectoryBestEffort()
        envelopeTransaction.forceDirectoryBestEffort()
    }

    private fun readJournal(): JournalRead {
        if (!journalFile.exists()) return JournalRead.Missing
        return try {
            if (!journalFile.isFile || journalFile.length() !in 1..MAX_JOURNAL_BYTES) return JournalRead.Invalid
            val lines = journalFile.readLines(Charsets.UTF_8)
            if (lines.size != 3 || lines[0] != JOURNAL_MAGIC) return JournalRead.Invalid
            val id = lines[1].removePrefix(ATTEMPT_PREFIX)
            val stageName = lines[2].removePrefix(STAGE_PREFIX)
            if (lines[1] == id || lines[2] == stageName || !ATTEMPT_ID.matches(id)) return JournalRead.Invalid
            val stage = runCatching { SqlCipherRekeyStage.valueOf(stageName) }.getOrNull()
                ?: return JournalRead.Invalid
            JournalRead.Available(RekeyJournal(id, stage))
        } catch (_: Throwable) {
            JournalRead.Invalid
        }
    }

    private fun writeJournal(journal: RekeyJournal) {
        val bytes = buildString {
            appendLine(JOURNAL_MAGIC)
            append(ATTEMPT_PREFIX)
            appendLine(journal.attemptId)
            append(STAGE_PREFIX)
            append(journal.stage.name)
        }.toByteArray(Charsets.UTF_8)
        databaseTransaction.writeAtomically(journalFile, bytes)
    }

    private fun activeEnvelopeFormatVersion(): Int? = runCatching {
        val bytes = activeEnvelopeFile.readBytes()
        try {
            envelopeFormatVersion(bytes)
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    private fun envelopeFormatVersion(bytes: ByteArray): Int? {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 4).contentEquals(ENVELOPE_MAGIC)) return null
        return ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private data class RekeyJournal(val attemptId: String, val stage: SqlCipherRekeyStage)

    private sealed interface JournalRead {
        data object Missing : JournalRead
        data object Invalid : JournalRead
        data class Available(val journal: RekeyJournal) : JournalRead
    }

    private companion object {
        const val JOURNAL_MAGIC = "DAWN_SQLCIPHER_REKEY_V1"
        const val ATTEMPT_PREFIX = "attempt="
        const val STAGE_PREFIX = "stage="
        const val MAX_JOURNAL_BYTES = 512L
        const val LEGACY_ENVELOPE_VERSION = 1
        const val RAW_ENVELOPE_VERSION = 2
        val ATTEMPT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val ENVELOPE_MAGIC = byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    }
}

/** rekey 文件协议的唯一严格命名策略；扫描、清理和显式退休必须共享它。 */
internal object SqlCipherRekeyArtifactPolicy {
    private const val UUID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

    fun isJournalArtifact(databaseFileName: String, artifactName: String): Boolean =
        artifactName == "$databaseFileName.sqlcipher-rekey.journal" ||
            artifactName == "$databaseFileName.sqlcipher-rekey.journal.tmp"

    fun isDatabaseArtifact(
        databaseFileName: String,
        artifactName: String,
        attemptId: String? = null,
    ): Boolean {
        val id = attemptId?.let(Regex::escape) ?: UUID_PATTERN
        return Regex(
            "^${Regex.escape(databaseFileName)}\\.(?:legacy-preimage|raw-temp)\\.$id" +
                "(?:\\.copying|\\.bak|\\.new|\\.lock|" +
                "-(?:wal|shm|journal)(?:\\.(?:at-rekey|failed-raw-$UUID_PATTERN))?)?$|" +
                "^${Regex.escape(databaseFileName)}\\.legacy-restore\\.$id(?:\\.copying)?$",
        ).matches(artifactName)
    }

    fun isEnvelopeArtifact(
        envelopeFileName: String,
        artifactName: String,
        attemptId: String? = null,
    ): Boolean {
        val id = attemptId?.let(Regex::escape) ?: UUID_PATTERN
        return Regex(
            "^${Regex.escape(envelopeFileName)}\\.(?:legacy-preimage|raw-staged)\\.$id" +
                "(?:\\.copying|\\.bak|\\.new|\\.lock)?$",
        ).matches(artifactName)
    }
}

private fun isAllowedTransition(current: SqlCipherRekeyStage, next: SqlCipherRekeyStage): Boolean {
    if (current == SqlCipherRekeyStage.INITIALIZED && next == SqlCipherRekeyStage.ROLLED_BACK) return true
    if (
        next == SqlCipherRekeyStage.ROLLBACK_PENDING &&
        current !in setOf(
            SqlCipherRekeyStage.ROLLBACK_PENDING,
            SqlCipherRekeyStage.COMPLETE,
            SqlCipherRekeyStage.ROLLED_BACK,
        )
    ) return true
    return when (current) {
        SqlCipherRekeyStage.INITIALIZED -> next == SqlCipherRekeyStage.LEGACY_PREIMAGE_READY
        SqlCipherRekeyStage.LEGACY_PREIMAGE_READY -> next == SqlCipherRekeyStage.RAW_ENVELOPE_STAGED
        SqlCipherRekeyStage.RAW_ENVELOPE_STAGED -> next == SqlCipherRekeyStage.RAW_TEMP_VERIFIED
        SqlCipherRekeyStage.RAW_TEMP_VERIFIED -> next == SqlCipherRekeyStage.SWAP_PENDING
        SqlCipherRekeyStage.SWAP_PENDING -> next == SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED
        SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED -> next == SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED
        SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED -> next == SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING
        SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING -> next == SqlCipherRekeyStage.COMPLETE
        SqlCipherRekeyStage.ROLLBACK_PENDING -> next == SqlCipherRekeyStage.ROLLED_BACK
        SqlCipherRekeyStage.COMPLETE,
        SqlCipherRekeyStage.ROLLED_BACK -> false
    }
}
