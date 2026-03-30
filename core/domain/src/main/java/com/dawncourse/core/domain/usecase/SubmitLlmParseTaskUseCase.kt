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
     */
    suspend operator fun invoke(content: String): LlmParseTaskResult {
        return repository.submitParseTask(content)
    }
}
