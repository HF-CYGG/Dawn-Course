package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.LastSyncInfo
import kotlinx.coroutines.flow.Flow

/**
 * 同步状态仓库
 *
 * 负责记录最近一次“自动更新”的结果和时间，用于 UI 展示与诊断。
 * 实现建议使用 DataStore Preferences。
 */
interface SyncStateRepository {
    /**
     * 最近一次同步信息状态流
     */
    val lastSyncInfo: Flow<LastSyncInfo>

    /**
     * 写入最近一次同步信息
     */
    suspend fun setLastSyncInfo(info: LastSyncInfo)
}
