package com.dawncourse.core.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dawncourse.core.domain.model.WebDavCredentials
import com.dawncourse.core.domain.repository.WebDavCredentialsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WebDAV 账号仓库实现
 *
 * 使用 EncryptedSharedPreferences 将 WebDAV 的服务器地址、账号与密码加密存储，
 * 同时通过 Flow 对外暴露当前账号状态。
 */
@Singleton
class WebDavCredentialsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WebDavCredentialsRepository {

    /** SharedPreferences 文件名 */
    private val prefsName = "dc_webdav_credentials"
    /** 服务器地址键 */
    private val keyServerUrl = "dc_webdav.server_url"
    /** 账号键 */
    private val keyUsername = "dc_webdav.username"
    /** 密码键 */
    private val keyPassword = "dc_webdav.password"

    /** 内部可变 Flow，用于推送账号变更 */
    private val _credentials = MutableStateFlow<WebDavCredentials?>(null)
    /** 对外只读 Flow */
    override val credentials: Flow<WebDavCredentials?> = _credentials.asStateFlow()

    init {
        // 初始化时加载已有账号，避免 UI 首次订阅为空
        _credentials.tryEmit(readCredentials())
    }

    /**
     * 获取加密的 SharedPreferences 实例
     */
    private fun prefs() = EncryptedSharedPreferences.create(
        context,
        prefsName,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * 从加密存储中读取账号信息
     */
    private fun readCredentials(): WebDavCredentials? {
        return runCatching {
            val p = prefs()
            val url = p.getString(keyServerUrl, null) ?: return null
            val username = p.getString(keyUsername, null) ?: return null
            val password = p.getString(keyPassword, null) ?: return null
            WebDavCredentials(serverUrl = url, username = username, password = password)
        }.getOrNull()
    }

    /**
     * 读取一次账号信息并同步到 Flow
     */
    override suspend fun getCredentials(): WebDavCredentials? {
        val value = readCredentials()
        _credentials.tryEmit(value)
        return value
    }

    /**
     * 保存账号信息并更新 Flow
     */
    override suspend fun saveCredentials(credentials: WebDavCredentials) {
        val p = prefs()
        p.edit()
            .putString(keyServerUrl, credentials.serverUrl)
            .putString(keyUsername, credentials.username)
            .putString(keyPassword, credentials.password)
            .apply()
        _credentials.tryEmit(credentials)
    }

    /**
     * 清空账号信息并更新 Flow
     */
    override suspend fun clearCredentials() {
        prefs().edit().clear().apply()
        _credentials.tryEmit(null)
    }
}
