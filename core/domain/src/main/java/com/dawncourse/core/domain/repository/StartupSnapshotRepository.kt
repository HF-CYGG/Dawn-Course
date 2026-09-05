package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.StartupSnapshot

/** 启动快照存储的领域边界；实现不得在读取路径解析 Room 或 DAO。 */
interface StartupSnapshotRepository {
    /** 读取已加密快照；任意异常、损坏或不匹配必须表现为 [StartupSnapshotReadResult.Missing]。 */
    suspend fun read(
        expectedProfileId: Long?,
        nowEpochMillis: Long,
        expectedZoneId: String,
    ): StartupSnapshotReadResult

    /** 用完整的新快照原子替换旧快照；写失败只能影响加速，不影响实时数据。 */
    suspend fun replace(snapshot: StartupSnapshot): Boolean

    /** 尽力删除已失效加速文件。 */
    suspend fun invalidate()
}

sealed interface StartupSnapshotReadResult {
    data class Available(val snapshot: StartupSnapshot) : StartupSnapshotReadResult
    data object Missing : StartupSnapshotReadResult
}
