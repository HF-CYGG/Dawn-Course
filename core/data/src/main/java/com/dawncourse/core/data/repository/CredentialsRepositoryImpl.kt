package com.dawncourse.core.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.dao.TimetableProfileDao
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Profile UUID 经摘要后形成固定安全文件名，调用参数永不直接进入路径。 */
internal object ProfileCredentialFileNamer {
    private const val PREFIX = "dc_sync_profile_"
    private const val SUFFIX = ".json"

    fun fileName(profileUuid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(profileUuid.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } + SUFFIX
    }

    fun isManagedFile(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)
}

/** 将主文件及其崩溃备份/恢复临时文件统一映射到所属的受管凭据主文件。 */
internal fun credentialOwnerFileName(name: String): String? {
    if (ProfileCredentialFileNamer.isManagedFile(name)) return name
    return listOf(".bak", ".restore")
        .firstNotNullOfOrNull { suffix ->
            name.takeIf { it.endsWith(suffix) }
                ?.removeSuffix(suffix)
                ?.takeIf(ProfileCredentialFileNamer::isManagedFile)
        }
}

/** 崩溃备份只在新主文件可完整解密并解析后才能删除。 */
internal fun recoverCredentialBackupIfNeeded(
    mainExists: () -> Boolean,
    backupExists: () -> Boolean,
    mainIsValid: () -> Boolean,
    restoreBackup: () -> Unit,
    discardBackup: () -> Unit,
) {
    if (!backupExists()) return
    if (mainExists() && mainIsValid()) {
        discardBackup()
    } else {
        restoreBackup()
    }
}

/** 清除前先收敛中断覆盖，再先删备份后删主文件，避免失败后复活。 */
internal suspend fun clearCredentialFiles(
    recoverBackups: suspend () -> Unit,
    deleteRestoreStaging: () -> Unit,
    deleteMain: () -> Unit,
    deleteBackup: () -> Unit,
) {
    recoverBackups()
    deleteRestoreStaging()
    deleteBackup()
    deleteMain()
}

/** 原备份在新主文件完整验证前始终保留。 */
internal fun restoreCredentialBackupSafely(
    stageBackupCopy: () -> Unit,
    replaceMainAtomically: () -> Unit,
    mainIsValid: () -> Boolean,
    discardBackup: () -> Unit,
) {
    stageBackupCopy()
    replaceMainAtomically()
    check(mainIsValid()) { "恢复后的凭据备份未通过回读验证" }
    discardBackup()
}

