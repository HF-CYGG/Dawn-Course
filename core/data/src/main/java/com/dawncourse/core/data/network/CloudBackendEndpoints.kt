package com.dawncourse.core.data.network

internal data class CloudBackendEndpoint(
    val label: String,
    val baseUrl: String
)

internal object CloudBackendEndpoints {
    val apiBaseUrls: List<CloudBackendEndpoint> = listOf(
        CloudBackendEndpoint("primary_https", "https://yyh163.xyz:10000/"),
        CloudBackendEndpoint("primary_http", "http://yyh163.xyz:10000/"),
        CloudBackendEndpoint("fallback_https", "https://47.105.76.193:15000/"),
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
