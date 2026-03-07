package com.dawncourse.core.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private val prefsName = "dc_sync_credentials"
    private val keyProvider = "dc_sync.provider"
    private val keyType = "dc_sync.type"
    private val keyUsername = "dc_sync.username"
    private val keySecret = "dc_sync.secret"
    private val keyEndpoint = "dc_sync.endpoint"

    private val _boundProvider = MutableStateFlow<SyncProviderType?>(null)
    override val boundProvider: Flow<SyncProviderType?> = _boundProvider.asStateFlow()

    init {
        // 启动时从加密存储中恢复绑定状态，避免更新后 UI 显示“未绑定”
        _boundProviderEmit(readBoundProviderFromPrefs())
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        context,
        prefsName,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun readBoundProviderFromPrefs(): SyncProviderType? {
        return runCatching {
            val p = prefs()
            val providerName = p.getString(keyProvider, null) ?: return null
            val typeName = p.getString(keyType, null) ?: return null
            val provider = runCatching { SyncProviderType.valueOf(providerName) }.getOrNull() ?: return null
            val type = runCatching { SyncCredentialType.valueOf(typeName) }.getOrNull() ?: return null
            if (type != SyncCredentialType.PASSWORD && type != SyncCredentialType.TOKEN) return null
            provider
        }.getOrNull()
    }

    override suspend fun getCredentials(): SyncCredentials? {
        val p = prefs()
        val providerName = p.getString(keyProvider, null) ?: run {
            _boundProviderEmit(null)
            return null
        }
        val typeName = p.getString(keyType, null) ?: return null
        val username = p.getString(keyUsername, null)
        val secret = p.getString(keySecret, null) ?: return null
        val endpoint = p.getString(keyEndpoint, null)
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
        val p = prefs()
        p.edit()
            .putString(keyProvider, credentials.provider.name)
            .putString(keyType, credentials.type.name)
            .putString(keyUsername, credentials.username)
            .putString(keySecret, credentials.secret)
            .putString(keyEndpoint, credentials.endpointUrl)
            .apply()
        _boundProviderEmit(credentials.provider)
    }

    override suspend fun clearCredentials() {
        val p = prefs()
        p.edit().clear().apply()
        _boundProviderEmit(null)
    }

    private fun _boundProviderEmit(provider: SyncProviderType?) {
        _boundProvider.tryEmit(provider)
    }
}
