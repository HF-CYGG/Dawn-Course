package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LlmParseTaskResult
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import com.dawncourse.core.domain.repository.LlmParseRepository
import javax.inject.Inject

/**
 * 提交 LLM 异步解析任务用例
 */
class SubmitLlmParseTaskUseCase @Inject constructor(
    private val repository: LlmParseRepository
) {
    /**
     * 提交解析任务
     *
     * @param sample 经受信脱敏器处理并绑定导入会话的样本
     * @param consentAt 用户确认时间戳
     * @param schoolId 用户主动提供的学校标识（可空）
     * @param schoolName 用户主动提供的学校名称（可空）
     * @param schoolSystemType 用户主动提供的教务系统类型（可空）
     */
    suspend operator fun invoke(
        sample: SanitizedDiagnosticSample,
        consentAt: Long,
        schoolId: String? = null,
        schoolName: String? = null,
        schoolSystemType: String? = null,
        sourceUrl: String? = null,
        scriptName: String? = null,
        scriptVersion: Int? = null,
        scriptSource: String? = null,
        failureType: String? = null,
        clientVersion: String? = null,
        issueId: String? = null,
        attemptedParsers: List<String> = emptyList()
    ): LlmParseTaskResult {
        return repository.submitParseTask(
            sample = sample,
            consentAt = consentAt,
            schoolId = schoolId,
            schoolName = schoolName,
            schoolSystemType = schoolSystemType,
            sourceUrl = sourceUrl,
            scriptName = scriptName,
            scriptVersion = scriptVersion,
            scriptSource = scriptSource,
            failureType = failureType,
            clientVersion = clientVersion,
            issueId = issueId,
            attemptedParsers = attemptedParsers
        )
    }
}
