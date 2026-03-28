package com.dawncourse.core.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow

/**
 * 凭据仓库实现（加密存储）
 *
 * 使用 Android Keystore + EncryptedSharedPreferences 安全存储用户名/密码或令牌。
 * 仅在必要时读取，避免在内存中长时间持有明文数据。
 */
@Singleton
class CredentialsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialsRepository {

    /** 提供者类型键 */
    private val keyProvider = "dc_sync.provider"
    /** 凭据类型键 */
    private val keyType = "dc_sync.type"
    /** 用户名键 */
    private val keyUsername = "dc_sync.username"
    /** 密钥键（口令/密码） */
    private val keySecret = "dc_sync.secret"
    /** 入口地址键 */
    private val keyEndpoint = "dc_sync.endpoint"
    /** 加密存储的文件名 */
    private val fileName = "dc_sync_credentials.json"
    /** JSON 序列化器 */
    private val gson = Gson()

    private val _boundProvider = MutableStateFlow<SyncProviderType?>(null)
    override val boundProvider: Flow<SyncProviderType?> = _boundProvider.asStateFlow()

    init {
        // 启动时从加密存储中恢复绑定状态，避免更新后 UI 显示“未绑定”
        _boundProviderEmit(readBoundProviderFromFile())
    }

    /**
     * 构建加密文件实例
     *
     * 使用 Android Keystore 生成主密钥，文件写入和读取均使用该密钥加解密。
     */
    private fun encryptedFile(): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val file = File(context.filesDir, fileName)
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * 读取凭据快照
     *
     * 读取失败时会清理损坏文件，避免后续持续报错。
     */
    private fun readSnapshot(): CredentialsSnapshot? {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return null
        return runCatching {
            encryptedFile().openFileInput().use { input ->
                val json = input.bufferedReader().readText()
                gson.fromJson(json, CredentialsSnapshot::class.java)
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    /**
     * 写入凭据快照
     *
     * 仅在保存时写入，避免在内存中长时间保留明文。
     * 注意：EncryptedFile.openFileOutput() 不支持覆盖写入，如果文件已存在会抛出 IOException。
     * 因此在写入前必须先删除旧文件。
     */
    private fun writeSnapshot(snapshot: CredentialsSnapshot) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            file.delete()
        }
        val json = gson.toJson(snapshot)
        encryptedFile().openFileOutput().use { output ->
            output.write(json.toByteArray())
        }
    }

    /**
     * 清空凭据快照文件
     */
    private fun clearSnapshot() {
        File(context.filesDir, fileName).delete()
    }

    /**
     * 读取已绑定的同步提供者
     *
     * 用于启动时同步 UI 状态。
     */
    private fun readBoundProviderFromFile(): SyncProviderType? {
        return runCatching {
            val snapshot = readSnapshot() ?: return null
            val providerName = snapshot.provider ?: return null
            val typeName = snapshot.type ?: return null
            val provider = runCatching { SyncProviderType.valueOf(providerName) }.getOrNull() ?: return null
            val type = runCatching { SyncCredentialType.valueOf(typeName) }.getOrNull() ?: return null
            if (type != SyncCredentialType.PASSWORD && type != SyncCredentialType.TOKEN) return null
            provider
        }.getOrNull()
    }

    override suspend fun getCredentials(): SyncCredentials? {
        val snapshot = readSnapshot()
        val providerName = snapshot?.provider ?: run {
            _boundProviderEmit(null)
            return null
        }
        val typeName = snapshot.type ?: return null
        val username = snapshot.username
        val secret = snapshot.secret ?: return null
        val endpoint = snapshot.endpointUrl
        val provider = runCatching { SyncProviderType.valueOf(providerName) }.getOrNull()
            ?: return null
        val type = runCatching { SyncCredentialType.valueOf(typeName) }.getOrNull()
            ?: return null
        _boundProviderEmit(provider)
        return SyncCredentials(
            provider = provider,
            type = type,
            username = username,
            secret = secret,
            endpointUrl = endpoint
        )
    }

    override suspend fun saveCredentials(credentials: SyncCredentials) {
        writeSnapshot(
            CredentialsSnapshot(
                provider = credentials.provider.name,
                type = credentials.type.name,
                username = credentials.username,
                secret = credentials.secret,
                endpointUrl = credentials.endpointUrl
            )
        )
        _boundProviderEmit(credentials.provider)
    }

    override suspend fun clearCredentials() {
        clearSnapshot()
        _boundProviderEmit(null)
    }

    /**
     * 推送当前绑定提供者的 UI 状态
     */
    private fun _boundProviderEmit(provider: SyncProviderType?) {
        _boundProvider.tryEmit(provider)
    }

    /**
     * 本地凭据快照结构
     *
     * 仅用于序列化/反序列化，不对外暴露。
     */
    private data class CredentialsSnapshot(
        val provider: String? = null,
        val type: String? = null,
        val username: String? = null,
        val secret: String? = null,
        val endpointUrl: String? = null
    )
}
