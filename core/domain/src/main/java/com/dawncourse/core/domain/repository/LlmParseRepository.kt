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
     * @param consent 用户是否明确同意上传
     * @param consentAt 用户确认时间戳
     * @param schoolId 用户主动提供的学校标识（可空）
     * @param schoolName 用户主动提供的学校名称（可空）
     * @param schoolSystemType 用户主动提供的教务系统类型（可空）
     */
    suspend fun submitParseTask(
        content: String,
        consent: Boolean,
        consentAt: Long,
        schoolId: String? = null,
        schoolName: String? = null,
        schoolSystemType: String? = null
    ): LlmParseTaskResult

    /**
     * 查询 LLM 异步解析任务状态
     *
     * @param taskId 服务端返回的任务 ID
     */
    suspend fun fetchTaskStatus(taskId: String): LlmParseStatusResult
}
