/**
 * 文件说明：验证更新元数据节点按顺序容错，并完整保留失败诊断。
 */
package com.dawncourse.feature.update

import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateEndpointFailoverTest {

    @Test
    fun `自建服务可用时不请求 GitHub 节点`() = runBlocking {
        val endpoints = buildUpdateEndpointConfigs()
        val requestedLabels = mutableListOf<String>()
        val expected = validUpdateInfo()

        val actual = resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
            requestedLabels += endpoint.label
            expected
        }

        assertEquals(expected, actual)
        assertEquals(listOf("Dawn Server"), requestedLabels)
    }

    @Test
    fun `自建服务失败后继续从 GitHub Raw 获取更新`() = runBlocking {
        val endpoints = buildUpdateEndpointConfigs()
        val requestedLabels = mutableListOf<String>()
        val expected = validUpdateInfo()

        val actual = resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
            requestedLabels += endpoint.label
            if (endpoint.label == "Dawn Server") {
                throw UpdateEndpointRequestException(
                    endpointLabel = endpoint.label,
                    endpointUrl = endpoint.versionInfoUrl,
                    stage = "request",
                    detail = "timeout",
                    cause = SocketTimeoutException("timeout")
                )
            }
            expected
        }

        assertEquals(expected, actual)
        assertEquals(listOf("Dawn Server", "GitHub Raw"), requestedLabels)
    }

    @Test
    fun `自建端点抛出 IllegalStateException 时按策略继续 fallback 而不会逃逸`() = runBlocking {
        // 回归 issue #115：ConnectionSpec 与实际协议不匹配时 OkHttp 会抛出
        // IllegalStateException（而非 IOException）。resolveUpdateInfoFromEndpoints
        // 必须把它当作普通节点失败处理，绝不能让其穿透到调用方。
        val endpoints = buildUpdateEndpointConfigs()
        val requestedLabels = mutableListOf<String>()
        val expected = validUpdateInfo()

        val actual = resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
            requestedLabels += endpoint.label
            if (endpoint.label == "Dawn Server") {
                throw IllegalStateException("CLEARTEXT-only client")
            }
            expected
        }

        assertEquals(expected, actual)
        assertEquals(listOf("Dawn Server", "GitHub Raw"), requestedLabels)
    }

    @Test
    fun `GitHub Raw 超时时继续从 GitHub API 获取更新`() = runBlocking {
        val endpoints = buildUpdateEndpointConfigs().drop(1)
        val requestedLabels = mutableListOf<String>()
        val expected = validUpdateInfo()

        val actual = resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
            requestedLabels += endpoint.label
            if (endpoint.label == "GitHub Raw") {
                throw UpdateEndpointRequestException(
                    endpointLabel = endpoint.label,
                    endpointUrl = endpoint.versionInfoUrl,
                    stage = "request",
                    detail = "timeout",
                    cause = SocketTimeoutException("timeout")
                )
            }
            expected
        }

        assertEquals(expected, actual)
        assertEquals(listOf("GitHub Raw", "GitHub API"), requestedLabels)
    }

    @Test
    fun `所有节点失败时按请求顺序保留每个节点诊断`() = runBlocking {
        val endpoints = buildUpdateEndpointConfigs()

        val failure = runCatching {
            resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
                throw UpdateEndpointRequestException(
                    endpointLabel = endpoint.label,
                    endpointUrl = endpoint.versionInfoUrl,
                    stage = "request",
                    detail = "timeout",
                    cause = SocketTimeoutException("timeout")
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is UpdateEndpointsExhaustedException)
        val exhausted = failure as UpdateEndpointsExhaustedException
        assertEquals(endpoints.map(UpdateEndpointConfig::label), exhausted.failures.map { it.endpointLabel })
        assertEquals(endpoints.map(UpdateEndpointConfig::versionInfoUrl), exhausted.failures.map { it.endpointUrl })
    }

    @Test
    fun `检查被取消后不得继续请求备用节点`() = runBlocking {
        val endpoints = buildUpdateEndpointConfigs()
        val primaryStarted = CompletableDeferred<Unit>()
        var primaryCancelled = false
        var fallbackStarted = false
        val job = launch {
            resolveUpdateInfoFromEndpoints(endpoints) { endpoint ->
                if (endpoint == endpoints.first()) {
                    primaryStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        primaryCancelled = true
                    }
                } else {
                    fallbackStarted = true
                    validUpdateInfo()
                }
            }
        }

        primaryStarted.await()
        job.cancelAndJoin()
        yield()

        assertFalse(fallbackStarted)
        assertTrue(primaryCancelled)
    }

    private fun validUpdateInfo(): UpdateInfo = UpdateInfo(
        versionCode = 140,
        versionName = "1.0.6.1",
        title = "测试更新",
        content = "测试",
        downloadUrl = "https://gitee.com/YeMiao_cats/Dawn-Course/releases/download/v1.0.6.1/Dawn%20Course.apk",
        releaseDate = "2026-09-02",
        sha256 = "a".repeat(64)
    )
}
