package com.dawncourse.core.data.local.startup

/** 数据库文件的低层观察结果；不透明文件不能在未验证密钥前被当作新库。 */
sealed interface DatabaseFileInspection {
    /** 数据库主文件不存在。 */
    data object NoDatabase : DatabaseFileInspection

    /** 文件头明确为未加密 SQLite 数据库。 */
    data object PlaintextSqlite : DatabaseFileInspection

    /** 文件存在且不是明文 SQLite，可能是 SQLCipher 数据库，也可能是未知数据。 */
    data object OpaqueData : DatabaseFileInspection

    /** 文件为空、截断或无法完成最低限度的文件检查。 */
    data object CorruptOrUnknown : DatabaseFileInspection
}

/** 数据库启动阶段对外暴露的稳定状态。 */
sealed interface DatabaseStartupState {
    /** 没有历史数据库，可以初始化新的加密数据库。 */
    data object NoDatabase : DatabaseStartupState

    /** 已确认存在明文 SQLite 数据库，需要原子加密换入。 */
    data object PlaintextDatabase : DatabaseStartupState

    /** 已确认存在可用密钥信封的不透明数据库，交由 SQLCipher 打开验证。 */
    data object EncryptedDatabase : DatabaseStartupState

    /** 数据库存在，但密钥信封不存在、失效或无法解封。 */
    data object DatabasePresentButKeyMissingOrInvalid : DatabaseStartupState

    /** 文件损坏或类型无法可靠判定，必须进入恢复流程。 */
    data object CorruptOrUnknown : DatabaseStartupState
}

/** 启动层应执行的后续动作；携带的口令由调用方在使用后关闭。 */
sealed interface DatabaseStartupPlan {
    /** 创建全新的加密数据库。 */
    data class CreateNewEncryptedDatabase(
        val passphrase: SqlCipherPassphrase
    ) : DatabaseStartupPlan

    /** 将已确认的明文数据库以原子换入方式转换为加密数据库。 */
    data class EncryptPlaintextDatabase(
        val passphrase: SqlCipherPassphrase
    ) : DatabaseStartupPlan

    /** 使用 SQLCipher 打开已加密数据库。 */
    data class OpenEncryptedDatabase(
        val passphrase: SqlCipherPassphrase
    ) : DatabaseStartupPlan

    /** 不允许继续打开或创建数据库，必须展示可见恢复入口。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason
    ) : DatabaseStartupPlan
}

/** RecoveryRequired 的可审计原因，不携带底层异常或敏感信息。 */
enum class DatabaseRecoveryReason {
    /** 已存在数据库对应的密钥信封不可用。 */
    KeyMissingOrInvalid,

    /** 数据库文件不可安全识别。 */
    CorruptOrUnknown,

    /** 新数据库初始化前发现已有不可用信封，拒绝覆盖。 */
    ExistingEnvelopeCannotBeReplaced,

    /** 新密钥或信封无法安全创建。 */
    KeyProvisioningFailed,

    /** 上次迁移 journal 无法安全恢复。 */
    CrashRecoveryFailed,

    /** 明文加密换入或换入后验证失败。 */
    MigrationFailed,

    /** SQLCipher/Room 无法使用既有口令完整打开数据库。 */
    DatabaseOpenFailed,

    /** 持久化恢复标记本身损坏或与文件状态冲突。 */
    RecoveryStateCorrupt,

    /** 用户选择的本地或 WebDAV 备份未通过验证或无法原子恢复。 */
    RestoreFailed,