/** 以同一 MasterKey 为每个 Profile 独立加密凭据，并懒迁移单文件旧格式。 */
@Singleton
class CredentialsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileDao: TimetableProfileDao,
    private val semesterDao: SemesterDao,
    private val legacySelectionStore: SemesterSelectionStore,
) : CredentialsRepository {
    private val gson = Gson()
    private val mutex = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    override suspend fun getCredentials(profileId: Long): SyncCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareStorageLocked()
            val profile = profileDao.getProfileById(profileId) ?: return@withLock null
            readSnapshot(profileFile(profile.uuid))?.toDomain()
        }
    }

    override suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareStorageLocked()
            val profile = profileDao.getProfileById(profileId)
                ?: throw IllegalArgumentException("Profile 不存在")
            writeSnapshot(profileFile(profile.uuid), CredentialsSnapshot.from(credentials))
            changes.tryEmit(Unit)
            Unit
        }
    }

    override suspend fun copyCredentials(sourceProfileId: Long, targetProfileId: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareStorageLocked()
            val source = profileDao.getProfileById(sourceProfileId)
                ?: throw IllegalArgumentException("来源 Profile 不存在")
            val target = profileDao.getProfileById(targetProfileId)
                ?: throw IllegalArgumentException("目标 Profile 不存在")
            val snapshot = readSnapshot(profileFile(source.uuid)) ?: return@withLock
            require(snapshot.toDomain() != null) { "来源凭据不可解析" }
            writeSnapshot(profileFile(target.uuid), snapshot)
            changes.tryEmit(Unit)
            Unit
        }
    }

    override suspend fun clearCredentials(profileId: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = profileDao.getProfileById(profileId)
            if (profile != null) {
                migrateLegacyLocked()
                val file = profileFile(profile.uuid)
                val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
                val restoreStaging = File(file.parentFile, file.name + RESTORE_STAGING_SUFFIX)
                clearCredentialFiles(
                    recoverBackups = ::recoverBackupsLocked,
                    deleteRestoreStaging = {
                        deleteIfPresent(restoreStaging, "无法删除 Profile 凭据恢复临时文件")
                    },
                    deleteMain = { deleteIfPresent(file, "无法删除 Profile 凭据") },
                    deleteBackup = { deleteIfPresent(backup, "无法删除 Profile 凭据备份") },
                )
            } else {
                recoverBackupsLocked()
            }
            cleanupOrphansLocked()
            changes.tryEmit(Unit)
            Unit
        }
    }

    override fun observeBoundProvider(profileId: Long): Flow<SyncProviderType?> = changes
        .onStart { emit(Unit) }
        .mapLatest { getCredentials(profileId)?.provider }
        .distinctUntilChanged()

    private suspend fun prepareStorageLocked() {
        recoverBackupsLocked()
        migrateLegacyLocked()
        cleanupOrphansLocked()
    }

    /** 旧文件先复制、回读等值验证，任一步失败都保留旧密文。 */
    private suspend fun migrateLegacyLocked() {
        val legacy = File(context.filesDir, LEGACY_FILE_NAME)
        if (!legacy.exists()) return
        val snapshot = readSnapshot(legacy) ?: return
        if (snapshot.toDomain() == null) return
        val profiles = profileDao.getAllProfilesOnce()
        val selectedProfileId = legacySelectionStore.selectedSemesterId.first()
            ?.let { semesterDao.getSemesterById(it) }?.profileId
        val target = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull() ?: return
        val targetFile = profileFile(target.uuid)
        runCatching {
            writeSnapshot(targetFile, snapshot)
            check(readSnapshot(targetFile) == snapshot) { "凭据迁移回读校验失败" }
        }.onSuccess {
            deleteIfPresent(legacy, "无法删除已迁移的旧凭据")
            changes.tryEmit(Unit)
        }
    }

    private suspend fun cleanupOrphansLocked() {
        val validNames = profileDao.getAllProfilesOnce().mapTo(hashSetOf()) {
            ProfileCredentialFileNamer.fileName(it.uuid)
        }
        context.filesDir.listFiles().orEmpty().forEach { file ->
            val ownerName = credentialOwnerFileName(file.name)
            if (ownerName != null && ownerName !in validNames) {
                deleteIfPresent(file, "无法清理孤立 Profile 凭据文件")
            }
        }
    }

    /** 崩溃发生在覆盖窗口时，只有新主文件验证成功才清理旧密文备份。 */
    private suspend fun recoverBackupsLocked() {
        profileDao.getAllProfilesOnce().forEach { profile ->
            val file = profileFile(profile.uuid)
            val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
            val restoreStaging = File(file.parentFile, file.name + RESTORE_STAGING_SUFFIX)
            deleteIfPresent(restoreStaging, "无法清理中断的凭据恢复临时文件")
            recoverCredentialBackupIfNeeded(
                mainExists = file::exists,
                backupExists = backup::exists,
                mainIsValid = { readSnapshot(file)?.toDomain() != null },
                restoreBackup = { restoreCredentialBackup(file, backup) },
                discardBackup = { deleteIfPresent(backup, "无法清理凭据备份") },
            )
        }
    }

    /** 复制旧密文并原子换入主路径；只有回读验证成功才删除 .bak。 */
    private fun restoreCredentialBackup(file: File, backup: File) {
        check(backup.exists()) { "待恢复的凭据备份不存在" }
        val staging = File(file.parentFile, file.name + RESTORE_STAGING_SUFFIX)
        deleteIfPresent(staging, "无法清理凭据恢复临时文件")
        try {
            restoreCredentialBackupSafely(
                stageBackupCopy = {
                    Files.copy(
                        backup.toPath(),
                        staging.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                },
                replaceMainAtomically = {
                    Files.move(
                        staging.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                },
                mainIsValid = { readSnapshot(file)?.toDomain() != null },
                discardBackup = { deleteIfPresent(backup, "无法清理已恢复的凭据备份") },
            )
        } catch (failure: Exception) {
            runCatching { deleteIfPresent(staging, "无法清理失败的凭据恢复临时文件") }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun profileFile(profileUuid: String): File =
        File(context.filesDir, ProfileCredentialFileNamer.fileName(profileUuid))

    /** 读取失败不删除源文件，允许后续恢复密钥或人工诊断。 */
    private fun readSnapshot(file: File): CredentialsSnapshot? {
        if (!file.exists()) return null
        return runCatching {
            encryptedFile(file).openFileInput().use { input ->
                gson.fromJson(input.bufferedReader().readText(), CredentialsSnapshot::class.java)
            }
        }.getOrNull()
    }

    /** EncryptedFile 不支持覆盖；失败时把原密文恢复到原路径。 */
    private fun writeSnapshot(file: File, snapshot: CredentialsSnapshot) {
        val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
        deleteIfPresent(backup, "无法清理旧凭据备份")
        if (file.exists() && !file.renameTo(backup)) error("无法保护旧凭据")
        try {
            encryptedFile(file).openFileOutput().use { output ->
                output.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            }
            check(readSnapshot(file) == snapshot) { "凭据写入回读校验失败" }
            deleteIfPresent(backup, "无法提交凭据覆盖")
        } catch (failure: Exception) {
            runCatching { deleteIfPresent(file, "无法移除失败的凭据写入") }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            if (backup.exists() && !backup.renameTo(file)) {
                failure.addSuppressed(IllegalStateException("无法恢复旧凭据备份"))
            }
            throw failure
        }
    }

    private fun deleteIfPresent(file: File, message: String) {
        if (file.exists() && !file.delete()) error(message)
    }

    private fun encryptedFile(file: File): EncryptedFile = EncryptedFile.Builder(
        context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private data class CredentialsSnapshot(
        val provider: String? = null,
        val type: String? = null,
        val username: String? = null,
        val secret: String? = null,
        val endpointUrl: String? = null,
    ) {
        fun toDomain(): SyncCredentials? {
            val parsedProvider = provider?.let { runCatching { SyncProviderType.valueOf(it) }.getOrNull() } ?: return null
            val parsedType = type?.let { runCatching { SyncCredentialType.valueOf(it) }.getOrNull() } ?: return null
            val parsedSecret = secret ?: return null
            return SyncCredentials(parsedProvider, parsedType, username, parsedSecret, endpointUrl)
        }

        companion object {
            fun from(credentials: SyncCredentials) = CredentialsSnapshot(
                provider = credentials.provider.name,
                type = credentials.type.name,
                username = credentials.username,
                secret = credentials.secret,
                endpointUrl = credentials.endpointUrl,
            )
        }
    }

    private companion object {
        const val LEGACY_FILE_NAME = "dc_sync_credentials.json"
        const val BACKUP_SUFFIX = ".bak"
        const val RESTORE_STAGING_SUFFIX = ".restore"
    }
}
