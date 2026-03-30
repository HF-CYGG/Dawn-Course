package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LlmParseStatusResult
import com.dawncourse.core.domain.repository.LlmParseRepository
import javax.inject.Inject

/**
 * 查询 LLM 异步解析任务状态用例
 */
class FetchLlmParseStatusUseCase @Inject constructor(
    private val repository: LlmParseRepository
) {
    /**
     * 查询任务状态
     *
     * @param taskId 服务端返回的任务 ID
     */
    suspend operator fun invoke(taskId: String): LlmParseStatusResult {
        return repository.fetchTaskStatus(taskId)
    }
}
