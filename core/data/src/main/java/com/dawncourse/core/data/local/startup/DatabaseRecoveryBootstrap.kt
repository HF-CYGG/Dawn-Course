package com.dawncourse.core.data.local.startup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.data.repository.BackupPayloadValidator
import com.dawncourse.core.data.repository.ValidatedBackupRestore
import com.dawncourse.core.data.repository.toRestorePayload
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.WebDavBackup
import com.dawncourse.core.domain.model.WebDavCredentials
import com.dawncourse.core.domain.repository.SettingsRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 恢复页动作的稳定结果；不回传路径、口令或底层异常。 */
sealed interface DatabaseRecoveryActionResult {
    /** 新加密库已完整换入，调用方必须受控重启进程。 */
    data object RestartRequired : DatabaseRecoveryActionResult

    /** 输入或恢复过程失败，原隔离库与 recovery marker 均保留。 */
    data class Failed(val reason: DatabaseRecoveryActionFailure) : DatabaseRecoveryActionResult
}

/** Recovery UI 可安全展示的失败分类。 */
enum class DatabaseRecoveryActionFailure {
    NotInRecoveryMode,
    BackupUnreadable,
    BackupTooLarge,
    BackupInvalid,
    WebDavAddressInvalid,
    WebDavAuthenticationFailed,
    WebDavUnavailable,
    KeyResetFailed,
    DatabaseRestoreFailed,
    SettingsRestoreFailed,
    AbandonFailed
}

/** 不依赖 AppDatabase 的恢复输入读取器。 */
internal class DatabaseRecoveryBackupReader(
    private val context: Context,
    private val clientFactory: () -> OkHttpClient = ::OkHttpClient,
    private val gsonFactory: () -> Gson = { GsonBuilder().create() },
) {
    /** 恢复页仅在用户实际选择 WebDAV 恢复时创建网络客户端。 */
    private val client: OkHttpClient by lazy(clientFactory)

    /** 本地或 WebDAV 恢复真正开始解析备份时才创建 JSON 解析器。 */
    private val gson: Gson by lazy(gsonFactory)

    /** SAF 只读取当前用户选择的 URI，并限制最大字节数。 */
    fun readLocal(uri: Uri): Result<ValidatedBackupRestore> = runCatching {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份" }
        val json = input.use(::readLimitedUtf8)
        val backup = requireNotNull(gson.fromJson(json, LocalBackupData::class.java)) { "备份为空" }
        BackupPayloadValidator.validate(backup.toRestorePayload())
    }

    /** 手动凭据仅用于本次请求，不写入 SharedPreferences/DataStore。 */
    fun readWebDav(credentials: WebDavCredentials): Result<ValidatedBackupRestore> = runCatching {
        val base = credentials.serverUrl.toHttpUrlOrNull() ?: error("WebDAV 地址无效")
        require(base.scheme == "https" || base.scheme == "http") { "WebDAV 协议无效" }
        val normalizedBase = base.newBuilder().apply {
            if (!base.encodedPath.endsWith('/')) addPathSegment("")
        }.build()
        var downloaded: String? = null
        DatabaseRecoveryWebDavPathPolicy.CANDIDATES.forEachIndexed { index, relativePath ->
            if (downloaded != null) return@forEachIndexed
            val segments = relativePath.split('/').filter(String::isNotBlank)
            val backupUrl = normalizedBase.newBuilder().apply {
                segments.forEach(::addPathSegment)
            }.build()
            val request = Request.Builder()
                .url(backupUrl)
                .header("Authorization", Credentials.basic(credentials.username, credentials.password))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) error("WebDAV 认证失败")
                if (response.code == 404 && index < DatabaseRecoveryWebDavPathPolicy.CANDIDATES.lastIndex) {
                    return@use
                }
                require(response.isSuccessful) { "WebDAV 下载失败" }
                val body = requireNotNull(response.body) { "WebDAV 响应为空" }
                downloaded = body.byteStream().use(::readLimitedUtf8)
            }
        }
        val backup = requireNotNull(gson.fromJson(downloaded, WebDavBackup::class.java)) { "备份为空" }
        BackupPayloadValidator.validate(backup.toRestorePayload())
    }

    /** 读取上限后额外探测一个字节，拒绝用 content-length 欺骗内存预算。 */
    private fun readLimitedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_BACKUP_BYTES) { "备份过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        /** v2 JSON 备份的恢复上限，避免恶意 SAF/WebDAV 输入导致 OOM。 */
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }
}

