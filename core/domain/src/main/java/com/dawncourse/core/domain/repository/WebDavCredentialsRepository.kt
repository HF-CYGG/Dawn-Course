package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.WebDavCredentials
import kotlinx.coroutines.flow.Flow

/**
 * WebDAV 账号仓库接口
 *
 * 负责账号信息的读取、保存与清除。
 */
interface WebDavCredentialsRepository {
    /**
     * 账号信息流
     *
     * 订阅后可实时获取账号是否已配置的状态。
     */
    val credentials: Flow<WebDavCredentials?>

    /**
     * 获取当前账号信息
     */
    suspend fun getCredentials(): WebDavCredentials?

    /**
     * 保存账号信息
     */
    suspend fun saveCredentials(credentials: WebDavCredentials)

    /**
     * 清除账号信息
     */
    suspend fun clearCredentials()
}
