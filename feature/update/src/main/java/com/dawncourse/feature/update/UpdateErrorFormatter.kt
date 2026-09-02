/**
 * 文件说明：统一整理更新检查失败时的复制错误信息。
 * 目标是在保留用户可理解摘要的同时，补充主备地址、底层异常类型、
 * HTTP/TLS/JSON 等诊断线索，方便定位独立更新服务器的兼容问题。
 */
package com.dawncourse.feature.update

/**
 * 单个更新检查节点的失败明细。
 *
 * @property endpointLabel 节点名称，例如主地址/备用地址
 * @property endpointUrl 实际访问的版本文件地址
 * @property stage 失败阶段，例如 request/http/json
 * @property detail 面向开发者的简短失败描述
 */
data class UpdateEndpointRequestException(
    val endpointLabel: String,
    val endpointUrl: String,
    val stage: String,
    val detail: String,
    override val cause: Throwable? = null
) : Exception(detail, cause)

/**
 * 格式化更新检查错误信息，用于弹窗展示与一键复制。
 */
fun formatUpdateErrorMessage(throwable: Throwable): String {
    val updateException = throwable as? UpdateRepository.UpdateCheckException
    val userMessage = updateException?.userMessage?.ifBlank { throwable.message.orEmpty() }
        ?: throwable.message.orEmpty()
    val details = buildList<UpdateEndpointRequestException> {
        addAll(updateException?.debugDetails.orEmpty())
        val cause = throwable.cause
        if (cause is UpdateEndpointRequestException && !any { existing ->
                existing.endpointLabel == cause.endpointLabel && existing.detail == cause.detail
            }
        ) {
            add(cause)
        }
        throwable.suppressed.forEach { suppressed ->
            if (suppressed is UpdateEndpointRequestException && !any { existing ->
                    existing.endpointLabel == suppressed.endpointLabel && existing.detail == suppressed.detail
                }
            ) {
                add(suppressed)
            }
        }
    }
    val lines = mutableListOf<String>()
    lines += "检查更新失败：${userMessage.ifBlank { "未知错误" }}"
    if (details.isNotEmpty()) {
        lines += ""
        lines += "【节点诊断】"
        details.forEach { detail ->
            val exceptionName = detail.cause?.javaClass?.simpleName ?: detail.javaClass.simpleName
            lines += "- ${detail.endpointLabel}：${detail.endpointUrl}"
            lines += "  阶段：${detail.stage}"
            lines += "  异常：$exceptionName"
            lines += "  详情：${detail.detail}"
        }
    }
    val hint = buildUpdateErrorHint(details, throwable)
    if (hint.isNotBlank()) {
        lines += ""
        lines += "【诊断提示】"
        lines += hint
    }
    return lines.joinToString("\n")
}

/**
 * 生成更贴近真实根因的诊断提示。
 */
private fun buildUpdateErrorHint(
    details: List<UpdateEndpointRequestException>,
    throwable: Throwable
): String {
    val haystack = buildString {
        append(throwable.message.orEmpty())
        append('\n')
        append(throwable.cause?.message.orEmpty())
        details.forEach { detail ->
            append('\n')
            append(detail.detail)
            append('\n')
            append(detail.cause?.message.orEmpty())
        }
    }.lowercase()
    return when {
        haystack.contains("ssl") || haystack.contains("tls") || haystack.contains("handshake") -> {
            "检测到 TLS/SSL 握手异常，请优先检查服务器证书链、TLS 配置，或是否把 HTTPS 请求发到了纯 HTTP 端口。"
        }
        haystack.contains("json") || haystack.contains("expected") || haystack.contains("malformed") -> {
            "检测到版本文件解析异常，请检查 version.json 字段名、字段类型或返回内容是否为合法 JSON。"
        }
        haystack.contains("http") -> {
            "检测到 HTTP 响应异常，请检查版本文件地址、反向代理状态与服务端返回码。"
        }
        else -> {
            "请结合上方节点诊断信息，分别核对主备地址的网络连通性、协议配置与返回内容。"
        }
    }
}
