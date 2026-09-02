package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import kotlinx.coroutines.flow.Flow

/**
 * 凭据仓库（安全存储）
 *
 * 负责以安全方式存储/读取“自动更新”所需的认证信息（用户名+密码或令牌）。
 * 实现层应基于 Android Keystore 为每个 Profile 独立加密，禁止跨 Profile 共享明文快照。
 */
interface CredentialsRepository {
    /**
     * 读取当前已绑定的凭据信息
     *
     * @return [SyncCredentials] 或 null（未绑定）
     */
    suspend fun getCredentials(profileId: Long): SyncCredentials?

    /**
     * 保存/更新凭据（覆盖写）
     *
     * @param credentials 同步凭据
     */
    suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials)

    /** 使用同一主密钥解密来源后，为目标 Profile 重新加密写入。 */
    suspend fun copyCredentials(sourceProfileId: Long, targetProfileId: Long)

    /**
     * 清除已绑定凭据
     */
    suspend fun clearCredentials(profileId: Long)

    /**
     * 监听当前绑定的同步提供者（用于 UI 显示不同入口文案）
     */
    fun observeBoundProvider(profileId: Long): Flow<SyncProviderType?>
}