    /** Ready 后双完整性校验失败，必须重启后物理隔离。 */
    IntegrityVerificationFailed,
}

/** 数据库文件检查器，生产实现与 JVM 测试实现均可替换。 */
fun interface DatabaseFileInspector {
    /** 返回不读取 Room 的最低风险文件检查结果。 */
    fun inspect(): DatabaseFileInspection
}

/**
 * 将文件观察结果和密钥信封状态映射为启动状态。
 *
 * 这里刻意不把 [DatabaseFileInspection.OpaqueData] 直接视为新库：只有现有信封
 * 成功解封后才允许交给 SQLCipher 打开，失败时必须 RecoveryRequired。
 */
internal object DatabaseStartupStateResolver {
    /** 解析不会创建、删除或覆盖任何密钥材料。 */
    fun resolve(
        inspection: DatabaseFileInspection,
        existingPassphrase: ExistingPassphraseResult?
    ): DatabaseStartupState = when (inspection) {
        DatabaseFileInspection.NoDatabase -> DatabaseStartupState.NoDatabase
        DatabaseFileInspection.PlaintextSqlite -> when (existingPassphrase) {
            is ExistingPassphraseResult.Invalid -> {
                DatabaseStartupState.DatabasePresentButKeyMissingOrInvalid
            }

            else -> DatabaseStartupState.PlaintextDatabase
        }

        DatabaseFileInspection.OpaqueData -> when (existingPassphrase) {
            is ExistingPassphraseResult.Available -> DatabaseStartupState.EncryptedDatabase
            else -> DatabaseStartupState.DatabasePresentButKeyMissingOrInvalid
        }

        DatabaseFileInspection.CorruptOrUnknown -> DatabaseStartupState.CorruptOrUnknown
    }
}

/**
 * SQLCipher 启动前的 fail-closed 协调器。
 *
 * 此类不接触 Room，也不执行数据库复制；后续 DataModule 接线必须只在收到非恢复计划时
 * 创建数据库，并且在 [SqlCipherPassphrase.close] 前完成 SQLCipher 打开或原子换入。
 */
class DatabaseStartupCoordinator(
    private val fileInspector: DatabaseFileInspector,
    private val envelopeStore: DatabasePassphraseEnvelopeStore
) {
    /** 生成一次启动计划；任何已有库的密钥异常均停止在 RecoveryRequired。 */
    fun prepare(): DatabaseStartupPlan {
        val inspection = try {
            fileInspector.inspect()
        } catch (_: Throwable) {
            return DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.CorruptOrUnknown)
        }
        return when (inspection) {
            DatabaseFileInspection.NoDatabase -> prepareNewDatabase()
            DatabaseFileInspection.PlaintextSqlite -> preparePlaintextMigration()
            DatabaseFileInspection.OpaqueData -> prepareEncryptedDatabase()
            DatabaseFileInspection.CorruptOrUnknown -> {
                DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.CorruptOrUnknown)
            }
        }
    }

    /** 无数据库时允许复用健康信封或创建首个信封，但永不覆盖异常信封。 */
    private fun prepareNewDatabase(): DatabaseStartupPlan = when (val existing = loadExistingSafely()) {
        is ExistingPassphraseResult.Available -> {
            DatabaseStartupPlan.CreateNewEncryptedDatabase(existing.passphrase)
        }

        ExistingPassphraseResult.Missing -> provisionNewPassphrase(
            onAvailable = { passphrase -> DatabaseStartupPlan.CreateNewEncryptedDatabase(passphrase) }
        )

        is ExistingPassphraseResult.Invalid -> {
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.ExistingEnvelopeCannotBeReplaced)
        }
    }

    /** 明文库在没有历史信封时可以创建首个信封，随后由调用方做原子加密换入。 */
    private fun preparePlaintextMigration(): DatabaseStartupPlan = when (val existing = loadExistingSafely()) {
        is ExistingPassphraseResult.Available -> {
            DatabaseStartupPlan.EncryptPlaintextDatabase(existing.passphrase)
        }

        ExistingPassphraseResult.Missing -> provisionNewPassphrase(
            onAvailable = { passphrase -> DatabaseStartupPlan.EncryptPlaintextDatabase(passphrase) }
        )

        is ExistingPassphraseResult.Invalid -> {
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid)
        }
    }

    /** 不透明库只允许使用已成功解封的既有口令打开。 */
    private fun prepareEncryptedDatabase(): DatabaseStartupPlan = when (val existing = loadExistingSafely()) {
        is ExistingPassphraseResult.Available -> {
            DatabaseStartupPlan.OpenEncryptedDatabase(existing.passphrase)
        }

        ExistingPassphraseResult.Missing,
        is ExistingPassphraseResult.Invalid -> {
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid)
        }
    }

    /** 将创建结果统一映射为计划；创建竞争或失败都不能覆盖旧信封。 */
    private fun provisionNewPassphrase(
        onAvailable: (SqlCipherPassphrase) -> DatabaseStartupPlan
    ): DatabaseStartupPlan = when (val created = createNewSafely()) {
        is NewPassphraseResult.Available -> onAvailable(created.passphrase)
        NewPassphraseResult.ExistingEnvelope -> {
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.ExistingEnvelopeCannotBeReplaced)
        }

        is NewPassphraseResult.Failed -> {
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyProvisioningFailed)
        }
    }

    /** 外部存储适配器异常一律按无效信封处理，避免启动阶段崩溃或静默重建。 */
    private fun loadExistingSafely(): ExistingPassphraseResult = try {
        envelopeStore.loadExisting()
    } catch (_: Throwable) {
        ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.InvalidEnvelopeFormat)
    }

    /** 首次创建失败不能逃逸到启动页，也不能让调用方改走裸 Room 新建路径。 */
    private fun createNewSafely(): NewPassphraseResult = try {
        envelopeStore.createNew()
    } catch (_: Throwable) {
        NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
    }
}
