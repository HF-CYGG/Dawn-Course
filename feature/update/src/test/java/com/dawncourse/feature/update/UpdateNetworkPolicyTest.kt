/**
 * 文件说明：验证更新检查网络连接策略。
 * 目标是确保自建 HTTP 元数据优先，同时把明文能力限制在唯一固定入口。
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
    fun `更新连接策略只允许 TLS`() {
        val specs = buildUpdateConnectionSpecs()

        assertEquals(ConnectionSpec.MODERN_TLS, specs[0])
        assertTrue(specs.contains(ConnectionSpec.COMPATIBLE_TLS))
        assertFalse(specs.contains(ConnectionSpec.CLEARTEXT))
    }

    @Test
    fun `只有固定自建元数据入口允许明文连接`() {
        val endpoints = buildUpdateEndpointConfigs()

        assertEquals(listOf(ConnectionSpec.CLEARTEXT), buildUpdateMetadataConnectionSpecs(endpoints[0]))
        assertFalse(buildUpdateMetadataConnectionSpecs(endpoints[1]).contains(ConnectionSpec.CLEARTEXT))
        assertFalse(
            buildUpdateMetadataConnectionSpecs(
                UpdateEndpointConfig(
                    label = "Untrusted HTTP",
                    baseUrl = "http://attacker.example/"
                )
            ).contains(ConnectionSpec.CLEARTEXT)
        )
        assertFalse(
            buildUpdateMetadataConnectionSpecs(
                UpdateEndpointConfig(
                    label = "Wrong self-hosted path",
                    baseUrl = "http://yyh163.xyz:10000/",
                    versionInfoPath = "other.json"
                )
            ).contains(ConnectionSpec.CLEARTEXT)
        )
    }

    @Test
    fun `更新元数据优先自建服务再按 Raw API CDN 顺序兜底`() {
        val endpoints = buildUpdateEndpointConfigs()

        assertEquals(4, endpoints.size)
        assertEquals("Dawn Server", endpoints[0].label)
        assertEquals(
            "http://yyh163.xyz:10000/version.json",
            endpoints[0].versionInfoUrl
        )
        assertTrue(endpoints[0].requestTimeoutSeconds <= 5L)
        assertEquals("GitHub Raw", endpoints[1].label)
        assertEquals(
            "https://raw.githubusercontent.com/HF-CYGG/DawnCourse-server/main/version.json",
            endpoints[1].versionInfoUrl
        )
        assertEquals("GitHub API", endpoints[2].label)
        assertEquals(
            "https://api.github.com/repos/HF-CYGG/DawnCourse-server/contents/version.json?ref=main",
            endpoints[2].versionInfoUrl
        )
        assertEquals("jsDelivr CDN", endpoints[3].label)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/HF-CYGG/DawnCourse-server@main/version.json",
            endpoints[3].versionInfoUrl
        )
        assertTrue(endpoints.drop(1).all { endpoint -> endpoint.versionInfoUrl.startsWith("https://") })
    }

    @Test
    fun `更新元数据响应只能停留在配置节点的相同来源和协议`() {
        val expected = "http://yyh163.xyz:10000/version.json"

        assertTrue(isExpectedUpdateMetadataResponseUrl(expected, expected))
        assertTrue(
            isExpectedUpdateMetadataResponseUrl(
                expected,
                "http://yyh163.xyz:10000/releases/version.json"
            )
        )
        assertFalse(
            isExpectedUpdateMetadataResponseUrl(
                expected,
                "https://yyh163.xyz:10000/version.json"
            )
        )
        assertFalse(
            isExpectedUpdateMetadataResponseUrl(
                expected,
                "http://attacker.example/version.json"
            )
        )
        assertFalse(
            isExpectedUpdateMetadataResponseUrl(
                expected,
                "http://yyh163.xyz/version.json"
            )
        )
        assertFalse(
            isExpectedUpdateMetadataResponseUrl(
                "http://attacker.example/version.json",
                "http://attacker.example/version.json"
            )
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

    @Test
    fun `应用内安装要求元数据提供合法 sha256`() {
        val missingHash = UpdateInfo(
            versionCode = 139,
            versionName = "1.0.6.0",
            title = null,
            content = null,
            downloadUrl = "https://downloads.example.com/update.apk",
            releaseDate = null,
            sha256 = null
        )
        val validHash = missingHash.copy(sha256 = "a".repeat(64))

        assertNull(validateUpdateInfo(missingHash))
        assertEquals(validHash, validateUpdateInfo(validHash))
    }
}
