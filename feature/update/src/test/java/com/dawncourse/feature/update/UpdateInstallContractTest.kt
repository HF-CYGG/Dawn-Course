/**
 * 文件说明：锁定应用内更新安装的 Manifest 与最小文件共享边界。
 */
package com.dawncourse.feature.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallContractTest {

    @Test
    fun `更新模块声明安装权限与非导出 FileProvider`() {
        val manifest = File("src/main/AndroidManifest.xml")
            .also { file -> assertTrue("缺少更新模块 Manifest", file.isFile) }
            .readText()

        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("android:name=\".UpdateFileProvider\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertTrue(manifest.contains("@xml/update_file_paths"))
    }

    @Test
    fun `FileProvider 只共享专用更新缓存目录`() {
        val paths = File("src/main/res/xml/update_file_paths.xml")
            .also { file -> assertTrue("缺少更新包共享路径配置", file.isFile) }
            .readText()

        assertTrue(paths.contains("<cache-path"))
        assertTrue(paths.contains("path=\"update_packages/\""))
        assertFalse(paths.contains("<root-path"))
        assertFalse(paths.contains("<external-path"))
        assertFalse(paths.contains("path=\".\""))
    }

    @Test
    fun `下载取消会终止 OkHttp 且每次使用独立临时文件`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/update/UpdatePackageDownloader.kt"
        ).also { file -> assertTrue("缺少更新包下载器", file.isFile) }.readText()

        assertTrue(source.contains("suspendCancellableCoroutine"))
        assertTrue(source.contains("invokeOnCancellation"))
        assertTrue(source.contains("call.cancel()"))
        assertTrue(source.contains("File.createTempFile"))
        assertFalse(source.contains("call.execute()"))
    }

    @Test
    fun `交给安装器前重新校验哈希与 APK 身份`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/update/UpdateInstaller.kt"
        ).also { file -> assertTrue("缺少更新安装器", file.isFile) }.readText()

        assertTrue(source.contains("suspend fun prepare"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("verifyDownloadedApkPayload"))
        assertTrue(source.contains("verifyDownloadedApkIdentity"))
        assertTrue(source.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("APK_MIME_TYPE"))
        assertFalse(source.contains("FLAG_ACTIVITY_NEW_TASK"))
    }

    @Test
    fun `交给外部授权或安装页后状态可恢复且不会重复拉起`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/update/UpdateViewModel.kt"
        ).also { file -> assertTrue("缺少更新 ViewModel", file.isFile) }.readText()

        assertTrue(source.contains("InstallHandoff"))
        assertTrue(source.contains("markInstallHandoffStarted"))
        assertTrue(source.contains("is UpdateUiState.InstallHandoff"))
    }
}
