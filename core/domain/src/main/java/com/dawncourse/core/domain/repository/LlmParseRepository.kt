package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.LlmParseStatusResult
import com.dawncourse.core.domain.model.LlmParseTaskResult

/**
 * LLM 异步解析仓库接口
 *
 * 负责提交解析任务与轮询任务状态，确保上层业务不直接依赖网络实现细节。
 */
interface LlmParseRepository {
    /**
     * 提交 LLM 异步解析任务
     *
     * @param content 已脱敏的 HTML/文本内容
     */
    suspend fun submitParseTask(content: String): LlmParseTaskResult

    /**
     * 查询 LLM 异步解析任务状态
     *
     * @param taskId 服务端返回的任务 ID
     */
    suspend fun fetchTaskStatus(taskId: String): LlmParseStatusResult
}
