package com.dawncourse.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云端上传与签名只读下载必须使用不同的传输边界。 */
class CloudBackendEndpointsTest {

    @Test
    fun `敏感 API 端点全部使用 HTTPS 且不包含自动明文降级`() {
        val endpoints = CloudBackendEndpoints.sensitiveApiBaseUrls

        assertEquals(listOf("primary_https", "fallback_https"), endpoints.map { it.label })
        assertTrue(endpoints.all { it.baseUrl.startsWith("https://") })
    }

    @Test
    fun `旧 HTTP 端点仅保留在签名校验的只读下载列表`() {
        val sensitiveUrls = CloudBackendEndpoints.sensitiveApiBaseUrls.map { it.baseUrl }.toSet()
        val readOnlyUrls = CloudBackendEndpoints.signedReadOnlyBaseUrls.map { it.baseUrl }.toSet()

        assertTrue(readOnlyUrls.containsAll(sensitiveUrls))
        assertTrue(readOnlyUrls.any { it.startsWith("http://") })
        assertTrue(sensitiveUrls.none { it.startsWith("http://") })
    }
}
