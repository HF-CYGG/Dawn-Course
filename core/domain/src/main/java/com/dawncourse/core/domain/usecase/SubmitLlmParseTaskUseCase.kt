package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LlmParseTaskResult
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
     * @param content 已脱敏的 HTML/文本内容
     * @param consent 用户是否明确同意上传
     * @param consentAt 用户确认时间戳
     * @param schoolId 用户主动提供的学校标识（可空）
     * @param schoolName 用户主动提供的学校名称（可空）
     * @param schoolSystemType 用户主动提供的教务系统类型（可空）
     */
    suspend operator fun invoke(
        content: String,
        consent: Boolean,
        consentAt: Long,
        schoolId: String? = null,
        schoolName: String? = null,
        schoolSystemType: String? = null,
        sourceUrl: String? = null
    ): LlmParseTaskResult {
        return repository.submitParseTask(
            content = content,
            consent = consent,
            consentAt = consentAt,
            schoolId = schoolId,
            schoolName = schoolName,
            schoolSystemType = schoolSystemType,
            sourceUrl = sourceUrl
        )
    }
}
