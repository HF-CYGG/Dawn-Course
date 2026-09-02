/**
 * 文件说明：验证更新检查网络连接策略。
 * 目标是确保独立更新服务器即使 TLS 配置偏旧，客户端也能优先用安全方式继续兼容访问。
 */
package com.dawncourse.feature.update

import okhttp3.ConnectionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNetworkPolicyTest {

    @Test
    fun `更新连接策略只允许现代与兼容 TLS`() {
        val specs = buildUpdateConnectionSpecs()

        assertEquals(ConnectionSpec.MODERN_TLS, specs[0])
        assertTrue(specs.contains(ConnectionSpec.COMPATIBLE_TLS))
        assertFalse(specs.contains(ConnectionSpec.CLEARTEXT))
    }

    @Test
    fun `更新元数据只使用 GitHub Raw HTTPS 入口`() {
        val endpoints = buildUpdateEndpointConfigs()

        assertEquals(1, endpoints.size)
        assertEquals("GitHub Raw", endpoints[0].label)
        assertEquals(
            "https://raw.githubusercontent.com/HF-CYGG/DawnCourse-server/main/",
            endpoints[0].baseUrl
        )
    }

    @Test
    fun `下载链接仅接受不含用户信息的 HTTPS 地址`() {
        assertTrue(isValidUpdateDownloadUrl("https://downloads.example.com/Dawn%20Course.apk"))
        assertTrue(isValidUpdateDownloadUrl("HTTPS://downloads.example.com/update.apk"))

        assertFalse(isValidUpdateDownloadUrl("http://downloads.example.com/update.apk"))
        assertFalse(isValidUpdateDownloadUrl("https:/missing-host.apk"))
        assertFalse(isValidUpdateDownloadUrl("https://user@downloads.example.com/update.apk"))
        assertFalse(isValidUpdateDownloadUrl("file:///sdcard/update.apk"))
        assertFalse(isValidUpdateDownloadUrl("content://downloads.example.com/update.apk"))
        assertFalse(isValidUpdateDownloadUrl("dawncourse://downloads.example.com/update.apk"))
    }

    @Test
    fun `不可信下载链接不能形成可用更新信息`() {
        val invalidInfo = UpdateInfo(
            versionCode = 1,
            versionName = "1.0.0",
            title = null,
            content = null,
            downloadUrl = "http://downloads.example.com/update.apk",
            releaseDate = null
        )

        assertNull(validateUpdateInfo(invalidInfo))
    }
}
