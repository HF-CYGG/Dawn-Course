/**
 * 文件说明：定义应用内 APK 下载的纯策略，包括可信元数据、固定文件名与内容校验。
 */
package com.dawncourse.feature.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/** 应用内更新缓存目录，只允许 FileProvider 暴露该目录。 */
internal const val UPDATE_PACKAGE_DIRECTORY = "update_packages"

/** APK 的标准 MIME 类型。 */
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** 单个更新包的安全大小上限，避免异常响应耗尽本机存储。 */
internal const val MAX_UPDATE_PACKAGE_BYTES = 200L * 1024L * 1024L

/** 被系统杀进程后遗留的未完成下载，超过一天即可安全清理。 */
internal const val INCOMPLETE_UPDATE_TTL_MILLIS = 24L * 60L * 60L * 1000L

/** 非当前目标版本的已验证缓存最多保留七天，避免长期占用空间。 */
internal const val OLD_UPDATE_APK_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L

/**
 * 已收敛的下载请求。
 *
 * 文件名由应用生成，绝不采用 URL 或 Content-Disposition 提供的名称。
 */
internal data class ApkDownloadRequest(
    val url: String,
    val fileName: String,
    val expectedSha256: String,
    val expectedVersionCode: Long
)

/** 将高频字节读取进度压缩为不同的百分比值，未知长度也只通知一次。 */
internal class DownloadProgressDeduplicator(
    private val emit: (Int?) -> Unit
) {
    private var hasEmitted = false
    private var lastProgress: Int? = null

    fun report(progress: Int?) {
        if (hasEmitted && lastProgress == progress) return
        hasEmitted = true
        lastProgress = progress
        emit(progress)
    }
}

/** 将更新元数据转换为可执行下载请求；缺少强校验信息时拒绝下载。 */
internal fun UpdateInfo.toApkDownloadRequest(): ApkDownloadRequest? {
    if (!isValidUpdateDownloadUrl(downloadUrl)) return null
    val normalizedSha256 = sha256
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(::isValidUpdateSha256)
        ?: return null
    val normalizedVersion = Regex("^[vV]?[0-9]+(?:\\.[0-9]+){1,3}")
        .find(versionName.trim())
        ?.value
        ?.removePrefix("v")
        ?.removePrefix("V")
        ?: versionCode.toString()
    return ApkDownloadRequest(
        url = downloadUrl,
        fileName = "Dawn-Course-v$normalizedVersion.apk",
        expectedSha256 = normalizedSha256,
        expectedVersionCode = versionCode.toLong()
    )
}

/**
 * 决定更新包在本机的最终文件名。
 *
 * Gitee 等托管站可能把 APK 标记为 application/zip，部分下载器还会据此追加 `.zip`。
 * 应用内下载只信任已经收敛的客户端文件名，明确忽略响应 MIME 与 Content-Disposition。
 */
internal fun resolveLocalUpdatePackageFileName(
    request: ApkDownloadRequest,
    responseContentType: String?,
    responseContentDisposition: String?
): String {
    @Suppress("UNUSED_VARIABLE")
    val ignoredUntrustedHeaders = responseContentType to responseContentDisposition
    return request.fileName
}

/**
 * 验证下载内容的 ZIP/APK 文件头与 SHA-256。
 *
 * APK 本质上是 ZIP；这里先排除 HTML 错误页等明显非 APK 内容，随后仍由
 * PackageManager 校验包名、版本和签名，最终由系统安装器完成完整验证。
 */
internal fun verifyDownloadedApkPayload(
    file: File,
    expectedSha256: String,
    checkActive: () -> Unit = {}
): Boolean {
    if (!file.isFile || file.length() < APK_ZIP_HEADER_SIZE) return false
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        checkActive()
        val header = ByteArray(APK_ZIP_HEADER_SIZE)
        if (input.read(header) != APK_ZIP_HEADER_SIZE || !isZipHeader(header)) return false
        digest.update(header)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actualSha256 = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
    return actualSha256.equals(expectedSha256.trim(), ignoreCase = true)
}

/** 精确清理专用目录中的过期中间文件与旧版本 APK，不触碰当前目标文件。 */
internal fun cleanupStaleUpdateFiles(
    updateDirectory: File,
    expectedFinalFile: File,
    nowMillis: Long = System.currentTimeMillis()
) {
    updateDirectory.listFiles().orEmpty().forEach { candidate ->
        if (!candidate.isFile || candidate == expectedFinalFile) return@forEach
        val ageMillis = (nowMillis - candidate.lastModified()).coerceAtLeast(0L)
        val shouldDelete = when {
            candidate.name.endsWith(".part", ignoreCase = true) -> {
                ageMillis >= INCOMPLETE_UPDATE_TTL_MILLIS
            }
            candidate.name.endsWith(".apk", ignoreCase = true) -> {
                ageMillis >= OLD_UPDATE_APK_TTL_MILLIS
            }
            else -> false
        }
        if (shouldDelete) candidate.delete()
    }
}

/** 只接受常见 ZIP 本地文件头、空归档头和分卷头。 */
private fun isZipHeader(header: ByteArray): Boolean {
    if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) return false
    val marker = (header[2].toInt() and 0xFF) to (header[3].toInt() and 0xFF)
    return marker == (0x03 to 0x04) ||
        marker == (0x05 to 0x06) ||
        marker == (0x07 to 0x08)
}

private const val APK_ZIP_HEADER_SIZE = 4
