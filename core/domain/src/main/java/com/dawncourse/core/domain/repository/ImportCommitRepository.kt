package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.ImportCommitImpact
import com.dawncourse.core.domain.model.ImportCommitRequest
import com.dawncourse.core.domain.model.ImportCommitResult

/** 导入候选写入 Operational Model 的唯一事务入口。 */
interface ImportCommitRepository {
    /** 为覆盖操作读取精确影响；目标无效时返回拒绝结果。 */
    suspend fun preview(request: ImportCommitRequest): Result<ImportCommitImpact>

    /** 校验目标后单次事务写入 Profile、Semester 与 Course。 */
    suspend fun commit(request: ImportCommitRequest): ImportCommitResult
}
