/**
 * 文件说明：统一定义更新检查的安全网络策略与可信元数据入口。
 * 更新元数据与下载链接都属于不可信网络输入，必须在进入 UI 前收敛为 HTTPS。
 */
package com.dawncourse.feature.update

import java.net.URI
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
 * 2. 兼容仍符合 HTTPS 要求的旧 TLS 服务端；
 * 3. 更新链路绝不允许明文 HTTP。
 */
fun buildUpdateConnectionSpecs(): List<ConnectionSpec> {
    return listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS
    )
}

/**
 * 构建更新检查的节点列表。
 *
 * 仅保留已确认的公开 GitHub Raw HTTPS 元数据入口，避免将更新信任链降级到
 * 未经确认的备用服务。
 */
fun buildUpdateEndpointConfigs(): List<UpdateEndpointConfig> {
    return listOf(
        UpdateEndpointConfig(
            label = "GitHub Raw",
            baseUrl = "https://raw.githubusercontent.com/HF-CYGG/DawnCourse-server/main/"
        )
    )
}

/**
 * 判断远端元数据提供的下载链接能否安全交给系统浏览器。
 *
 * 不对输入做补全或修正：任何非 HTTPS、缺少主机名或包含 userInfo 的链接都直接拒绝，
 * 以免攻击者借元数据将 Intent 导向本地文件、ContentProvider 或自定义 Scheme。
 */
fun isValidUpdateDownloadUrl(downloadUrl: String): Boolean = runCatching {
    val uri = URI(downloadUrl)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

/** 只有通过下载链接校验的元数据才可作为成功结果继续向 UI 传播。 */
fun validateUpdateInfo(updateInfo: UpdateInfo): UpdateInfo? {
    return updateInfo.takeIf { isValidUpdateDownloadUrl(it.downloadUrl) }
}
