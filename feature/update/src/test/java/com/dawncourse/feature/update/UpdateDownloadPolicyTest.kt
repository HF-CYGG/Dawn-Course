/**
 * 文件说明：验证应用内更新下载的文件命名与完整性门禁。
 */
package com.dawncourse.feature.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `下载目标始终使用应用生成的 apk 文件名`() {
        val request = updateInfo(
            versionName = "v1.0.6.0/../../恶意.zip",
            sha256 = "a".repeat(64)
        ).toApkDownloadRequest()

        requireNotNull(request)
        assertEquals("Dawn-Course-v1.0.6.0.apk", request.fileName)
        assertTrue(request.fileName.endsWith(".apk"))
        assertFalse(request.fileName.endsWith(".apk.zip"))
    }

    @Test
    fun `Gitee 即使按 zip 类型返回也只使用客户端生成的 apk 文件名`() {
        val giteeUrl =
            "https://gitee.com/YeMiao_cats/Dawn-Course/releases/download/v1.0.6.0/Dawn%20Course.apk"
        val request = updateInfo(
            versionName = "v1.0.6.0",
            downloadUrl = giteeUrl
        ).toApkDownloadRequest()

        requireNotNull(request)
        assertEquals(giteeUrl, request.url)
        val localFileName = resolveLocalUpdatePackageFileName(
            request = request,
            responseContentType = "application/zip",
            responseContentDisposition = "attachment; filename=\"Dawn Course.apk.zip\""
        )

        assertEquals("Dawn-Course-v1.0.6.0.apk", localFileName)
        assertFalse(localFileName.contains(".apk.zip", ignoreCase = true))
    }

    @Test
    fun `缺少或格式错误的 sha256 时拒绝应用内下载`() {
        assertNull(updateInfo(sha256 = null).toApkDownloadRequest())
        assertNull(updateInfo(sha256 = "1234").toApkDownloadRequest())
        assertNull(updateInfo(sha256 = "g".repeat(64)).toApkDownloadRequest())
    }

    @Test
    fun `下载内容必须同时匹配 apk 文件头和 sha256`() {
        val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "test-apk".toByteArray()
        val apkFile = temporaryFolder.newFile("candidate.part").apply {
            writeBytes(apkBytes)
        }

        assertTrue(
            verifyDownloadedApkPayload(
                file = apkFile,
                expectedSha256 = sha256(apkBytes)
            )
        )
        assertFalse(
            verifyDownloadedApkPayload(
                file = apkFile,
                expectedSha256 = "0".repeat(64)
            )
        )
    }

    @Test
    fun `即使哈希匹配也拒绝非 apk 内容`() {
        val htmlBytes = "<html>not an apk</html>".toByteArray()
        val htmlFile = temporaryFolder.newFile("candidate.part").apply {
            writeBytes(htmlBytes)
        }

        assertFalse(
            verifyDownloadedApkPayload(
                file = htmlFile,
                expectedSha256 = sha256(htmlBytes)
            )
        )
    }

    @Test
    fun `哈希读取期间会持续响应取消`() {
        val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) +
            ByteArray(DEFAULT_BUFFER_SIZE * 2) { 0x41 }
        val apkFile = temporaryFolder.newFile("large-candidate.part").apply {
            writeBytes(apkBytes)
        }
        var checkCount = 0

        val failure = runCatching {
            verifyDownloadedApkPayload(apkFile, sha256(apkBytes)) {
                checkCount += 1
                if (checkCount >= 2) throw CancellationException("test cancellation")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(checkCount >= 2)
    }

    @Test
    fun `缓存清理只删除过期 part 和非当前旧 apk`() {
        val updateDirectory = temporaryFolder.newFolder("update-cache")
        val nowMillis = 20L * 24L * 60L * 60L * 1000L
        val expectedFinalFile = File(updateDirectory, "Dawn-Course-v2.apk").apply {
            writeText("current")
            setLastModified(0L)
        }
        val expiredPart = File(updateDirectory, "download.part").apply {
            writeText("partial")
            setLastModified(nowMillis - INCOMPLETE_UPDATE_TTL_MILLIS)
        }
        val freshPart = File(updateDirectory, "active.part").apply {
            writeText("partial")
            setLastModified(nowMillis - INCOMPLETE_UPDATE_TTL_MILLIS + 1L)
        }
        val expiredApk = File(updateDirectory, "Dawn-Course-v1.apk").apply {
            writeText("old")
            setLastModified(nowMillis - OLD_UPDATE_APK_TTL_MILLIS)
        }
        val unrelated = File(updateDirectory, "keep.txt").apply {
            writeText("keep")
            setLastModified(0L)
        }

        cleanupStaleUpdateFiles(updateDirectory, expectedFinalFile, nowMillis)

        assertTrue(expectedFinalFile.exists())
        assertFalse(expiredPart.exists())
        assertTrue(freshPart.exists())
        assertFalse(expiredApk.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `下载进度只在值变化时通知`() {
        val emitted = mutableListOf<Int?>()
        val deduplicator = DownloadProgressDeduplicator { progress -> emitted.add(progress) }

        listOf<Int?>(null, null, 0, 0, 1, 1, 50, 50, 99).forEach(deduplicator::report)

        assertEquals(listOf<Int?>(null, 0, 1, 50, 99), emitted)
    }

    @Test
    fun `安装器只接受专用缓存目录中的 apk 文件`() {
        val cacheDirectory = temporaryFolder.newFolder("cache")
        val updateDirectory = File(cacheDirectory, UPDATE_PACKAGE_DIRECTORY).apply { mkdirs() }
        val verifiedApk = File(updateDirectory, "Dawn-Course-v1.0.6.0.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
        val incompleteFile = File(updateDirectory, "Dawn-Course-v1.0.6.0.apk.part").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
        val siblingApk = File(cacheDirectory, "outside.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }

        assertTrue(isManagedUpdateApk(cacheDirectory, verifiedApk))
        assertFalse(isManagedUpdateApk(cacheDirectory, incompleteFile))
        assertFalse(isManagedUpdateApk(cacheDirectory, siblingApk))
    }

    private fun updateInfo(
        versionName: String = "v1.0.6.0",
        sha256: String? = "a".repeat(64),
        downloadUrl: String = "https://downloads.example.com/not-really-an-apk.zip"
    ): UpdateInfo = UpdateInfo(
        versionCode = 139,
        versionName = versionName,
        title = "测试更新",
        content = "测试",
        downloadUrl = downloadUrl,
        releaseDate = "2026-09-02",
        sha256 = sha256
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