/** P0 兼容 WebDavBackup v2 与历史 v1 文件名；Profile manifest 在 P1-6 接入。 */
internal object DatabaseRecoveryWebDavPathPolicy {
    val CANDIDATES = listOf(
        "DawnCourseBackup/backup_v4.json",
        "DawnCourseBackup/backup_v3.json",
        "DawnCourseBackup/backup_v2.json",
        "DawnCourseBackup/backup_v1.json"
    )
}

/** 恢复安装 journal 阶段；任一阶段都可在下一冷启动机械收敛。 */
internal enum class DatabaseRecoveryInstallStage {
    INITIALIZED,
    NEW_ENVELOPE_READY,
    STAGING_VERIFIED,
    MAIN_SWAPPED,
    SETTINGS_APPLIED,
    COMMITTED,
    ROLLED_BACK
}

/** journal 只保存受限 UUID；所有文件路径均由实现固定派生。 */
internal data class DatabaseRecoveryInstallAttempt(
    val id: String,
    val stage: DatabaseRecoveryInstallStage
)

/** 崩溃后恢复安装的稳定结果。 */
internal enum class DatabaseRecoveryInstallRecovery {
    NO_WORK,
    KEEP_RECOVERY_MODE,
    COMMITTED,
    FAILED
}

/** 冷启动对单个持久阶段采取的动作。 */
internal enum class DatabaseRecoveryInstallResumeAction {
    ROLLBACK_NEW_REPLACEMENT,
    FINISH_SETTINGS_AND_COMMIT,
    KEEP_COMMITTED,
    KEEP_RECOVERY_MODE
}

/** 将 journal 阶段机械映射为恢复动作，避免在文件代码中散落模糊分支。 */
internal object DatabaseRecoveryInstallRecoveryPolicy {
    fun action(stage: DatabaseRecoveryInstallStage): DatabaseRecoveryInstallResumeAction = when (stage) {
        DatabaseRecoveryInstallStage.INITIALIZED,
        DatabaseRecoveryInstallStage.NEW_ENVELOPE_READY,
        DatabaseRecoveryInstallStage.STAGING_VERIFIED -> {
            DatabaseRecoveryInstallResumeAction.ROLLBACK_NEW_REPLACEMENT
        }
        DatabaseRecoveryInstallStage.MAIN_SWAPPED,
        DatabaseRecoveryInstallStage.SETTINGS_APPLIED -> {
            DatabaseRecoveryInstallResumeAction.FINISH_SETTINGS_AND_COMMIT
        }
        DatabaseRecoveryInstallStage.COMMITTED -> DatabaseRecoveryInstallResumeAction.KEEP_COMMITTED
        DatabaseRecoveryInstallStage.ROLLED_BACK -> DatabaseRecoveryInstallResumeAction.KEEP_RECOVERY_MODE
    }
}

/** 旧/目标设置的最小 crash-forward payload，不包含课程、凭据或 WebDAV 口令。 */
internal data class RecoverySettingsPayload(
    val oldSettings: com.dawncourse.core.domain.model.AppSettings,
    val oldSelectedSemesterId: Long?,
    val targetSettings: com.dawncourse.core.domain.model.AppSettings,
    val targetSelectedSemesterId: Long?,
    /** 写入前的原始 active_profile_id；null 表示该键原本不存在。 */
    val oldActiveProfileId: Long? = null,
    /** 备份声明的活动 Profile；null 表示备份未携带该字段。 */
    val targetActiveProfileId: Long? = null
)

