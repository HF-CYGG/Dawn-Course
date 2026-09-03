/**
 * 文件说明：端到端复现 issue #115 的崩溃根因（连接规格与实际协议不匹配），
 * 并验证修复后自建更新节点在真实 TLS 连接下可以正常工作。
 *
 * 之所以使用真实 OkHttpClient + MockWebServer 而不是只在业务逻辑层 mock，
 * 是因为 issue #115 的崩溃恰好发生在 OkHttp 建立连接、协商连接规格的阶段，
 * 单纯 mock 掉网络层无法复现或验证这类问题。
 */
package com.dawncourse.feature.update

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateClientTlsRegressionTest {

    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates

    @Before
    fun setUp() {
        val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(localhostCertificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 复现 issue #115 的崩溃根因：旧版自建端点专用的 ConnectionSpec.CLEARTEXT-only
     * 客户端一旦命中实际讲 TLS 的服务端（服务端迁移到 HTTPS 后正是这种情况），
     * 连接会在建立阶段直接失败，而不是把请求当作明文成功发出。
     */
    @Test
    fun `明文专用连接规格连接实际 TLS 服务端会立即失败`() {
        server.enqueue(MockResponse().setBody("{}"))
        val cleartextOnlyClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .build()
        val request = Request.Builder().url(server.url("/version.json")).build()

        assertThrows(Throwable::class.java) {
            cleartextOnlyClient.newCall(request).execute()
        }
    }

    /**
     * 验证修复：所有更新节点现在统一使用 buildUpdateConnectionSpecs()（仅 TLS），
     * 在信任对应证书的前提下可以正常完成一次自建端点请求。
     */
    @Test
    fun `修复后使用统一的 TLS 连接规格可以正常完成自建端点请求`() {
        server.enqueue(MockResponse().setBody("{\"versionCode\":140}"))
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectionSpecs(buildUpdateConnectionSpecs())
            .build()
        val request = Request.Builder().url(server.url("/version.json")).build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals("{\"versionCode\":140}", response.body?.string())
        }
    }

    /**
     * issue #115 中描述的触发路径之一：自建节点跳转到另一个 HTTPS 地址。
     * 统一的 TLS-only 客户端跟随跳转、重新建立 TLS 连接时应正常工作，
     * 不会因为跳转前后握手差异产生连接规格冲突。
     */
    @Test
    fun `跟随跳转到同源 HTTPS 目标时统一的 TLS 连接规格仍能正常完成请求`() {
        val redirectTargetPath = "/version.json"
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url(redirectTargetPath).toString())
        )
        server.enqueue(MockResponse().setBody("{\"versionCode\":140}"))
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectionSpecs(buildUpdateConnectionSpecs())
            .build()
        val request = Request.Builder().url(server.url("/redirect")).build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals(server.url(redirectTargetPath), response.request.url)
        }
    }
}
