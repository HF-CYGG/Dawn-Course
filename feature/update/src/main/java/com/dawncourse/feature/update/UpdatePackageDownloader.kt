/**
 * 文件说明：在应用私有目录下载、验证并原子保存更新 APK。
 */
package com.dawncourse.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 下载并验证完成、可以交给系统安装器的 APK。 */
data class DownloadedUpdatePackage(
    val filePath: String,
    val fileName: String,
    val expectedSha256: String,
    val expectedVersionCode: Long
)

/** 更新包下载或验证失败。 */
class UpdatePackageException(
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause)

/**
 * 更新包下载器。
 *
 * 下载结果只写入应用私有缓存，服务端提供的文件名和 MIME 均不参与本地命名。
 */
@Singleton
class UpdatePackageDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** APK 下载允许更长读取时间，连接策略仍严格限定为 TLS。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .connectionSpecs(buildUpdateConnectionSpecs())
        .build()

    /**
     * 下载并验证更新包。
     *
     * @param updateInfo 已通过版本检查的远端元数据
     * @param onProgress 下载百分比；响应未提供长度时为 null
     */
    suspend fun download(
        updateInfo: UpdateInfo,
        onProgress: (Int?) -> Unit
    ): DownloadedUpdatePackage = withContext(Dispatchers.IO) {
        val downloadRequest = updateInfo.toApkDownloadRequest()
            ?: throw UpdatePackageException("更新信息缺少有效的 HTTPS 地址或 SHA-256，已停止下载")
        val updateDirectory = File(context.cacheDir, UPDATE_PACKAGE_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw UpdatePackageException("无法创建更新缓存目录，请检查设备存储空间")
            }
        }
        val finalFile = File(updateDirectory, downloadRequest.fileName)
        cleanupStaleUpdateFiles(updateDirectory, finalFile)
        val verificationContext = currentCoroutineContext()

        // 同版本已验证文件可以直接复用，不为复用路径创建无意义的临时文件。
        if (finalFile.isFile &&
            verifyDownloadedApkPayload(
                finalFile,
                downloadRequest.expectedSha256,
                verificationContext::ensureActive
            ) &&
            verifyDownloadedApkIdentity(context, finalFile, downloadRequest.expectedVersionCode)
        ) {
            verificationContext.ensureActive()
            onProgress(100)
            return@withContext finalFile.asDownloadedPackage(downloadRequest)
        }
        finalFile.delete()
        val temporaryFile = File.createTempFile(
            "${downloadRequest.fileName}.",
            ".part",
            updateDirectory
        )

        try {
            downloadToTemporaryFile(
                downloadRequest = downloadRequest,
                temporaryFile = temporaryFile,
                expectedFinalFileName = finalFile.name,
                onProgress = onProgress
            )
            currentCoroutineContext().ensureActive()

            if (!verifyDownloadedApkPayload(
                    temporaryFile,
                    downloadRequest.expectedSha256,
                    verificationContext::ensureActive
                )
            ) {
                throw UpdatePackageException("安装包完整性校验失败，文件已删除")
            }
            if (!verifyDownloadedApkIdentity(context, temporaryFile, downloadRequest.expectedVersionCode)) {
                throw UpdatePackageException("安装包的包名、版本或签名不匹配，已停止安装")
            }
            verificationContext.ensureActive()

            Files.move(
                temporaryFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            onProgress(100)
            finalFile.asDownloadedPackage(downloadRequest)
        } catch (failure: CancellationException) {
            temporaryFile.delete()
            throw failure
        } catch (failure: UpdatePackageException) {
            temporaryFile.delete()
            throw failure
        } catch (failure: Throwable) {
            temporaryFile.delete()
            throw UpdatePackageException("安装包下载失败，请检查网络和存储空间后重试", failure)
        }
    }

    /**
     * 使用 OkHttp 异步调用下载，协程取消时立即 cancel 网络请求。
     *
     * 网络回调完成前协程不恢复，因此取消处理器在整个响应体读取期间都保持有效。
     */
    private suspend fun downloadToTemporaryFile(
        downloadRequest: ApkDownloadRequest,
        temporaryFile: File,
        expectedFinalFileName: String,
        onProgress: (Int?) -> Unit
    ) = suspendCancellableCoroutine { continuation ->
        val progressReporter = DownloadProgressDeduplicator(onProgress)
        val request = Request.Builder()
            .url(downloadRequest.url)
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
            temporaryFile.delete()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                temporaryFile.delete()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) {
                            throw UpdatePackageException("安装包下载失败：HTTP ${it.code}")
                        }
                        if (!isValidUpdateDownloadUrl(it.request.url.toString())) {
                            throw UpdatePackageException("安装包重定向到了不安全的地址，已停止下载")
                        }
                        val localFileName = resolveLocalUpdatePackageFileName(
                            request = downloadRequest,
                            responseContentType = it.header("Content-Type"),
                            responseContentDisposition = it.header("Content-Disposition")
                        )
                        if (localFileName != expectedFinalFileName) {
                            throw UpdatePackageException("安装包文件名校验失败，已停止下载")
                        }
                        val body = it.body
                            ?: throw UpdatePackageException("安装包下载响应为空，请稍后重试")
                        val contentLength = body.contentLength()
                        if (contentLength > MAX_UPDATE_PACKAGE_BYTES) {
                            throw UpdatePackageException("安装包超过安全大小限制，已停止下载")
                        }

                        progressReporter.report(if (contentLength > 0L) 0 else null)
                        body.byteStream().use { input ->
                            temporaryFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var downloadedBytes = 0L
                                while (true) {
                                    if (call.isCanceled()) throw IOException("download_cancelled")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    downloadedBytes += read
                                    if (downloadedBytes > MAX_UPDATE_PACKAGE_BYTES) {
                                        throw UpdatePackageException("安装包超过安全大小限制，已停止下载")
                                    }
                                    output.write(buffer, 0, read)
                                    progressReporter.report(
                                        if (contentLength > 0L) {
                                            ((downloadedBytes * 100L) / contentLength)
                                                .coerceIn(0L, 99L)
                                                .toInt()
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    } else {
                        temporaryFile.delete()
                    }
                } catch (failure: Throwable) {
                    temporaryFile.delete()
                    if (continuation.isActive) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }

    /** 构造只暴露路径和值语义的下载结果。 */
    private fun File.asDownloadedPackage(request: ApkDownloadRequest): DownloadedUpdatePackage {
        return DownloadedUpdatePackage(
            filePath = absolutePath,
            fileName = name,
            expectedSha256 = request.expectedSha256,
            expectedVersionCode = request.expectedVersionCode
        )
    }
}

/** 校验 APK 包名、目标版本以及与当前安装版本兼容的签名历史。 */
internal fun verifyDownloadedApkIdentity(
    context: Context,
    file: File,
    expectedVersionCode: Long
): Boolean {
    val packageManager = context.packageManager
    val archiveInfo = packageInfoForArchive(packageManager, file) ?: return false
    val installedInfo = packageInfoForInstalledApp(context, packageManager) ?: return false
    if (archiveInfo.packageName != context.packageName) return false
    if (PackageInfoCompat.getLongVersionCode(archiveInfo) != expectedVersionCode) return false
    if (expectedVersionCode <= PackageInfoCompat.getLongVersionCode(installedInfo)) return false
    return signaturesAreCompatible(installedInfo, archiveInfo)
}

/** 读取 APK 归档信息；Android 13 起使用类型安全 flags。 */
private fun packageInfoForArchive(packageManager: PackageManager, file: File): PackageInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
    }
}

