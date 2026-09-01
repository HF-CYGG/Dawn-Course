package com.dawncourse.core.data.local.startup

import android.content.Context
import android.util.AtomicFile
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.AppDatabaseMigrations
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.model.WebDavCredentials
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** 数据库启动、恢复 UI 与 DataModule 共用的进程级唯一 Runtime。 */
@Singleton
class DatabaseStartupRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository
) : OperationalDataGate {
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)
    private val migrationFiles = AtomicDatabaseMigrationFiles(databaseFile)
    private val recoveryFiles = AndroidDatabaseRecoveryFiles(context, databaseFile)
    private val roomFactory = SqlCipherRoomDatabaseFactory(context)
    private val startupCriticalSection = AndroidDatabaseStartupCriticalSection(databaseFile)
    private val controller = DatabaseStartupRuntimeController(
        criticalSection = startupCriticalSection,
        initializer = DatabaseStartupInitializer(::initializeWhileLocked)
    )
    private val recoveryBootstrap = DatabaseRecoveryBootstrapCoordinator(
        context = context,
        settingsRepository = settingsRepository,
        criticalSection = startupCriticalSection,
        recoveryFiles = recoveryFiles,
        installer = DatabaseRecoveryBootstrapInstaller(
            context = context,
            databaseFile = databaseFile,
            recoveryFiles = recoveryFiles,
            roomFactory = roomFactory
        )
    )
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Compose 只观察稳定状态；数据库句柄不会进入状态对象。 */
    val state: StateFlow<DatabaseRuntimeState> = controller.state

    /** 主进程 Application 提交唯一 IO 初始化；脚本隔离进程不得调用。 */
    fun start() {
        controller.start(applicationScope)
    }

    /** DataModule 只能取得 Runtime 已打开并验证的同一 Room 实例。 */
    fun requireReadyDatabase(): AppDatabase = controller.requireReadyHandle()

    /** Worker/Widget/Receiver 的无阻塞启动守卫。 */
    override fun readiness(): OperationalDataReadiness = controller.readiness()

    /** 从 SAF canonical 备份恢复；不会解析原 AppDatabase。 */
    suspend fun restoreFromLocalBackup(uri: Uri): DatabaseRecoveryActionResult =
        recoveryBootstrap.restoreLocal(uri)

    /** 使用仅驻留于本次调用内存的手动凭据恢复 WebDAV canonical 备份。 */
    suspend fun restoreFromWebDav(credentials: WebDavCredentials): DatabaseRecoveryActionResult =
        recoveryBootstrap.restoreWebDav(credentials)

    /** 用户二次确认后放弃不可访问数据并创建空加密库。 */
    suspend fun abandonInaccessibleData(): DatabaseRecoveryActionResult = recoveryBootstrap.abandon()

    /** 外层锁已持有；这里不得等待 UI 或发起需要用户输入的操作。 */
    private fun initializeWhileLocked(): DatabaseStartupInitialization<AppDatabase> {
        when (recoveryBootstrap.recoverInterruptedInstall()) {
            DatabaseRecoveryInstallRecovery.FAILED -> {
                return enterRecovery(DatabaseRecoveryReason.RecoveryStateCorrupt)
            }
            DatabaseRecoveryInstallRecovery.KEEP_RECOVERY_MODE -> {
                if (recoveryFiles.readRecoveryReason() == null) {
                    return enterRecovery(DatabaseRecoveryReason.RecoveryStateCorrupt)
                }
            }
            DatabaseRecoveryInstallRecovery.NO_WORK,
            DatabaseRecoveryInstallRecovery.COMMITTED -> Unit
        }
        recoveryFiles.readRecoveryReason()?.let { reason ->
            val reconciled = runCatching {
                recoveryFiles.ensureQuarantined()
                reason
            }.getOrElse { DatabaseRecoveryReason.RecoveryStateCorrupt }
            return DatabaseStartupInitialization.RecoveryRequired(reconciled)
        }
        if (migrationFiles.recoverIncompleteMigration() == DatabaseMigrationRecovery.Failed) {
            return enterRecovery(DatabaseRecoveryReason.CrashRecoveryFailed)
        }

        val coordinator = DatabaseStartupCoordinator(
            fileInspector = SqliteDatabaseFileInspector(databaseFile),
            envelopeStore = AndroidDatabasePassphraseEnvelopeStore(context)
        )
        return when (val plan = coordinator.prepare()) {
            is DatabaseStartupPlan.RecoveryRequired -> enterRecovery(plan.reason)
            is DatabaseStartupPlan.CreateNewEncryptedDatabase -> openAndPublish(
                passphrase = plan.passphrase,
                migratedPlaintextThisRun = false
            )
            is DatabaseStartupPlan.OpenEncryptedDatabase -> openAndPublish(
                passphrase = plan.passphrase,
                migratedPlaintextThisRun = false
            )
            is DatabaseStartupPlan.EncryptPlaintextDatabase -> migrateOpenAndPublish(plan.passphrase)
        }
    }

    /** 明文换入成功后同一次启动仍保留 pre-image，下一次成功冷开才清理。 */
    private fun migrateOpenAndPublish(
        passphrase: SqlCipherPassphrase
    ): DatabaseStartupInitialization<AppDatabase> {
        val migration = PlaintextToSqlCipherMigrator(
            files = migrationFiles,
            backend = AndroidPlaintextToSqlCipherMigrationBackend()
        ).migrate(passphrase)
        return when (migration) {
            is PlaintextToSqlCipherMigrationResult.Success -> openAndPublish(
                passphrase = passphrase,
                migratedPlaintextThisRun = true
            )
            is PlaintextToSqlCipherMigrationResult.RecoveryRequired -> {
                passphrase.close()
                enterRecovery(DatabaseRecoveryReason.MigrationFailed)
            }
        }
    }

    /** Room 只有完整打开并通过 SQLite/SQLCipher 校验后才会发布给 Hilt。 */
    private fun openAndPublish(
        passphrase: SqlCipherPassphrase,
        migratedPlaintextThisRun: Boolean
    ): DatabaseStartupInitialization<AppDatabase> {
        val database = try {
            roomFactory.openAndVerify(DATABASE_NAME, passphrase)
        } catch (_: Throwable) {
            passphrase.close()
            return enterRecovery(DatabaseRecoveryReason.DatabaseOpenFailed)
        }
        val profileReady = runCatching {
            runBlocking {
                if (database.timetableProfileDao().getFirstProfile() == null) {
                    database.timetableProfileDao().insert(
                        TimetableProfileEntity(
                            id = DEFAULT_PROFILE_ID,
                            uuid = UUID.randomUUID().toString(),
                            name = DEFAULT_PROFILE_NAME,
                        ),
                    )
                }
            }
        }.isSuccess
        passphrase.close()
        if (!profileReady) {
            database.close()
            return enterRecovery(DatabaseRecoveryReason.DatabaseOpenFailed)
        }
        if (!migratedPlaintextThisRun && !migrationFiles.cleanupAfterVerifiedColdOpen()) {
            database.close()
            return enterRecovery(DatabaseRecoveryReason.RecoveryStateCorrupt)
        }
        if (!recoveryBootstrap.cleanupAfterVerifiedColdOpen()) {
            database.close()
            return enterRecovery(DatabaseRecoveryReason.RecoveryStateCorrupt)
        }
        return DatabaseStartupInitialization.Ready(database, migratedPlaintextThisRun)
    }

    /** 先原子隔离现存主库，再持久化可见恢复状态；任何失败都升级为状态损坏。 */
    private fun enterRecovery(
        reason: DatabaseRecoveryReason
    ): DatabaseStartupInitialization.RecoveryRequired {
        val persistedReason = runCatching { recoveryFiles.enterRecovery(reason) }
            .getOrElse { DatabaseRecoveryReason.RecoveryStateCorrupt }
        return DatabaseStartupInitialization.RecoveryRequired(persistedReason)
    }

    companion object {
        /** Room 主库固定名称。 */
        const val DATABASE_NAME = "dawn_course.db"
        private const val DEFAULT_PROFILE_ID = 1L
        private const val DEFAULT_PROFILE_NAME = "默认课表"
    }
}

