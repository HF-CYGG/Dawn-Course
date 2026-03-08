package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LastSyncInfo
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.repository.SyncStateRepository
import com.dawncourse.core.domain.repository.TimetableSyncRepository
import javax.inject.Inject

/**
 * 一键更新课表用例
 *
 * 编排调用同步仓库，写入同步状态，向上层返回结构化结果。
 */
class RunTimetableSyncUseCase @Inject constructor(
    private val syncRepository: TimetableSyncRepository,
    private val syncStateRepository: SyncStateRepository
){
    /**
     * 执行一键同步
     */
    suspend operator fun invoke(): TimetableSyncResult {
        val result = syncRepository.syncCurrentSemester()
        val now = System.currentTimeMillis()
        when (result) {
            is TimetableSyncResult.Success -> {
                syncStateRepository.setLastSyncInfo(
                    LastSyncInfo(
                        timestamp = now,
                        success = true,
                        message = result.message
                    )
                )
            }
            is TimetableSyncResult.Failure -> {
                syncStateRepository.setLastSyncInfo(
                    LastSyncInfo(
                        timestamp = now,
                        success = false,
                        message = result.message
                    )
                )
            }
        }
        return result
    }
}
