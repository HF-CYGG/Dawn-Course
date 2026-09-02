package com.dawncourse.core.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dawncourse.core.domain.model.DiagnosticCleanupReport
import com.dawncourse.core.domain.model.DiagnosticSampleMetadata
import com.dawncourse.core.domain.model.RawDiagnosticRetentionAuthorization
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import com.dawncourse.core.domain.repository.DiagnosticSampleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断样本仓库生产实现。
 *
 * 存储根目录固定在 `noBackupFilesDir`，不会进入系统云备份或设备迁移；构造时进行一次
 * 最佳努力的过期清理，进入导入流程后还会由 ViewModel 再次显式触发。
 */
@Singleton
class DiagnosticSampleRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : DiagnosticSampleRepository {
    /** 私有文件策略实现。 */
    private val fileStore = DiagnosticSampleFileStore(
        rootDirectory = File(context.noBackupFilesDir, DIAGNOSTIC_DIRECTORY_NAME),
        rawCipher = AndroidKeystoreRawDiagnosticCipher()
    )

    init {
        runCatching { fileStore.cleanupExpired(System.currentTimeMillis()) }
    }

    /** 在 IO 线程保存脱敏副本。 */
    override suspend fun saveSanitized(
        sample: SanitizedDiagnosticSample
    ): Result<DiagnosticSampleMetadata> = withContext(Dispatchers.IO) {
        fileStore.saveSanitized(sample, System.currentTimeMillis())
    }

    /** 在 IO 线程保存用户明确授权的加密原文。 */
    override suspend fun saveRaw(
        rawContent: String,
        authorization: RawDiagnosticRetentionAuthorization
    ): Result<DiagnosticSampleMetadata> = withContext(Dispatchers.IO) {
        fileStore.saveRaw(rawContent, authorization, System.currentTimeMillis())
    }

    /** 独立处理每个文件的过期与损坏状态。 */
    override suspend fun cleanupExpired(): DiagnosticCleanupReport = withContext(Dispatchers.IO) {
        fileStore.cleanupExpired(System.currentTimeMillis())
    }

    /** 离开流程时只清理目标会话原文。 */
    override suspend fun clearRawForSession(importSessionId: String): DiagnosticCleanupReport = withContext(Dispatchers.IO) {
        fileStore.clearRawForSession(importSessionId)
    }

    private companion object {
        /** `noBackupFilesDir` 下的固定诊断目录。 */
        const val DIAGNOSTIC_DIRECTORY_NAME = "import_diagnostics"
    }
}

/** Android Keystore AES-256-GCM 原文加密适配器。 */
class AndroidKeystoreRawDiagnosticCipher : RawDiagnosticCipher {
    /** 加密时才允许创建本用途专属 Keystore key。 */
    override fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "raw diagnostic plaintext is empty" }
        val key = loadKey(createIfMissing = true) ?: error("diagnostic encryption key unavailable")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // Android Keystore 在 randomizedEncryptionRequired=true 时禁止调用方提供 IV。
        // 由 provider 生成唯一随机 IV，再与密文一同写入版本化信封。
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv.also { generatedIv -> require(generatedIv.size == GCM_IV_BYTES) }
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeInt(FORMAT_VERSION)
                data.writeInt(iv.size)
                data.write(iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
            output.toByteArray()
        }
    }

    /** 解密只读取既有 key；缺失、失效或认证失败均按损坏样本处理。 */
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val envelope = decodeEnvelope(ciphertext) ?: error("invalid encrypted diagnostic envelope")
        val key = loadKey(createIfMissing = false) ?: error("diagnostic decryption key unavailable")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(envelope.ciphertext)
    }

    /** 读取或按授权路径创建 Keystore 密钥，任何异常均转为安全失败。 */
    private fun loadKey(createIfMissing: Boolean): SecretKey? {
        return synchronized(KEY_CREATION_LOCK) {
            val keyStore = runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.getOrNull()
                ?: return@synchronized null
            val existing = runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()
            if (existing != null || !createIfMissing) {
                return@synchronized existing
            }
            runCatching {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val specification = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .build()
                generator.init(specification)
                generator.generateKey()
            }.getOrNull()
        }
    }

    /** 严格解析版本化密文信封，不读取任意长度缓冲区。 */
    private fun decodeEnvelope(bytes: ByteArray): EncryptedDiagnosticEnvelope? = runCatching {
        require(bytes.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            require(magic.contentEquals(MAGIC))
            require(data.readInt() == FORMAT_VERSION)
            val ivSize = data.readInt()
            require(ivSize == GCM_IV_BYTES)
            val iv = ByteArray(ivSize)
            data.readFully(iv)
            val ciphertextSize = data.readInt()
            require(ciphertextSize in MIN_GCM_CIPHERTEXT_BYTES..DiagnosticSampleFileStore.MAX_CONTENT_BYTES + ENVELOPE_OVERHEAD_BYTES)
            val encrypted = ByteArray(ciphertextSize)
            data.readFully(encrypted)
            require(data.read() == -1)
            EncryptedDiagnosticEnvelope(iv = iv, ciphertext = encrypted)
        }
    }.getOrNull()

    /** 已校验的 AES-GCM 信封字段。 */
    private data class EncryptedDiagnosticEnvelope(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dawn_import_raw_diagnostics_v1"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val FORMAT_VERSION = 1
        const val MIN_GCM_CIPHERTEXT_BYTES = 16
        const val ENVELOPE_OVERHEAD_BYTES = 64 * 1024
        const val MIN_ENVELOPE_BYTES = 32
        const val MAX_ENVELOPE_BYTES = DiagnosticSampleFileStore.MAX_CONTENT_BYTES + ENVELOPE_OVERHEAD_BYTES
        val MAGIC = "DAWN-RAW-DIAG".toByteArray(Charsets.US_ASCII)
        val AAD = "dawn-import-raw-diagnostic:v1".toByteArray(Charsets.US_ASCII)
        /** 同进程内完整串行化 Keystore alias 的首次查找与创建。 */
        val KEY_CREATION_LOCK = Any()
    }
}
