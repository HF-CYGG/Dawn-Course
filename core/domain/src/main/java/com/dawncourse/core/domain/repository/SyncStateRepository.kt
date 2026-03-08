package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.LastSyncInfo
import kotlinx.coroutines.flow.Flow

interface SyncStateRepository {
    val lastSyncInfo: Flow<LastSyncInfo>
    suspend fun setLastSyncInfo(info: LastSyncInfo)
}
