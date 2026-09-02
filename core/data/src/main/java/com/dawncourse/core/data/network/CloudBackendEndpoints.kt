package com.dawncourse.core.data.network

internal data class CloudBackendEndpoint(
    val label: String,
    val baseUrl: String
)

internal object CloudBackendEndpoints {
    /** 上传脱敏样本、解析结果和任务状态只能使用 TLS，不允许自动降级到 HTTP。 */
    val sensitiveApiBaseUrls: List<CloudBackendEndpoint> = listOf(
        CloudBackendEndpoint("primary_https", "https://yyh163.xyz:10000/"),
        CloudBackendEndpoint("fallback_https", "https://47.105.76.193:15000/")
    )

    /**
     * 公共脚本下载仍兼容旧部署的 HTTP 只读端点；下载内容必须继续通过现有签名校验。
     * 该列表不得用于上传用户数据、凭据、诊断样本或解析任务。
     */
    val signedReadOnlyBaseUrls: List<CloudBackendEndpoint> = sensitiveApiBaseUrls + listOf(
        CloudBackendEndpoint("primary_http", "http://yyh163.xyz:10000/"),
        CloudBackendEndpoint("fallback_http", "http://47.105.76.193:15000/")
    )

    fun toUserFacingMessage(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Unable to parse TLS packet header", ignoreCase = true)) {
                return "服务器协议配置异常（HTTPS 端口返回了 HTTP 响应）"
            }
            current = current.cause
        }
        return error.message?.takeIf { it.isNotBlank() } ?: "网络异常"
    }
}
