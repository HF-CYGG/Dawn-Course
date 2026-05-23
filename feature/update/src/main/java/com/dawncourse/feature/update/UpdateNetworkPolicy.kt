/**
 * 文件说明：统一定义更新检查的网络连接策略与更新节点地址。
 * 由于版本文件来自独立服务器，客户端需要兼容多种连接方式，
 * 并优先选择当前已验证可直连的更新入口。
 */
package com.dawncourse.feature.update

import okhttp3.ConnectionSpec

/**
 * 更新节点配置。
 *
 * @property label 节点名称，用于错误诊断展示
 * @property baseUrl Retrofit 使用的基础地址
 */
data class UpdateEndpointConfig(
    val label: String,
    val baseUrl: String
)

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

/**
 * 构建更新检查的节点列表。
 *
 * 当前策略：
 * 1. 主地址优先走已验证可访问的 HTTP IP:10000；
 * 2. 备用地址保留域名 HTTP:10000，便于后续域名入口恢复后继续兜底；
 * 3. 仅调整客户端访问入口，不改独立服务器版本文件内容。
 */
fun buildUpdateEndpointConfigs(): List<UpdateEndpointConfig> {
    return listOf(
        UpdateEndpointConfig(
            label = "主地址",
            baseUrl = "http://47.105.76.193:10000/"
        ),
        UpdateEndpointConfig(
            label = "备用地址",
            baseUrl = "http://yyh163.xyz:10000/"
        )
    )
}
