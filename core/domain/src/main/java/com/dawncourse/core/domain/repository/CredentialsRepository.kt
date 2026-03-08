package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import kotlinx.coroutines.flow.Flow

interface CredentialsRepository {
    suspend fun getCredentials(): SyncCredentials?

    suspend fun saveCredentials(credentials: SyncCredentials)

    suspend fun clearCredentials()

    fun getBoundProvider(): Flow<SyncProviderType?>
}
