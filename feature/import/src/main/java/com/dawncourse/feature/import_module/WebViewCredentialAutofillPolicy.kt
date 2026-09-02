package com.dawncourse.feature.import_module

import java.net.URI

/**
 * WebView 密码自动填充的来源约束。
 *
 * 密码只能注入到用户绑定凭据时保存的同一 HTTP(S) origin。页面路径可以变化，
 * 但 scheme、host 和有效端口必须一致；SSO 等跨源页面仍由 WebView 正常加载，
 * 只是不再获得自动填充的密码。
 */
internal object WebViewCredentialAutofillPolicy {

    /** 判断当前实际 WebView URL 是否可以接收已保存凭据的自动填充。 */
    fun canAutoFill(savedEndpointUrl: String?, currentWebViewUrl: String?): Boolean {
        val savedOrigin = savedEndpointUrl?.let(::parseWebOrigin) ?: return false
        val currentOrigin = currentWebViewUrl?.let(::parseWebOrigin) ?: return false
        return savedOrigin == currentOrigin
    }

    /**
     * 自动记录入口时也不得扩大 origin allowlist。
     *
     * 仅允许在既有的用户绑定 origin 内更新路径；没有已保存 endpoint 或跨源回调
     * 都必须由用户在设置中显式重新绑定。显式保存的校园 HTTP 入口仍只允许同源更新。
     */
    fun canUpdateSavedEndpoint(savedEndpointUrl: String?, currentWebViewUrl: String?): Boolean =
        canAutoFill(savedEndpointUrl, currentWebViewUrl)

    /**
     * 将含凭据的填充脚本包进执行时 origin 校验。
     *
     * Kotlin 侧的 URL 校验只能作为快速拒绝：在读取脚本、构造参数与
     * [WebView.evaluateJavascript] 真正执行之间，页面仍可能发生跳转。这里将期望
     * origin 与凭据脚本置于同一次 evaluateJavascript 调用中，并在进入凭据脚本前
     * 直接读取 document.location 的不可伪造 Location 属性重新核验。
     *
     * 已被允许的 origin 中的恶意页面本身无法通过客户端脚本隔离；该边界只防止
     * 登录页跳转到另一个普通页面后仍把凭据注入过去。
     */
    fun wrapCredentialScriptForVerifiedOrigin(
        savedEndpointUrl: String?,
        credentialScript: String,
    ): String? {
        val expectedOrigin = savedEndpointUrl?.let(::parseWebOrigin) ?: return null
        val expectedPort = expectedOrigin.effectivePort.toString()

        return """
            (function() {
              const expectedProtocol = ${(expectedOrigin.scheme + ":").toJavaScriptDoubleQuotedLiteral()};
              const expectedHost = ${expectedOrigin.host.toJavaScriptDoubleQuotedLiteral()};
              const expectedPort = ${expectedPort.toJavaScriptDoubleQuotedLiteral()};
              const currentLocation = document.location;
              const actualProtocol = currentLocation.protocol;
              const actualHost = currentLocation.hostname;
              const actualPort = currentLocation.port === ""
                ? (actualProtocol === "https:" ? "443" : "80")
                : currentLocation.port;
              if (
                actualProtocol !== expectedProtocol ||
                actualHost !== expectedHost ||
                actualPort !== expectedPort
              ) {
                return "autofill_origin_mismatch";
              }
              return (function() {
                $credentialScript
                return "autofill_applied";
              })();
            })();
        """.trimIndent()
    }

    private fun parseWebOrigin(rawUrl: String): WebOrigin? = runCatching {
        val uri = URI(rawUrl.trim())
        if (!uri.isAbsolute || uri.isOpaque) {
            return null
        }
        val scheme = uri.scheme.lowercase().takeIf { it == "https" || it == "http" } ?: return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        WebOrigin(
            scheme = scheme,
            host = host,
            effectivePort = if (port == -1) defaultPort(scheme) else port,
        )
    }.getOrNull()

    /** 仅保留安全比较所需的严格 Web origin 组成部分。 */
    private data class WebOrigin(
        val scheme: String,
        val host: String,
        val effectivePort: Int,
    )

    private fun defaultPort(scheme: String): Int = if (scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT

    /** 将 Kotlin 字符串编码为不会改变包装器结构的 JavaScript 双引号字面量。 */
    private fun String.toJavaScriptDoubleQuotedLiteral(): String = buildString(length + 2) {
        append('"')
        for (character in this@toJavaScriptDoubleQuotedLiteral) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private const val HTTPS_DEFAULT_PORT = 443
    private const val HTTP_DEFAULT_PORT = 80
}
