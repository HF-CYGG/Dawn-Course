package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定脚本同步调用侧的读写传输边界，防止只读 HTTP 回退被误用于上报。 */
class ScriptSyncTransportContractTest {

    @Test
    fun `签名下载允许只读回退而所有上报只使用敏感 API 端点`() {
        val source = repositorySource()

        assertTrue(
            source.contains(
                "private val signedReadOnlyEndpoints = CloudBackendEndpoints.signedReadOnlyBaseUrls"
            )
        )
        assertTrue(
            source.contains(
                "private val sensitiveApiEndpoints = CloudBackendEndpoints.sensitiveApiBaseUrls"
            )
        )
        assertTrue(source.contains("private val manifestApiEndpoints = sensitiveApiEndpoints"))
        assertTrue(source.contains("private val scriptBaseUrls = signedReadOnlyEndpoints.map"))
        assertTrue(source.contains("private val feedbackBaseUrls = sensitiveApiEndpoints.map"))
        assertTrue(source.contains("private val pullStatUrls = sensitiveApiEndpoints.map"))
        assertTrue(source.contains("private val activationEventUrls = sensitiveApiEndpoints.map"))
        assertTrue(source.contains("feedbackBaseUrls.any"))
        assertTrue(source.contains("for ((label, baseUrl) in manifestApiEndpoints"))
        assertTrue(source.contains("for ((_, baseUrl) in manifestApiEndpoints"))
        assertFalse(source.contains("for ((_, baseScriptsUrl) in scriptBaseUrls"))
        assertFalse(source.contains("private val backendEndpoints ="))
    }

    /** 兼容从仓库根目录或 core:data 模块目录启动 Gradle 测试。 */
    private fun repositorySource(): String {
        val candidates = listOf(
            File(
                "src/main/java/com/dawncourse/core/data/repository/ScriptSyncRepositoryImpl.kt"
            ),
            File(
                "core/data/src/main/java/com/dawncourse/core/data/repository/ScriptSyncRepositoryImpl.kt"
            )
        )
        return candidates.first(File::isFile).readText()
    }
}
