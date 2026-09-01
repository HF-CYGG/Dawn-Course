package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.DiagnosticCleanupReport
import com.dawncourse.core.domain.model.DiagnosticSampleMetadata
import com.dawncourse.core.domain.model.RawDiagnosticRetentionAuthorization
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample

/** 导入诊断副本的最小安全存储契约。 */
interface DiagnosticSampleRepository {
    /** 保存脱敏副本；这是唯一默认允许的落盘入口。 */
    suspend fun saveSanitized(sample: SanitizedDiagnosticSample): Result<DiagnosticSampleMetadata>

    /** 仅在持有用户单次明确授权时短期加密保存原文。 */
    suspend fun saveRaw(
        rawContent: String,
        authorization: RawDiagnosticRetentionAuthorization
    ): Result<DiagnosticSampleMetadata>

    /** 启动或进入导入流程时逐个清理过期、损坏副本。 */
    suspend fun cleanupExpired(): DiagnosticCleanupReport

    /** 离开诊断流程时清除指定会话的所有短期原文。 */
    suspend fun clearRawForSession(importSessionId: String): DiagnosticCleanupReport
}
