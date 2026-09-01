package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.RawDiagnosticRetentionAuthorization
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import java.nio.file.Files
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 诊断样本文件存储的安全边界测试。 */
class DiagnosticSampleFileStoreTest {
    /** 每个测试独占的应用私有目录替身。 */
    private lateinit var rootDirectory: java.io.File

    /** 固定测试密钥，仅用于证明原文不会直接落盘。 */
    private lateinit var cipher: RawDiagnosticCipher

    @Before
    fun setUp() {
        rootDirectory = Files.createTempDirectory("dawn-diagnostics").toFile()
        cipher = JvmAesGcmDiagnosticCipher()
    }

    @After
    fun tearDown() {
        rootDirectory.deleteRecursively()
    }

    /** 默认路径只保存调用方已经脱敏的副本。 */
    @Test
    fun saveSanitized_doesNotPersistRawContent() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val rawSecret = "姓名：张三 手机：13812345678"
        val sample = SanitizedDiagnosticSample(
            importSessionId = SESSION_ID,
            sanitizerVersion = 1,
            contentSha256 = sha256("姓名：*** 手机：***********"),
            content = "姓名：*** 手机：***********"
        )

        val result = store.saveSanitized(sample, NOW)

        assertTrue(result.isSuccess)
        val diskBytes = rootDirectory.walkTopDown()
            .filter(java.io.File::isFile)
            .flatMap { file -> file.readBytes().asSequence() }
            .toList()
            .toByteArray()
            .toString(Charsets.UTF_8)
        assertFalse(diskBytes.contains(rawSecret))
        assertFalse(diskBytes.contains("张三"))
        assertFalse(diskBytes.contains("13812345678"))
    }

    /** 原文保存必须持有近期、明确授权，并固定为 24 小时 TTL。 */
    @Test
    fun saveRaw_requiresExplicitAuthorizationAndEncryptsForExactly24Hours() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val denied = RawDiagnosticRetentionAuthorization.create(
            importSessionId = SESSION_ID,
            userApproved = false,
            grantedAtEpochMillis = NOW
        )
        assertTrue(denied.isFailure)

        val authorization = RawDiagnosticRetentionAuthorization.create(
            importSessionId = SESSION_ID,
            userApproved = true,
            grantedAtEpochMillis = NOW
        ).getOrThrow()
        val raw = "学号：2026123456"

        val result = store.saveRaw(raw, authorization, NOW).getOrThrow()

        assertEquals(NOW + DiagnosticSampleFileStore.RAW_RETENTION_MILLIS, result.expiresAtEpochMillis)
        val persisted = rootDirectory.walkTopDown()
            .filter(java.io.File::isFile)
            .flatMap { file -> file.readBytes().asSequence() }
            .toList()
            .toByteArray()
            .toString(Charsets.UTF_8)
        assertFalse(persisted.contains(raw))
    }

    /** 旧授权不能被跨流程复用。 */
    @Test
    fun saveRaw_rejectsStaleAuthorization() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val staleAuthorization = RawDiagnosticRetentionAuthorization.create(
            importSessionId = SESSION_ID,
            userApproved = true,
            grantedAtEpochMillis = NOW - DiagnosticSampleFileStore.AUTHORIZATION_FRESHNESS_MILLIS - 1
        ).getOrThrow()

        val result = store.saveRaw("raw", staleAuthorization, NOW)

        assertTrue(result.isFailure)
        assertEquals(emptyList<java.io.File>(), rootDirectory.listFiles()?.toList().orEmpty())
    }

    /** 恶意 sessionId 必须在任何路径计算或目录创建前被拒绝。 */
    @Test
    fun save_rejectsMaliciousSessionId() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val sample = SanitizedDiagnosticSample(
            importSessionId = "../../outside",
            sanitizerVersion = 1,
            contentSha256 = sha256("redacted"),
            content = "redacted"
        )

        val result = store.saveSanitized(sample, NOW)

        assertTrue(result.isFailure)
        assertEquals(emptyList<java.io.File>(), rootDirectory.listFiles()?.toList().orEmpty())
    }

    /** 超大页面必须在落盘前拒绝。 */
    @Test
    fun saveSanitized_rejectsOversizedContent() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val oversizedContent = "x".repeat(DiagnosticSampleFileStore.MAX_CONTENT_BYTES + 1)
        val sample = SanitizedDiagnosticSample(
            importSessionId = SESSION_ID,
            sanitizerVersion = 1,
            contentSha256 = sha256(oversizedContent),
            content = oversizedContent
        )

        val result = store.saveSanitized(sample, NOW)

        assertTrue(result.isFailure)
        assertEquals(emptyList<java.io.File>(), rootDirectory.listFiles()?.toList().orEmpty())
    }

    /** 调用方声明的 hash 必须与实际脱敏内容恒等，不能只校验格式。 */
    @Test
    fun saveSanitized_rejectsContentHashMismatch() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val sample = SanitizedDiagnosticSample(
            importSessionId = SESSION_ID,
            sanitizerVersion = 1,
            contentSha256 = sha256("different content"),
            content = "redacted"
        )

        val result = store.saveSanitized(sample, NOW)

        assertTrue(result.isFailure)
        assertEquals(emptyList<java.io.File>(), rootDirectory.listFiles()?.toList().orEmpty())
    }

    /** 退出流程只删除当前 session 的原文，且不影响其他 session。 */
    @Test
    fun clearRawForSession_isolatesOtherSamples() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val first = RawDiagnosticRetentionAuthorization.create(SESSION_ID, true, NOW).getOrThrow()
        val second = RawDiagnosticRetentionAuthorization.create(OTHER_SESSION_ID, true, NOW).getOrThrow()
        store.saveRaw("first raw", first, NOW).getOrThrow()
        store.saveRaw("second raw", second, NOW).getOrThrow()

        val report = store.clearRawForSession(SESSION_ID)

        assertEquals(1, report.removedCount)
        assertEquals(1, store.listRawFilesForTest().size)
    }

    /** 启动清理必须删除过期与损坏文件，并继续处理其它有效样本。 */
    @Test
    fun cleanupExpired_handlesCorruptFileWithoutStoppingOtherSamples() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val expired = RawDiagnosticRetentionAuthorization.create(SESSION_ID, true, NOW).getOrThrow()
        val validSavedAt = NOW + DiagnosticSampleFileStore.RAW_RETENTION_MILLIS - 1
        val valid = RawDiagnosticRetentionAuthorization.create(OTHER_SESSION_ID, true, validSavedAt).getOrThrow()
        store.saveRaw("expired", expired, NOW).getOrThrow()
        store.saveRaw("valid", valid, validSavedAt).getOrThrow()
        store.rawDirectoryForTest().resolve("broken.diag").writeText("not encrypted")

        val report = store.cleanupExpired(NOW + DiagnosticSampleFileStore.RAW_RETENTION_MILLIS)

        assertEquals(2, report.removedCount)
        assertEquals(1, report.corruptCount)
        assertEquals(1, store.listRawFilesForTest().size)
    }

    /** 超限损坏文件必须按长度直接拒绝，不能先整文件读入内存。 */
    @Test
    fun cleanupExpired_rejectsOversizedCorruptFile() {
        val store = DiagnosticSampleFileStore(rootDirectory, cipher)
        val oversized = store.rawDirectoryForTest()
            .resolve("r_${"0".repeat(32)}_${NOW + DiagnosticSampleFileStore.RAW_RETENTION_MILLIS}.diag")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(DiagnosticSampleFileStore.MAX_CONTENT_BYTES.toLong() + 64L * 1024L + 1L)
        }

        val report = store.cleanupExpired(NOW)

        assertEquals(1, report.removedCount)
        assertEquals(1, report.corruptCount)
        assertFalse(oversized.exists())
    }

    /** JVM 测试使用的 AES-GCM 加密器，行为与 Android Keystore 生产适配器一致。 */
    private class JvmAesGcmDiagnosticCipher : RawDiagnosticCipher {
        /** 固定 256 位测试密钥。 */
        private val key = SecretKeySpec(ByteArray(32) { index -> index.toByte() }, "AES")

        /** 使用固定测试 IV 生成不可读密文。 */
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12) { index -> (index + 1).toByte() }
            val algorithm = Cipher.getInstance("AES/GCM/NoPadding")
            algorithm.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + algorithm.doFinal(plaintext)
        }

        /** 解开测试密文。 */
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            val iv = ciphertext.copyOfRange(0, 12)
            val algorithm = Cipher.getInstance("AES/GCM/NoPadding")
            algorithm.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return algorithm.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
    }

    private companion object {
        /** 测试输入的真实 SHA-256。 */
        fun sha256(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }

        /** 固定测试时间。 */
        const val NOW = 1_800_000_000_000L

        /** 合法 UUID 会话 ID。 */
        const val SESSION_ID = "11111111-1111-4111-8111-111111111111"

        /** 另一个合法 UUID 会话 ID。 */
        const val OTHER_SESSION_ID = "22222222-2222-4222-8222-222222222222"

    }
}
