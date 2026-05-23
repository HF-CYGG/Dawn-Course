/**
 * 文件说明：验证更新检查网络连接策略。
 * 目标是确保独立更新服务器即使 TLS 配置偏旧，客户端也能优先用安全方式继续兼容访问。
 */
package com.dawncourse.feature.update

import okhttp3.ConnectionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNetworkPolicyTest {

    @Test
    fun `更新连接策略应同时支持现代与兼容 TLS`() {
        val specs = buildUpdateConnectionSpecs()

        assertEquals(ConnectionSpec.MODERN_TLS, specs[0])
        assertTrue(specs.contains(ConnectionSpec.COMPATIBLE_TLS))
        assertTrue(specs.contains(ConnectionSpec.CLEARTEXT))
    }
}
