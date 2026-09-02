package com.dawncourse.core.domain.model

/**
 * LLM 异步解析任务提交结果
 *
 * @property success 是否提交成功
 * @property taskId 服务端返回的任务 ID
 * @property message 失败原因或提示信息
 */
data class LlmParseTaskResult(
    val success: Boolean,
    val taskId: String? = null,
    val message: String? = null
)

/**
 * LLM 异步解析任务状态
 */
enum class LlmParseStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}

/**
 * LLM 异步解析任务查询结果
 *
 * @property success 是否请求成功
 * @property status 任务状态
 * @property resultText 解析完成后的原始 JSON 字符串
 * @property message 失败原因或提示信息
 */
data class LlmParseStatusResult(
    val success: Boolean,
    val status: LlmParseStatus,
    val resultText: String? = null,
    val message: String? = null
)