/** 使用 AtomicFile + 固定路径持久化恢复安装阶段和设置补偿数据。 */
internal class AndroidDatabaseRecoveryInstallJournal(
    private val context: Context,
    private val idSource: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    private val directory = File(context.noBackupFilesDir, "database-recovery")
    private val journal = android.util.AtomicFile(File(directory, "bootstrap-install-v1"))
    private val gson = GsonBuilder().create()

    fun begin(
        oldSettings: com.dawncourse.core.domain.model.AppSettings,
        oldSelection: Long?,
        targetSettings: com.dawncourse.core.domain.model.AppSettings,
        targetSelection: Long?,
        oldActiveProfileId: Long?,
        targetActiveProfileId: Long?
    ): DatabaseRecoveryInstallAttempt {
        current()?.let { existing ->
            require(existing.stage in TERMINAL_STAGES) { "上一次恢复安装尚未收敛" }
            cleanupTerminalAttempt(existing)
        }
        val id = idSource().lowercase()
        require(ATTEMPT_ID.matches(id)) { "恢复安装 attempt ID 无效" }
        writeSettings(
            id,
            RecoverySettingsPayload(
                oldSettings = oldSettings,
                oldSelectedSemesterId = oldSelection,
                targetSettings = targetSettings,
                targetSelectedSemesterId = targetSelection,
                oldActiveProfileId = oldActiveProfileId,
                targetActiveProfileId = targetActiveProfileId
            )
        )
        val attempt = DatabaseRecoveryInstallAttempt(id, DatabaseRecoveryInstallStage.INITIALIZED)
        write(attempt)
        return attempt
    }

    fun current(): DatabaseRecoveryInstallAttempt? {
        return runCatching {
            val bytes = AtomicFileArtifactProtocol.readOrNull(journal) ?: return null
            if (bytes.size !in 1..MAX_JOURNAL_BYTES) error("journal 大小无效")
            val lines = try {
                bytes.toString(Charsets.UTF_8).lines()
            } finally {
                bytes.fill(0)
            }
            require(lines.size == 3 && lines[0] == MAGIC) { "journal 格式无效" }
            val id = lines[1].removePrefix(ID_PREFIX)
            val stage = lines[2].removePrefix(STAGE_PREFIX)
            require(lines[1] != id && lines[2] != stage && ATTEMPT_ID.matches(id)) { "journal 字段无效" }
            DatabaseRecoveryInstallAttempt(id, DatabaseRecoveryInstallStage.valueOf(stage))
        }.getOrNull()
    }

    /** 区分“没有 journal”与“journal 存在但损坏”。 */
    fun exists(): Boolean = AtomicFileArtifactProtocol.hasAnyArtifact(journal)

    fun record(attempt: DatabaseRecoveryInstallAttempt, next: DatabaseRecoveryInstallStage) {
        val current = requireNotNull(current()) { "恢复安装 journal 缺失或损坏" }
        require(current.id == attempt.id) { "恢复安装 attempt 不匹配" }
        require(allowed(current.stage, next)) { "恢复安装阶段转换无效" }
        write(DatabaseRecoveryInstallAttempt(attempt.id, next))
    }

    fun readSettings(attempt: DatabaseRecoveryInstallAttempt): RecoverySettingsPayload {
        val file = settingsFile(attempt.id)
        require(file.isFile && file.length() in 1..MAX_SETTINGS_BYTES) { "恢复设置 payload 无效" }
        return requireNotNull(gson.fromJson(file.readText(Charsets.UTF_8), RecoverySettingsPayload::class.java))
    }

    fun clearCommittedArtifacts(attempt: DatabaseRecoveryInstallAttempt): Boolean = runCatching {
        require(attempt.stage == DatabaseRecoveryInstallStage.COMMITTED)
        deleteExact(settingsFile(attempt.id))
        AtomicFileArtifactProtocol.deleteAndConfirm(journal)
        true
    }.getOrDefault(false)

    private fun cleanupTerminalAttempt(attempt: DatabaseRecoveryInstallAttempt) {
        deleteExact(settingsFile(attempt.id))
        AtomicFileArtifactProtocol.deleteAndConfirm(journal)
    }

    private fun writeSettings(id: String, payload: RecoverySettingsPayload) {
        directory.mkdirs()
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_SETTINGS_BYTES) { "恢复设置 payload 过大" }
        val target = settingsFile(id)
        val temporary = File(target.path + ".writing")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun write(attempt: DatabaseRecoveryInstallAttempt) {
        directory.mkdirs()
        val output = journal.startWrite()
        try {
            output.write(
                "$MAGIC\n$ID_PREFIX${attempt.id}\n$STAGE_PREFIX${attempt.stage.name}"
                    .toByteArray(Charsets.UTF_8)
            )
            journal.finishWrite(output)
        } catch (failure: Throwable) {
            journal.failWrite(output)
            throw failure
        }
    }

    private fun settingsFile(id: String): File {
        require(ATTEMPT_ID.matches(id))
        return File(directory, "bootstrap-settings.$id.json")
    }

    private fun deleteExact(file: File) {
        if (file.exists()) require(file.delete()) { "无法清理恢复安装文件" }
    }

    private fun allowed(current: DatabaseRecoveryInstallStage, next: DatabaseRecoveryInstallStage): Boolean {
        if (next == DatabaseRecoveryInstallStage.ROLLED_BACK && current != DatabaseRecoveryInstallStage.COMMITTED) {
            return true
        }
        return when (current) {
            DatabaseRecoveryInstallStage.INITIALIZED -> next == DatabaseRecoveryInstallStage.NEW_ENVELOPE_READY
            DatabaseRecoveryInstallStage.NEW_ENVELOPE_READY -> next == DatabaseRecoveryInstallStage.STAGING_VERIFIED
            DatabaseRecoveryInstallStage.STAGING_VERIFIED -> next == DatabaseRecoveryInstallStage.MAIN_SWAPPED
            DatabaseRecoveryInstallStage.MAIN_SWAPPED -> next == DatabaseRecoveryInstallStage.SETTINGS_APPLIED
            DatabaseRecoveryInstallStage.SETTINGS_APPLIED -> next == DatabaseRecoveryInstallStage.COMMITTED
            DatabaseRecoveryInstallStage.COMMITTED,
            DatabaseRecoveryInstallStage.ROLLED_BACK -> false
        }
    }

    private companion object {
        const val MAGIC = "DAWN_RECOVERY_BOOTSTRAP_V1"
        const val ID_PREFIX = "attempt="
        const val STAGE_PREFIX = "stage="
        const val MAX_JOURNAL_BYTES = 512L
        const val MAX_SETTINGS_BYTES = 1024 * 1024L
        val ATTEMPT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TERMINAL_STAGES = setOf(
            DatabaseRecoveryInstallStage.COMMITTED,
            DatabaseRecoveryInstallStage.ROLLED_BACK
        )
    }
}

