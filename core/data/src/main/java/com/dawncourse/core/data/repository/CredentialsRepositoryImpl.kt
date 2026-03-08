package com.dawncourse.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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
    private val _boundProvider = MutableStateFlow(readBoundProviderFromPrefs())
    private val boundProvider = _boundProvider.asStateFlow()

    override fun getBoundProvider(): Flow<SyncProviderType?> {
        return boundProvider
    }

    override suspend fun getCredentials(): SyncCredentials? {
        val prefs = prefs()
        val providerName = prefs.getString(keyProvider, null) ?: return null
        val typeName = prefs.getString(keyType, null) ?: return null
        val provider = runCatching { SyncProviderType.valueOf(providerName) }.getOrNull() ?: return null
        val type = runCatching { SyncCredentialType.valueOf(typeName) }.getOrNull() ?: return null
        val username = prefs.getString(keyUsername, "") ?: ""
        val secret = prefs.getString(keySecret, "") ?: ""
        val endpoint = prefs.getString(keyEndpoint, "") ?: ""
        val isValid = when (type) {
            SyncCredentialType.PASSWORD -> username.isNotBlank() && secret.isNotBlank()
            SyncCredentialType.TOKEN -> secret.isNotBlank()
        }
        if (!isValid) return null
        return SyncCredentials(
            provider = provider,
            type = type,
            username = username,
            secret = secret,
            endpointUrl = endpoint
        )
    }

    override suspend fun saveCredentials(credentials: SyncCredentials) {
        prefs().edit()
            .putString(keyProvider, credentials.provider.name)
            .putString(keyType, credentials.type.name)
            .putString(keyUsername, credentials.username)
            .putString(keySecret, credentials.secret)
            .putString(keyEndpoint, credentials.endpointUrl)
            .apply()
        _boundProviderEmit(credentials.provider)
    }

    override suspend fun clearCredentials() {
        prefs().edit().clear().apply()
        _boundProviderEmit(null)
    }

    private fun prefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun readBoundProviderFromPrefs(): SyncProviderType? {
        val prefs = prefs()
        val providerName = prefs.getString(keyProvider, null) ?: return null
        val typeName = prefs.getString(keyType, null) ?: return null
        val provider = runCatching { SyncProviderType.valueOf(providerName) }.getOrNull() ?: return null
        val type = runCatching { SyncCredentialType.valueOf(typeName) }.getOrNull() ?: return null
        val username = prefs.getString(keyUsername, "") ?: ""
        val secret = prefs.getString(keySecret, "") ?: ""
        return when (type) {
            SyncCredentialType.PASSWORD -> if (username.isNotBlank() && secret.isNotBlank()) provider else null
            SyncCredentialType.TOKEN -> if (secret.isNotBlank()) provider else null
        }
    }

    private fun _boundProviderEmit(provider: SyncProviderType?) {
        _boundProvider.value = provider
    }
}