/** 读取当前应用信息，用于版本与签名兼容性校验。 */
private fun packageInfoForInstalledApp(
    context: Context,
    packageManager: PackageManager
): PackageInfo? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()
}

/**
 * 校验签名兼容性。
 *
 * 单签名应用允许候选 APK 的受验证签名历史包含当前签名，从而兼容 Android 的签名轮换；
 * 多签名应用必须保持当前签名集合完全一致。
 */
@Suppress("DEPRECATION")
private fun signaturesAreCompatible(installed: PackageInfo, archive: PackageInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return signatureDigests(installed.signatures.orEmpty()) ==
            signatureDigests(archive.signatures.orEmpty())
    }
    val installedSigningInfo = installed.signingInfo ?: return false
    val archiveSigningInfo = archive.signingInfo ?: return false
    if (installedSigningInfo.hasMultipleSigners() || archiveSigningInfo.hasMultipleSigners()) {
        return signatureDigests(installedSigningInfo.apkContentsSigners.orEmpty()) ==
            signatureDigests(archiveSigningInfo.apkContentsSigners.orEmpty())
    }
    val installedCurrent = signatureDigests(installedSigningInfo.apkContentsSigners.orEmpty())
    val archiveHistory = signatureDigests(archiveSigningInfo.signingCertificateHistory.orEmpty())
    return installedCurrent.isNotEmpty() && archiveHistory.containsAll(installedCurrent)
}

/** 将签名证书转换为稳定 SHA-256 集合。 */
private fun signatureDigests(signatures: Array<out android.content.pm.Signature>): Set<String> {
    return signatures.mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
