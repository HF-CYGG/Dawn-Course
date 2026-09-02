package com.dawncourse.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.domain.model.RawDiagnosticRetentionAuthorization
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真机验证 no-backup 路径与 Android Keystore 原文加密。 */
@RunWith(AndroidJUnit4::class)
class DiagnosticSampleRepositoryInstrumentedTest {
    /** 目标应用上下文。 */
    private val context: android.content.Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 位于 noBackupFilesDir 下且每次测试独占的目录。 */
    private lateinit var diagnosticRoot: File

    /** 使用生产 Android Keystore cipher 的文件存储。 */
    private lateinit var fileStore: DiagnosticSampleFileStore

    @Before
    fun setUp() {
        diagnosticRoot = File(context.noBackupFilesDir, "diagnostic-test/${UUID.randomUUID()}")
        check(diagnosticRoot.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        fileStore = DiagnosticSampleFileStore(diagnosticRoot, AndroidKeystoreRawDiagnosticCipher())
    }

    @After
    fun tearDown() {
        check(diagnosticRoot.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        diagnosticRoot.deleteRecursively()
    }

    /** 生产仓库必须把脱敏副本写入 noBackupFilesDir。 */
    @Test
    fun saveSanitized_usesNoBackupDirectory() {
        val sessionId = UUID.randomUUID().toString()
        val content = "姓名：***"

        val result = fileStore.saveSanitized(
            SanitizedDiagnosticSample(
                importSessionId = sessionId,
                sanitizerVersion = 2,
                contentSha256 = sha256(content),
                content = content
            ),
            System.currentTimeMillis()
        )

        assertTrue(result.exceptionOrNull()?.javaClass?.simpleName.orEmpty(), result.isSuccess)
        assertTrue(newFilesIn("sanitized").singleOrNull()?.isFile == true)
    }

    /** 原文密文不得包含可直接搜索的页面内容。 */
    @Test
    fun saveRaw_usesKeystoreCiphertext() {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val authorization = RawDiagnosticRetentionAuthorization.create(sessionId, true, now).getOrThrow()
        val raw = "学号：2026123456"

        val result = fileStore.saveRaw(raw, authorization, now)

        assertTrue(result.exceptionOrNull()?.javaClass?.simpleName.orEmpty(), result.isSuccess)
        val ciphertext = newFilesIn("raw").singleOrNull()?.readBytes() ?: ByteArray(0)
        assertTrue(ciphertext.isNotEmpty())
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(raw))
    }

    /** 返回当前测试新建且位于指定子目录的文件。 */
    private fun newFilesIn(directoryName: String): List<File> {
        val directory = File(diagnosticRoot, directoryName)
        return directory.listFiles()
            ?.filter(File::isFile)
            .orEmpty()
    }

    /** 与生产契约一致的 UTF-8 SHA-256。 */
    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