/** 使用独立 startup 锁包围 journal→文件→信封→迁移/打开→Room 的完整生命周期。 */
private class AndroidDatabaseStartupCriticalSection(
    databaseFile: File
) : DatabaseStartupCriticalSection {
    private val lockFile = File(databaseFile.parentFile, "${databaseFile.name}.startup.lock")
    private val localLock = locks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }

    override fun run(block: () -> Unit) {
        lockFile.parentFile?.mkdirs()
        localLock.lock()
        try {
            FileOutputStream(lockFile, true).channel.use { channel ->
                channel.lock().use { block() }
            }
        } finally {
            localLock.unlock()
        }
    }

    private companion object {
        /** 同进程先串行，避免 overlapping FileLock。 */
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

/** 只创建 SQLCipher Room，且强制打开与双完整性校验后才返回。 */
internal class SqlCipherRoomDatabaseFactory(
    private val context: Context
) {
    fun openAndVerify(
        databaseName: String,
        passphrase: SqlCipherPassphrase
    ): AppDatabase {
        SqlCipherNativeLoader.ensureLoaded()
        val openHelperFactory = passphrase.useBytes(::ClearingSupportOpenHelperFactory)
        val database = buildWithOpenHelperFactoryCleanup(openHelperFactory) {
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .openHelperFactory(openHelperFactory)
                .addMigrations(*AppDatabaseMigrations.ALL)
                .build()
        }
        return try {
            val opened = database.openHelper.writableDatabase
            require(readSingleColumn(opened.query("PRAGMA integrity_check")) == listOf("ok")) {
                "数据库完整性校验失败"
            }
            require(readSingleColumn(opened.query("PRAGMA cipher_integrity_check")).isEmpty()) {
                "SQLCipher 完整性校验失败"
            }
            database
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    private fun readSingleColumn(cursor: android.database.Cursor): List<String> = cursor.use {
        buildList {
            while (it.moveToNext()) add(it.getString(0))
        }
    }
}

/**
 * SQLCipher 4.18 会在连接池创建后续连接时继续读取 Factory 持有的口令。
 * 因此不能在首次 `getWritableDatabase()` 后立即清零；改为在 Room close 时统一清零。
 */
internal class ClearingSupportOpenHelperFactory(
    passphrase: ByteArray,
    delegateFactory: (ByteArray) -> SupportSQLiteOpenHelper.Factory = { retained ->
        SupportOpenHelperFactory(retained, null, true)
    },
) : SupportSQLiteOpenHelper.Factory {
    private val retainedPassphrase = passphrase.copyOf()
    private val cleared = AtomicBoolean(false)
    private val delegate = delegateFactory(retainedPassphrase)

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(!cleared.get()) { "SQLCipher factory 已关闭" }
        val helper = try {
            delegate.create(configuration)
        } catch (failure: Throwable) {
            clear()
            throw failure
        }
        return object : SupportSQLiteOpenHelper by helper {
            override fun close() {
                try {
                    helper.close()
                } finally {
                    clear()
                }
            }
        }
    }

    /** 失败路径与正常 close 共用的幂等清零入口。 */
    fun clear() {
        if (cleared.compareAndSet(false, true)) {
            retainedPassphrase.fill(0)
        }
    }
}

/** Room build 失败时没有 helper 可关闭，必须由 factory 自行清零。 */
internal inline fun <T> buildWithOpenHelperFactoryCleanup(
    factory: ClearingSupportOpenHelperFactory,
    build: () -> T,
): T = try {
    build()
} catch (failure: Throwable) {
    factory.clear()
    throw failure
}

/** 恢复标记和不可读数据库的固定路径隔离；标记内容从不接受任意路径。 */
internal class AndroidDatabaseRecoveryFiles(
    context: Context,
    private val databaseFile: File
) {
    private val recoveryDirectory = File(context.noBackupFilesDir, "database-recovery")
    private val marker = AtomicFile(File(recoveryDirectory, "recovery-state-v1"))
    private val quarantineFile = File(databaseFile.parentFile, "${databaseFile.name}.recovery-quarantine")

    /** 严格读取固定两行标记；损坏标记安全映射为 RecoveryStateCorrupt。 */
    fun readRecoveryReason(): DatabaseRecoveryReason? {
        if (!marker.baseFile.exists()) return null
        return runCatching {
            val lines = marker.readFully().toString(Charsets.UTF_8).lines()
            if (lines.size != 2 || lines[0] != MARKER_MAGIC) {
                DatabaseRecoveryReason.RecoveryStateCorrupt
            } else {
                DatabaseRecoveryReason.valueOf(lines[1])
            }
        }.getOrDefault(DatabaseRecoveryReason.RecoveryStateCorrupt)
    }

    /** 先写标记，再将主文件与 sidecar 原子移到固定隔离路径。 */
    fun enterRecovery(reason: DatabaseRecoveryReason): DatabaseRecoveryReason {
        readRecoveryReason()?.let { return it }
        writeMarker(reason)
        ensureQuarantined()
        return reason
    }

    /** 恢复 marker 已先落盘时，冷启动继续完成尚未结束的固定路径隔离。 */
    fun ensureQuarantined() {
        require(!(databaseFile.exists() && quarantineFile.exists())) {
            "主库与恢复隔离库同时存在"
        }
        if (databaseFile.exists()) {
            atomicMove(databaseFile, quarantineFile)
        }
        SIDECARS.forEach { suffix ->
            val source = File(databaseFile.path + suffix)
            val target = File(quarantineFile.path + suffix)
            if (source.exists()) {
                require(!target.exists()) { "数据库恢复 sidecar 隔离文件已存在" }
                atomicMove(source, target)
            }
        }
        forceDirectoryBestEffort(requireNotNull(databaseFile.parentFile))
    }

    /** 用户已作出恢复/放弃决定且新主库成功冷开后，才删除旧隔离文件。 */
    fun cleanupAfterVerifiedColdOpen(): Boolean = runCatching {
        if (marker.baseFile.exists()) return@runCatching false
        listOf(quarantineFile).plus(SIDECARS.map { File(quarantineFile.path + it) })
            .forEach { file ->
                if (file.exists()) require(file.delete()) { "无法清理旧数据库隔离文件" }
            }
        true
    }.getOrDefault(false)

    /** 恢复 Bootstrap 成功后删除标记；隔离文件延迟到下一次冷开验证。 */
    fun clearMarkerAfterExplicitDecision() {
        marker.delete()
    }

    /** 当前隔离主库，仅供显式恢复/放弃事务使用。 */
    fun quarantineDatabase(): File = quarantineFile

    private fun writeMarker(reason: DatabaseRecoveryReason) {
        recoveryDirectory.mkdirs()
        val output = marker.startWrite()
        try {
            output.write("$MARKER_MAGIC\n${reason.name}".toByteArray(Charsets.UTF_8))
            marker.finishWrite(output)
        } catch (failure: Throwable) {
            marker.failWrite(output)
            throw failure
        }
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("当前文件系统不支持恢复隔离原子移动", unsupported)
        }
    }

    private fun forceDirectoryBestEffort(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private companion object {
        const val MARKER_MAGIC = "DAWN_DATABASE_RECOVERY_V1"
        val SIDECARS = listOf("-wal", "-shm", "-journal")
    }
}
