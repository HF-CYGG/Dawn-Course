package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LastSyncInfo
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.repository.SyncStateRepository
import com.dawncourse.core.domain.repository.TimetableSyncRepository
import javax.inject.Inject

class RunTimetableSyncUseCase @Inject constructor(
    private val syncRepository: TimetableSyncRepository,
    private val syncStateRepository: SyncStateRepository
) {
    suspend operator fun invoke(): TimetableSyncResult {
        val result = syncRepository.syncCurrentSemester()
        val info = when (result) {
            is TimetableSyncResult.Success -> LastSyncInfo(
                timestamp = System.currentTimeMillis(),
                success = true,
                message = result.message
            )
            is TimetableSyncResult.Failure -> LastSyncInfo(
                timestamp = System.currentTimeMillis(),
                success = false,
                message = result.message
            )
        }
        syncStateRepository.setLastSyncInfo(info)
        return result
    }
}
