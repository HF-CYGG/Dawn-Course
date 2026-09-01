package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import com.dawncourse.core.domain.repository.SyncSourceBindingRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 将 Feature 的来源绑定请求收敛到 Profile 选择锁。 */
@Singleton
class SyncSourceBindingRepositoryImpl @Inject constructor(
    private val profileSelectionCoordinator: ProfileSelectionCoordinator,
) : SyncSourceBindingRepository {
    override suspend fun ensureIfStillActive(
        profileId: Long,
        semesterId: Long,
        provider: SyncProviderType,
    ): String? = profileSelectionCoordinator.ensureSourceBindingIfStillActive(
        profileId = profileId,
        semesterId = semesterId,
        provider = provider,
    )

    override suspend fun saveCredentialsAndRebindIfActive(
        profileId: Long,
        credentials: SyncCredentials,
    ): CredentialBindingMutationResult = profileSelectionCoordinator.saveCredentialsAndRebindIfActive(
        profileId = profileId,
        credentials = credentials,
    )

    override suspend fun clearCredentialsAndUnbindIfActive(
        profileId: Long,
    ): CredentialBindingMutationResult = profileSelectionCoordinator.clearCredentialsAndUnbindIfActive(profileId)
}
