/**
 * 文件说明：统一定义云端解析失败后的客户端提示策略。
 * 重点把“服务端已接收数据但暂未产出结果”的场景归类为可重试提示，
 * 避免用户在上传完成后立即看到红色报错，误以为本次操作完全失败。
 */
package com.dawncourse.feature.import_module

/**
 * 云端解析失败的前端展示结果。
 *
 * @property userMessage 最终展示给用户的提示文案
 * @property currentStep 同步流程中的当前步骤名称
 * @property isRetryable 是否属于可稍后重试的情况
 * @property shouldReportError 是否应继续按错误上报到同步日志与错误面板
 */
data class CloudParseFailurePresentation(
    val userMessage: String,
    val currentStep: String,
    val isRetryable: Boolean,
    val shouldReportError: Boolean
)

/**
 * 构建云端解析失败后的展示策略。
 *
 * 说明：
 * 1. `cloud_empty_result` 表示服务端已接收到数据，但当前没有产出可解析课程；
 * 2. 对该场景仅提示用户稍后重试，不继续展示技术错误码；
 * 3. 其他错误保持原有错误语义，便于定位真实失败原因。
 */
fun buildCloudParseFailurePresentation(rawReason: String): CloudParseFailurePresentation {
    val normalized = rawReason.trim()
    return when {
        normalized.contains("cloud_empty_result", ignoreCase = true) -> {
            CloudParseFailurePresentation(
                userMessage = "云端已接收数据，暂未生成解析结果，请过一段时间重试",
                currentStep = "等待稍后重试",
                isRetryable = true,
                shouldReportError = false
            )
        }

        normalized.isBlank() -> {
            CloudParseFailurePresentation(
                userMessage = "云端解析失败，请确认页面数据完整或稍后重试",
                currentStep = "云端解析失败",
                isRetryable = false,
                shouldReportError = true
            )
        }

        normalized.startsWith("云端解析失败：") -> {
            CloudParseFailurePresentation(
                userMessage = normalized,
                currentStep = "云端解析失败",
                isRetryable = false,
                shouldReportError = true
            )
        }

        else -> {
            CloudParseFailurePresentation(
                userMessage = "云端解析失败：$normalized",
                currentStep = "云端解析失败",
                isRetryable = false,
                shouldReportError = true
            )
        }
    }
}
