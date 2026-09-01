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
                deleteIfPresent(profileFile(profile.uuid), "无法删除 Profile 凭据")
            }
            recoverBackupsLocked()
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
            if (ProfileCredentialFileNamer.isManagedFile(file.name) && file.name !in validNames) {
                deleteIfPresent(file, "无法清理孤立 Profile 凭据")
            }
            if (file.name.endsWith(BACKUP_SUFFIX)) {
                val originalName = file.name.removeSuffix(BACKUP_SUFFIX)
                if (ProfileCredentialFileNamer.isManagedFile(originalName) && originalName !in validNames) {
                    deleteIfPresent(file, "无法清理孤立凭据备份")
                }
            }
        }
    }

    /** 崩溃发生在覆盖窗口时，以旧密文备份恢复；主文件存在时清理残留备份。 */
    private suspend fun recoverBackupsLocked() {
        profileDao.getAllProfilesOnce().forEach { profile ->
            val file = profileFile(profile.uuid)
            val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
            if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
                error("无法恢复中断的凭据覆盖")
            }
            if (file.exists() && backup.exists()) deleteIfPresent(backup, "无法清理凭据备份")
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
    }
}
