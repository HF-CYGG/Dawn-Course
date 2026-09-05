/**
 * 文件说明：把已验证 APK 安全交给 Android 系统安装器。
 */
package com.dawncourse.feature.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 系统安装流程下一步。 */
sealed interface UpdateInstallAction {
    /** 需要先由用户允许 Dawn Course 安装未知来源应用。 */
    data class RequestPermission(val intent: Intent) : UpdateInstallAction

    /** 可以直接打开系统安装确认页。 */
    data class LaunchInstaller(val intent: Intent) : UpdateInstallAction

    /** 文件不在受控目录、已被删除或不再是 APK。 */
    data object InvalidPackage : UpdateInstallAction
}

/** 更新安装 Intent 构建器。 */
object UpdateInstaller {
    /**
     * 根据系统授权状态生成下一步操作。
     *
     * 普通应用不能静默安装；所谓“自动安装”是校验完成后自动进入系统确认页。
     */
    suspend fun prepare(
        context: Context,
        updatePackage: DownloadedUpdatePackage
    ): UpdateInstallAction = withContext(Dispatchers.IO) {
        val verificationContext = currentCoroutineContext()
        val apkFile = File(updatePackage.filePath)
        if (!isManagedUpdateApk(context.cacheDir, apkFile) ||
            apkFile.name != updatePackage.fileName ||
            !verifyDownloadedApkPayload(
                apkFile,
                updatePackage.expectedSha256,
                verificationContext::ensureActive
            ) ||
            !verifyDownloadedApkIdentity(
                context = context,
                file = apkFile,
                expectedVersionCode = updatePackage.expectedVersionCode
            )
        ) {
            return@withContext UpdateInstallAction.InvalidPackage
        }
        verificationContext.ensureActive()
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            return@withContext UpdateInstallAction.RequestPermission(permissionIntent)
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.update-file-provider",
                apkFile
            )
        }.getOrNull() ?: return@withContext UpdateInstallAction.InvalidPackage
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Dawn Course update", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        UpdateInstallAction.LaunchInstaller(installIntent)
    }
}

/** 只允许安装下载器完成验证后放入专用缓存目录的 `.apk` 文件。 */
internal fun isManagedUpdateApk(cacheDirectory: File, candidate: File): Boolean {
    if (!candidate.isFile || !candidate.name.endsWith(".apk", ignoreCase = true)) return false
    val managedDirectory = File(cacheDirectory, UPDATE_PACKAGE_DIRECTORY)
    return runCatching {
        candidate.canonicalFile.parentFile == managedDirectory.canonicalFile
    }.getOrDefault(false)
}
