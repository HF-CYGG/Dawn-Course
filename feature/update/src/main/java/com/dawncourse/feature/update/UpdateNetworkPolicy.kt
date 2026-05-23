/**
 * 文件说明：统一定义更新检查的网络连接策略。
 * 由于版本文件来自独立服务器，客户端需要兼容现代 TLS 与兼容 TLS，
 * 避免服务端证书或加密套件稍旧时直接握手失败。
 */
package com.dawncourse.feature.update

import okhttp3.ConnectionSpec

/**
 * 构建更新检查的连接策略列表。
 *
 * 顺序说明：
 * 1. 优先使用现代 TLS；
 * 2. 如独立服务器 TLS 配置较旧，再退到兼容 TLS；
 * 3. 保留明文能力，仅用于现有代码的兼容兜底，不改变远端数据格式。
 */
fun buildUpdateConnectionSpecs(): List<ConnectionSpec> {
    return listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )
}
