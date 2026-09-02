/**
 * 文件说明：验证更新检查错误信息的复制内容格式。
 * 目标是让复制到剪贴板的文本同时包含用户可读摘要与真实底层诊断信息，
 * 便于区分 TLS/证书问题、HTTP 问题和 JSON 解析问题。
 */
package com.dawncourse.feature.update

import javax.net.ssl.SSLException
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateErrorFormatterTest {

    @Test
    fun `复制错误信息应包含主备地址与 TLS 诊断提示`() {
        val primaryFailure = UpdateEndpointRequestException(
            endpointLabel = "主地址",
            endpointUrl = "https://yyh163.xyz:10000/version.json",
            stage = "request",
            detail = "Remote host terminated the handshake",
            cause = SSLException("Remote host terminated the handshake")
        )
        val fallbackFailure = UpdateEndpointRequestException(
            endpointLabel = "备用地址",
            endpointUrl = "https://47.105.76.193/version.json",
            stage = "request",
            detail = "Unable to parse TLS packet header",
            cause = SSLException("Unable to parse TLS packet header")
        )
        val exception = UpdateRepository.UpdateCheckException(
            userMessage = "检查更新失败，请检查网络或稍后重试",
            debugDetails = listOf(primaryFailure, fallbackFailure),
            cause = fallbackFailure
        )
        exception.addSuppressed(primaryFailure)

        val message = formatUpdateErrorMessage(exception)

        assertTrue(message.contains("检查更新失败：检查更新失败，请检查网络或稍后重试"))
        assertTrue(message.contains("主地址"))
        assertTrue(message.contains("备用地址"))
        assertTrue(message.contains("yyh163.xyz:10000/version.json"))
        assertTrue(message.contains("47.105.76.193/version.json"))
        assertTrue(message.contains("SSLException"))
        assertTrue(message.contains("TLS/SSL"))
        assertTrue(message.contains("HTTPS"))
    }
}
