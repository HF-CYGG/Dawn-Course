package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType

/** 凭据文件与 Room 来源绑定的协调写入结果。 */
sealed interface CredentialBindingMutationResult {
    data class Success(val sourceBindingId: String?) : CredentialBindingMutationResult
    data class Rejected(val reason: String) : CredentialBindingMutationResult
    data class Inconsistent(val reason: String) : CredentialBindingMutationResult
}

/** 自动更新来源绑定的领域入口，供 Feature 在不依赖 data 实现的前提下固定同步目标。 */
interface SyncSourceBindingRepository {
    /** 仅当 profile/semester 仍是活动目标时创建或返回稳定绑定 ID。 */
    suspend fun ensureIfStillActive(
        profileId: Long,
        semesterId: Long,
        provider: SyncProviderType,
    ): String?

    /** 保存活动 Profile 凭据，并在同一选择锁内建立或显式重绑来源。 */
    suspend fun saveCredentialsAndRebindIfActive(
        profileId: Long,
        credentials: SyncCredentials,
    ): CredentialBindingMutationResult

    /** 清除活动 Profile 凭据并立即作废其在途同步绑定。 */
    suspend fun clearCredentialsAndUnbindIfActive(
        profileId: Long,
    ): CredentialBindingMutationResult
}