/** 用户明确选择恢复/放弃后使用的加密 staging 与原子换入实现。 */
internal class DatabaseRecoveryBootstrapInstaller(
    private val context: Context,
    private val databaseFile: File,
    private val recoveryFiles: AndroidDatabaseRecoveryFiles,
    private val roomFactory: SqlCipherRoomDatabaseFactory
) {
    private val keyMaterial = AndroidDatabaseRecoveryKeyMaterial(context)

    fun install(
        attempt: DatabaseRecoveryInstallAttempt,
        validated: ValidatedBackupRestore?,
        record: (DatabaseRecoveryInstallStage) -> Unit
    ) {
        require(recoveryFiles.readRecoveryReason() != null) { "当前不在恢复模式" }
        recoveryFiles.ensureQuarantined()
        require(!databaseFile.exists()) { "恢复前主数据库路径必须为空" }
        val stagingName = stagingName(attempt.id)
        val stagingFile = context.getDatabasePath(stagingName)
        require(!stagingFile.exists()) { "恢复 staging 已存在" }
        keyMaterial.archiveEnvelopeForExplicitRecovery()
        val passphrase = provisionRecoveryPassphrase()
        try {
            record(DatabaseRecoveryInstallStage.NEW_ENVELOPE_READY)
            val staging = roomFactory.open(stagingName, passphrase)
            try {
                // 恢复 staging 永远走同步双扫描，不参与普通冷启动的后台策略。
                roomFactory.verifyIntegrity(staging)
                if (validated != null) {
                    runBlocking {
                        staging.withTransaction {
                            validated.profiles.forEach {
                                staging.timetableProfileDao().insert(it.toEntity())
                            }
                            validated.semesters.forEach { staging.semesterDao().insertSemester(it.toEntity()) }
                            staging.courseDao().insertCourses(validated.courses.map { it.toEntity() })
                            validated.sourceBindings.forEach {
                                staging.syncSourceBindingDao().insert(it.toEntity())
                            }
                        }
                    }
                }
                checkpointAndUseDeleteJournal(staging.openHelper.writableDatabase)
            } finally {
                staging.close()
            }
            val verification = AndroidPlaintextToSqlCipherMigrationBackend()
                .inspectEncrypted(stagingFile, passphrase)
            require(verification.integrityOk && verification.cipherIntegrityOk == true)
            val expectedProfiles = validated?.profiles?.size?.toLong() ?: 0L
            val expectedSemesters = validated?.semesters?.size?.toLong() ?: 0L
            val expectedCourses = validated?.courses?.size?.toLong() ?: 0L
            val expectedBindings = validated?.sourceBindings?.size?.toLong() ?: 0L
            require(
                verification.snapshot.userTableRowCounts["timetable_profiles"] == expectedProfiles &&
                    verification.snapshot.userTableRowCounts["semesters"] == expectedSemesters &&
                    verification.snapshot.userTableRowCounts["courses"] == expectedCourses &&
                    verification.snapshot.userTableRowCounts["sync_source_bindings"] == expectedBindings
            ) { "恢复数据量校验失败" }
            requireNoSidecars(stagingFile)
            record(DatabaseRecoveryInstallStage.STAGING_VERIFIED)
            atomicMove(stagingFile, databaseFile)
            forceDirectoryBestEffort(requireNotNull(databaseFile.parentFile))
            record(DatabaseRecoveryInstallStage.MAIN_SWAPPED)
        } finally {
            passphrase.close()
        }
    }

    /** 任一未提交 attempt 都把新 main/staging/envelope移到唯一审计路径，不覆盖旧隔离材料。 */
    fun rollbackNewReplacement(attempt: DatabaseRecoveryInstallAttempt): Boolean = runCatching {
        val staging = context.getDatabasePath(stagingName(attempt.id))
        archiveDatabaseFamily(databaseFile, "${databaseFile.name}.failed-recovery.${attempt.id}")
        archiveDatabaseFamily(staging, "${databaseFile.name}.failed-staging.${attempt.id}")
        keyMaterial.archiveCurrentEnvelopeAsFailed(attempt.id)
        true
    }.getOrDefault(false)

    fun cleanupCommittedOldKeyMaterial(): Boolean = keyMaterial.cleanupOldQuarantine()

    private fun provisionRecoveryPassphrase(): SqlCipherPassphrase {
        val store = AndroidDatabasePassphraseEnvelopeStore(context)
        return when (val result = store.createNew()) {
            is NewPassphraseResult.Available -> result.passphrase
            NewPassphraseResult.ExistingEnvelope -> error("旧信封未完成隔离")
            is NewPassphraseResult.Failed -> {
                keyMaterial.deleteCurrentAliasAfterExplicitRecovery()
                when (val retried = store.createNew()) {
                    is NewPassphraseResult.Available -> retried.passphrase
                    else -> error("无法创建恢复密钥")
                }
            }
        }
    }

    private fun archiveDatabaseFamily(sourceBase: File, failedBaseName: String) {
        val targetBase = File(databaseFile.parentFile, failedBaseName)
        listOf("").plus(SIDECARS).forEach { suffix ->
            val source = File(sourceBase.path + suffix)
            val target = File(targetBase.path + suffix)
            if (source.exists()) {
                require(!target.exists()) { "恢复失败审计文件已存在" }
                atomicMove(source, target)
            }
        }
    }

    private fun requireNoSidecars(file: File) {
        SIDECARS.forEach { suffix ->
            val sidecar = File(file.path + suffix)
            require(!sidecar.exists() || sidecar.length() == 0L) { "恢复库存在未合并 sidecar" }
            if (sidecar.exists()) require(sidecar.delete()) { "无法清理恢复 sidecar" }
        }
    }

    /** 原子换入前把 Room 的 WAL 完整合并，确保单一主文件可独立恢复。 */
    private fun checkpointAndUseDeleteJournal(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            require(cursor.moveToFirst()) { "恢复库 WAL checkpoint 未返回结果" }
            require(cursor.getInt(0) == 0) { "恢复库 WAL checkpoint 忙碌" }
            require(!cursor.moveToNext()) { "恢复库 WAL checkpoint 返回多行" }
        }
        database.query("PRAGMA journal_mode=DELETE").use { cursor ->
            require(cursor.moveToFirst()) { "恢复库 journal_mode 未返回结果" }
            require(cursor.getString(0).equals("delete", ignoreCase = true)) {
                "恢复库无法切换到 DELETE journal"
            }
            require(!cursor.moveToNext()) { "恢复库 journal_mode 返回多行" }
        }
    }

    private fun stagingName(id: String): String {
        require(ATTEMPT_ID.matches(id))
        return "${databaseFile.name}.recovery-staging.$id"
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("当前文件系统不支持恢复原子换入", unsupported)
        }
    }

    private fun forceDirectoryBestEffort(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private companion object {
        val SIDECARS = listOf("-wal", "-shm", "-journal")
        val ATTEMPT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** 只在用户已明确恢复/放弃后隔离旧信封，普通启动绝不调用。 */
private class AndroidDatabaseRecoveryKeyMaterial(
    private val context: Context
) {
    private val envelopeDirectory = File(context.noBackupFilesDir, "database")
    private val recoveryDirectory = File(context.noBackupFilesDir, "database-recovery")
    private val envelope = File(envelopeDirectory, "dawn_course_key_envelope.bin")

    fun archiveEnvelopeForExplicitRecovery() {
        recoveryDirectory.mkdirs()
        ENVELOPE_SUFFIXES.forEach { suffix ->
            val source = File(envelope.path + suffix)
            val target = File(recoveryDirectory, "key-envelope.quarantine$suffix")
            if (source.exists()) {
                require(!target.exists()) { "旧数据库密钥信封隔离文件已存在" }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    fun archiveCurrentEnvelopeAsFailed(attemptId: String) {
        require(ATTEMPT_ID.matches(attemptId))
        ENVELOPE_SUFFIXES.forEach { suffix ->
            val source = File(envelope.path + suffix)
            val target = File(recoveryDirectory, "key-envelope.failed.$attemptId$suffix")
            if (source.exists()) {
                require(!target.exists()) { "恢复失败密钥信封已存在" }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    fun cleanupOldQuarantine(): Boolean = runCatching {
        ENVELOPE_SUFFIXES.forEach { suffix ->
            val target = File(recoveryDirectory, "key-envelope.quarantine$suffix")
            if (target.exists()) require(target.delete()) { "无法清理旧密钥信封隔离文件" }
        }
        true
    }.getOrDefault(false)

    fun deleteCurrentAliasAfterExplicitRecovery() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private companion object {
        const val KEY_ALIAS = "com.dawncourse.database.key.v1"
        val ENVELOPE_SUFFIXES = listOf("", ".bak", ".new")
        val ATTEMPT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** 由 Runtime 调用的恢复流程；整个实现不注入原 AppDatabase。 */
internal class DatabaseRecoveryBootstrapCoordinator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val activeProfileSelectionStore: com.dawncourse.core.data.repository.ActiveProfileSelectionStore,
    private val criticalSection: DatabaseStartupCriticalSection,
    private val recoveryFiles: AndroidDatabaseRecoveryFiles,
    private val installer: DatabaseRecoveryBootstrapInstaller,
    private val reader: DatabaseRecoveryBackupReader = DatabaseRecoveryBackupReader(context),
    private val journal: AndroidDatabaseRecoveryInstallJournal = AndroidDatabaseRecoveryInstallJournal(context),
    /** 恢复动作提交成功后，与 recoveryFiles 标记一并清除全部在线恢复责任。 */
    private val clearRecoveryResponsibilities: () -> Unit = {}
) {
    suspend fun restoreLocal(uri: Uri): DatabaseRecoveryActionResult = withContext(Dispatchers.IO) {
        val validated = reader.readLocal(uri).getOrElse { error ->
            return@withContext DatabaseRecoveryActionResult.Failed(classifyBackupFailure(error))
        }
        install(validated, DatabaseRecoveryActionFailure.DatabaseRestoreFailed)
    }

    suspend fun restoreWebDav(credentials: WebDavCredentials): DatabaseRecoveryActionResult =
        withContext(Dispatchers.IO) {
            val validated = reader.readWebDav(credentials).getOrElse { error ->
                return@withContext DatabaseRecoveryActionResult.Failed(classifyWebDavFailure(error))
            }
            install(validated, DatabaseRecoveryActionFailure.DatabaseRestoreFailed)
        }

    suspend fun abandon(): DatabaseRecoveryActionResult = withContext(Dispatchers.IO) {
        val currentSettings = settingsRepository.settings.first()
        val empty = ValidatedBackupRestore(
            settings = currentSettings,
            semesters = emptyList(),
            courses = emptyList(),
            selectedSemesterId = null,
            profiles = emptyList(),
            sourceBindings = emptyList(),
            activeProfileId = null,
        )
        install(empty, DatabaseRecoveryActionFailure.AbandonFailed)
    }

    /** 启动外层锁内先收敛上次恢复 attempt，再允许普通数据库状态机检查文件。 */
    fun recoverInterruptedInstall(): DatabaseRecoveryInstallRecovery {
        val attempt = journal.current()
            ?: return if (journal.exists()) {
                DatabaseRecoveryInstallRecovery.FAILED
            } else {
                DatabaseRecoveryInstallRecovery.NO_WORK
            }
        return when (DatabaseRecoveryInstallRecoveryPolicy.action(attempt.stage)) {
            DatabaseRecoveryInstallResumeAction.KEEP_COMMITTED -> DatabaseRecoveryInstallRecovery.COMMITTED
            DatabaseRecoveryInstallResumeAction.KEEP_RECOVERY_MODE -> {
                DatabaseRecoveryInstallRecovery.KEEP_RECOVERY_MODE
            }
            DatabaseRecoveryInstallResumeAction.FINISH_SETTINGS_AND_COMMIT -> finishOrRollback(attempt)
            DatabaseRecoveryInstallResumeAction.ROLLBACK_NEW_REPLACEMENT -> rollback(attempt)
        }
    }

    /** 下一成功冷开同时清理旧迁移产物、旧 DB、旧信封和 committed journal。 */
    fun cleanupAfterVerifiedColdOpen(
        cleanupRolledBackMigration: () -> Boolean,
    ): Boolean {
        val attempt = journal.current()
        return cleanupArtifactsAfterVerifiedRecoveryColdOpen(
            recoveryInstallStage = attempt?.stage,
            cleanupRolledBackMigration = cleanupRolledBackMigration,
            cleanupRecoveryDatabase = recoveryFiles::cleanupAfterVerifiedColdOpen,
            cleanupOldKeyMaterial = installer::cleanupCommittedOldKeyMaterial,
            cleanupInstallJournal = {
                attempt != null && journal.clearCommittedArtifacts(attempt)
            },
        )
    }

    private suspend fun install(
        validated: ValidatedBackupRestore,
        failure: DatabaseRecoveryActionFailure
    ): DatabaseRecoveryActionResult {
        if (recoveryFiles.readRecoveryReason() == null) {
            return DatabaseRecoveryActionResult.Failed(DatabaseRecoveryActionFailure.NotInRecoveryMode)
        }
        val oldSettings = settingsRepository.settings.first()
        val oldSelection = settingsRepository.selectedSemesterId.first()
        val oldActiveProfileId = activeProfileSelectionStore.rawActiveProfileId.first()
        var succeeded = false
        criticalSection.run {
            val attempt = journal.begin(
                oldSettings = oldSettings,
                oldSelection = oldSelection,
                targetSettings = validated.settings,
                targetSelection = validated.selectedSemesterId,
                oldActiveProfileId = oldActiveProfileId,
                targetActiveProfileId = validated.activeProfileId
            )
            succeeded = runCatching {
                installer.install(
                    attempt,
                    validated.takeIf {
                        it.profiles.isNotEmpty() || it.semesters.isNotEmpty() || it.courses.isNotEmpty()
                    },
                ) {
                    stage -> journal.record(attempt, stage)
                }
                runBlocking {
                    settingsRepository.restoreAllSettingsAndSelection(
                        validated.settings,
                        validated.selectedSemesterId
                    )
                    applyActiveProfileSelection(validated.activeProfileId)
                }
                journal.record(attempt, DatabaseRecoveryInstallStage.SETTINGS_APPLIED)
                commitExplicitRecoveryDecision(
                    clearRecoveryResponsibilities = clearRecoveryResponsibilities,
                    clearRecoveryStateMarker = recoveryFiles::clearMarkerAfterExplicitDecision,
                    recordCommitted = { journal.record(attempt, DatabaseRecoveryInstallStage.COMMITTED) },
                )
                true
            }.getOrElse { failureCause ->
                if (failureCause is RecoveryResponsibilitiesPendingException) {
                    // replacement 与 SETTINGS_APPLIED journal 保留；下一冷启动会重试同一提交步骤，
                    // 不能因 marker 删除故障伪造 RestartRequired 或回滚到不可判定状态。
                    false
                } else {
                    runCatching {
                        val payload = journal.readSettings(attempt)
                        runBlocking {
                            settingsRepository.restoreAllSettingsAndSelection(
                                payload.oldSettings,
                                payload.oldSelectedSemesterId
                            )
                            activeProfileSelectionStore.restoreRawSelection(payload.oldActiveProfileId)
                        }
                    }
                    val rolledBack = installer.rollbackNewReplacement(attempt)
                    if (rolledBack) runCatching { journal.record(attempt, DatabaseRecoveryInstallStage.ROLLED_BACK) }
                    false
                }
            }
        }
        return if (succeeded) {
            DatabaseRecoveryActionResult.RestartRequired
        } else {
            DatabaseRecoveryActionResult.Failed(failure)
        }
    }

    /** 与常规恢复入口一致的写入语义：备份未声明活动 Profile 时显式清空，而非保留旧值。 */
    private suspend fun applyActiveProfileSelection(activeProfileId: Long?) {
        if (activeProfileId == null) {
            activeProfileSelectionStore.clearSelection()
        } else {
            activeProfileSelectionStore.selectProfile(activeProfileId)
        }
    }

    private fun finishOrRollback(attempt: DatabaseRecoveryInstallAttempt): DatabaseRecoveryInstallRecovery {
        val finished = try {
            val payload = journal.readSettings(attempt)
            runBlocking {
                settingsRepository.restoreAllSettingsAndSelection(
                    payload.targetSettings,
                    payload.targetSelectedSemesterId
                )
                applyActiveProfileSelection(payload.targetActiveProfileId)
            }
            if (attempt.stage == DatabaseRecoveryInstallStage.MAIN_SWAPPED) {
                journal.record(attempt, DatabaseRecoveryInstallStage.SETTINGS_APPLIED)
            }
            commitExplicitRecoveryDecision(
                clearRecoveryResponsibilities = clearRecoveryResponsibilities,
                clearRecoveryStateMarker = recoveryFiles::clearMarkerAfterExplicitDecision,
                recordCommitted = { journal.record(attempt, DatabaseRecoveryInstallStage.COMMITTED) },
            )
            true
        } catch (_: RecoveryResponsibilitiesPendingException) {
            return DatabaseRecoveryInstallRecovery.KEEP_RECOVERY_MODE
        } catch (_: Throwable) {
            false
        }
        if (finished) return DatabaseRecoveryInstallRecovery.COMMITTED
        return rollback(attempt)
    }

    private fun rollback(attempt: DatabaseRecoveryInstallAttempt): DatabaseRecoveryInstallRecovery {
        val settingsRestored = runCatching {
            val payload = journal.readSettings(attempt)
            runBlocking {
                settingsRepository.restoreAllSettingsAndSelection(
                    payload.oldSettings,
                    payload.oldSelectedSemesterId
                )
                activeProfileSelectionStore.restoreRawSelection(payload.oldActiveProfileId)
            }
        }.isSuccess
        val filesRestored = installer.rollbackNewReplacement(attempt)
        val stageRecorded = if (filesRestored) {
            runCatching { journal.record(attempt, DatabaseRecoveryInstallStage.ROLLED_BACK) }.isSuccess
        } else {
            false
        }
        return if (settingsRestored && filesRestored && stageRecorded) {
            DatabaseRecoveryInstallRecovery.KEEP_RECOVERY_MODE
        } else {
            DatabaseRecoveryInstallRecovery.FAILED
        }
    }

    private fun classifyBackupFailure(error: Throwable): DatabaseRecoveryActionFailure = when {
        error.message?.contains("过大") == true -> DatabaseRecoveryActionFailure.BackupTooLarge
        error is IllegalArgumentException -> DatabaseRecoveryActionFailure.BackupInvalid
        else -> DatabaseRecoveryActionFailure.BackupUnreadable
    }

    private fun classifyWebDavFailure(error: Throwable): DatabaseRecoveryActionFailure = when {
        error.message?.contains("地址") == true || error.message?.contains("协议") == true -> {
            DatabaseRecoveryActionFailure.WebDavAddressInvalid
        }
        error.message?.contains("认证") == true -> DatabaseRecoveryActionFailure.WebDavAuthenticationFailed
        error is IllegalArgumentException -> DatabaseRecoveryActionFailure.BackupInvalid
        else -> DatabaseRecoveryActionFailure.WebDavUnavailable
    }
}

/** marker 清除未确认时保持 SETTINGS_APPLIED journal，供下次冷启动安全重试。 */
internal class RecoveryResponsibilitiesPendingException(cause: Throwable) : IllegalStateException(cause)

/**
 * 显式恢复提交的唯一顺序：先确认全部在线责任 marker，再确认 recovery-state-v1，最后记录
 * COMMITTED。任一 marker 故障绝不允许越过 COMMITTED，journal 保留为可机械重试的状态。
 */
internal fun commitExplicitRecoveryDecision(
    clearRecoveryResponsibilities: () -> Unit,
    clearRecoveryStateMarker: () -> Unit,
    recordCommitted: () -> Unit,
) {
    try {
        clearRecoveryResponsibilities()
        clearRecoveryStateMarker()
    } catch (failure: Throwable) {
        throw RecoveryResponsibilitiesPendingException(failure)
    }
    recordCommitted()
}

/**
 * 只有 COMMITTED 恢复安装能证明用户已完成恢复或明确放弃；普通冷开仅处理无 marker 的隔离残留。
 * 清理短路并保持 journal 到最后，使任一步失败都能在下次冷启动安全重试。
 */
internal fun cleanupArtifactsAfterVerifiedRecoveryColdOpen(
    recoveryInstallStage: DatabaseRecoveryInstallStage?,
    cleanupRolledBackMigration: () -> Boolean,
    cleanupRecoveryDatabase: () -> Boolean,
    cleanupOldKeyMaterial: () -> Boolean,
    cleanupInstallJournal: () -> Boolean,
): Boolean = when (recoveryInstallStage) {
    null -> cleanupRecoveryDatabase()
    DatabaseRecoveryInstallStage.COMMITTED -> {
        cleanupRolledBackMigration() &&
            cleanupRecoveryDatabase() &&
            cleanupOldKeyMaterial() &&
            cleanupInstallJournal()
    }
    else -> false
}
