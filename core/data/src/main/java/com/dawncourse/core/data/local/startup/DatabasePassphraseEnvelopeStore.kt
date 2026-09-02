package com.dawncourse.core.data.local.startup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 明确区分 SQLCipher 旧派生口令与 raw-key literal 的受控密钥材料。
 *
 * SQLCipher 会把普通字节当作 passphrase 再执行 KDF；raw key 必须转换成
 * `x'<64 hex>'` 字面量。所有数据库入口都接收本类型，禁止用裸 [ByteArray]
 * 隐式表达密钥模式。
 */
sealed class DatabaseKeyMaterial private constructor(
    private var keyBytes: ByteArray?
) : AutoCloseable {
    /** 持久协议中的密钥模式。 */
    enum class Mode {
        LEGACY_PASSPHRASE,
        RAW_KEY_LITERAL
    }

    /** 当前材料的明确模式。 */
    abstract val mode: Mode

    /**
     * 在受控作用域内提供 SQLCipher API 需要的字节。
     *
     * Legacy 模式提供原口令；Raw 模式提供临时的 `x'<64 hex>'` ASCII 字面量。
     * 调用方不得保存数组引用，也不得写入日志。
     */
    abstract fun <T> useSqlCipherBytes(block: (ByteArray) -> T): T

    /** 仅供信封加解密使用原始 32 字节；不得传给 SQLCipher。 */
    internal fun <T> useStoredBytes(block: (ByteArray) -> T): T = block(requireKeyBytes())

    protected fun requireKeyBytes(): ByteArray = checkNotNull(keyBytes) { "SQLCipher 密钥材料已清零" }

    /** 尽力清空内存中的密钥字节。 */
    override fun close() {
        keyBytes?.fill(0)
        keyBytes = null
    }

    /** v1 信封解封后的旧派生口令。 */
    class LegacyPassphrase private constructor(bytes: ByteArray) : DatabaseKeyMaterial(bytes) {
        override val mode: Mode = Mode.LEGACY_PASSPHRASE

        override fun <T> useSqlCipherBytes(block: (ByteArray) -> T): T = block(requireKeyBytes())

        internal companion object {
            fun fromBytes(bytes: ByteArray): LegacyPassphrase = LegacyPassphrase(bytes.copyOf())

            fun takeOwnership(bytes: ByteArray): LegacyPassphrase = LegacyPassphrase(bytes)
        }
    }

    /** v2 信封解封后的 32 字节 raw key。 */
    class RawKeyLiteral private constructor(bytes: ByteArray) : DatabaseKeyMaterial(bytes) {
        override val mode: Mode = Mode.RAW_KEY_LITERAL

        override fun <T> useSqlCipherBytes(block: (ByteArray) -> T): T {
            val literal = requireKeyBytes().toSqlCipherRawLiteral()
            return try {
                block(literal)
            } finally {
                literal.clearSensitiveBytes()
            }
        }

        internal companion object {
            fun fromBytes(bytes: ByteArray): RawKeyLiteral = RawKeyLiteral(bytes.copyOf())

            fun takeOwnership(bytes: ByteArray): RawKeyLiteral = RawKeyLiteral(bytes)
        }
    }
}

/** 已有信封的读取结果；Invalid 不暴露底层异常，避免错误链路泄漏密钥信息。 */
sealed interface ExistingPassphraseResult {
    /** 已成功通过 Keystore 解封的口令。 */
    data class Available(
        val keyMaterial: DatabaseKeyMaterial
    ) : ExistingPassphraseResult

    /** 信封文件不存在。 */
    data object Missing : ExistingPassphraseResult

    /** 信封格式、别名、Keystore 密钥或 GCM 认证校验失败。 */
    data class Invalid(
        val reason: KeyEnvelopeFailureReason
    ) : ExistingPassphraseResult
}

/** 创建首个信封的结果；ExistingEnvelope 明确禁止覆盖。 */
sealed interface NewPassphraseResult {
    /** 已创建并持久化的新口令。 */
    data class Available(
        val keyMaterial: DatabaseKeyMaterial
    ) : NewPassphraseResult

    /** 创建前发现已有信封，调用方必须重新评估，不可覆盖。 */
    data object ExistingEnvelope : NewPassphraseResult

    /** 生成、Keystore 或原子写入失败。 */
    data class Failed(
        val reason: KeyEnvelopeFailureReason
    ) : NewPassphraseResult
}

/** 信封失败的稳定分类，仅用于恢复决策和审计。 */
enum class KeyEnvelopeFailureReason {
    /** 信封二进制格式不受支持或内容不完整。 */
    InvalidEnvelopeFormat,

    /** Android Keystore 不存在对应别名或无法读取该密钥。 */
    KeyUnavailable,

    /** AES-GCM 解封或认证失败。 */
    UnwrapFailed,

    /** 随机口令或加密信封无法创建。 */
    ProvisioningFailed,

    /** 原子写入信封失败。 */
    AtomicWriteFailed
}

/** Keystore 密钥访问结果；禁止导出密钥原始字节，仅可交给 Cipher 使用。 */
sealed interface KeyEncryptionKeyResult {
    /** 仅供 AES-GCM Cipher 使用的 Android Keystore 对称密钥。 */
    data class Available(
        val key: SecretKey
    ) : KeyEncryptionKeyResult

    /** 既有密钥缺失、失效或访问异常。 */
    data object MissingOrInvalid : KeyEncryptionKeyResult
}

/** Android Keystore 的可替换访问接口。 */
interface KeyEncryptionKeyProvider {
    /** 仅查询既有别名；本方法绝不能创建新密钥。 */
    fun getExisting(alias: String): KeyEncryptionKeyResult

    /** 仅为首次创建数据库或明文迁移创建别名。 */
    fun getOrCreate(alias: String): KeyEncryptionKeyResult
}

/** 可替换的原子字节存储接口，生产实现必须保证写入失败不会替换原文件。 */
interface AtomicByteStore {
    /**
     * 在同一跨进程互斥区内执行完整操作。
     *
     * 首次信封创建必须把“检查不存在、创建 Keystore alias、加密、落盘”全部放在此区间，
     * 否则两个进程可能让后创建的 Keystore 密钥覆盖先创建的同名密钥。
     */
    fun <T> withExclusiveLock(block: () -> T): T

    /** 读取当前字节；调用方在使用后会清空返回数组。 */
    fun readOrNull(): ByteArray?

    /** 原子持久化完整字节；失败时必须保留原内容。 */
    fun writeAtomically(bytes: ByteArray)

}

/** 可替换的随机字节来源，便于 JVM 测试验证不覆盖语义。 */
fun interface SecureRandomByteSource {
    /** 生成指定长度的随机字节。 */
    fun nextBytes(size: Int): ByteArray
}

/** 基于 JCA SecureRandom 的生产随机字节来源。 */
class JcaSecureRandomByteSource(
    private val secureRandom: SecureRandom = SecureRandom()
) : SecureRandomByteSource {
    /** 生成密码学安全随机字节。 */
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)
}

/** SQLCipher 口令信封能力的可替换接口。 */
interface DatabasePassphraseEnvelopeStore {
    /** 只读取并解封既有信封；任何失败都不能生成替代口令。 */
    fun loadExisting(): ExistingPassphraseResult

    /** 只在调用方已确认允许首次创建时调用；已有信封必须拒绝覆盖。 */
    fun createNew(): NewPassphraseResult
}

/**
 * 使用 Android Keystore AES-GCM 包裹随机 SQLCipher 口令的实现。
 *
 * 二进制信封同时带格式版本、Keystore 别名版本、IV 与密文；固定版本化 AAD 绑定用途，
 * 避免同一 Keystore 密钥被误用于其它数据。此类不依赖 Context，便于通过接口完整单测。
 */
class DatabaseKeyEnvelopeStore(
    private val atomicByteStore: AtomicByteStore,
    private val keyProvider: KeyEncryptionKeyProvider,
    private val randomByteSource: SecureRandomByteSource = JcaSecureRandomByteSource()
) : DatabasePassphraseEnvelopeStore {
    /** 只读取既有信封，不会调用 getOrCreate 或写入存储。 */
    override fun loadExisting(): ExistingPassphraseResult =
        DawnStartupTrace.section(DawnStartupTrace.KEYSTORE_UNSEAL) {
        val serialized = try {
            atomicByteStore.readOrNull()
        } catch (_: Throwable) {
            return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.InvalidEnvelopeFormat)
        } ?: return ExistingPassphraseResult.Missing
        var decrypted: ByteArray? = null
        try {
            val envelope = DatabaseKeyEnvelope.decode(serialized)
                ?: return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.InvalidEnvelopeFormat)
            val key = (keyProvider.getExisting(envelope.keyAlias()) as? KeyEncryptionKeyResult.Available)
                ?.key
                ?: return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.KeyUnavailable)
            decrypted = decrypt(envelope, key)
                ?: return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.UnwrapFailed)
            if (decrypted.size != SQL_CIPHER_KEY_BYTES) {
                return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.UnwrapFailed)
            }
            val keyMaterial = when (envelope.formatVersion) {
                LEGACY_ENVELOPE_FORMAT_VERSION -> DatabaseKeyMaterial.LegacyPassphrase.takeOwnership(decrypted)
                RAW_KEY_ENVELOPE_FORMAT_VERSION -> DatabaseKeyMaterial.RawKeyLiteral.takeOwnership(decrypted)
                else -> return ExistingPassphraseResult.Invalid(KeyEnvelopeFailureReason.InvalidEnvelopeFormat)
            }
            decrypted = null
            return ExistingPassphraseResult.Available(keyMaterial)
        } finally {
            serialized.clearSensitiveBytes()
            decrypted?.clearSensitiveBytes()
        }
    }

    /** 创建第一个信封；任何已有字节均拒绝覆盖，即使其格式已损坏。 */
    override fun createNew(): NewPassphraseResult {
        return try {
            atomicByteStore.withExclusiveLock {
                createNewWhileLocked(allowKeyProvisioning = true)
            }
        } catch (_: Throwable) {
            NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
        }
    }

    /**
     * rekey 专用 staged v2 信封：只能复用刚刚成功解封 v1 的既有 KEK。
     *
     * 此路径绝不调用 getOrCreate；别名临时不可用时立即失败，避免覆盖同名 KEK 后让
     * legacy envelope/pre-image 永久失去解封能力。
     */
    internal fun createNewWithExistingKey(): NewPassphraseResult {
        return try {
            atomicByteStore.withExclusiveLock {
                createNewWhileLocked(allowKeyProvisioning = false)
            }
        } catch (_: Throwable) {
            NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
        }
    }

    /** 在调用方持有跨进程文件锁时完成不可分割的首次信封创建。 */
    private fun createNewWhileLocked(allowKeyProvisioning: Boolean): NewPassphraseResult {
        val existing = try {
            atomicByteStore.readOrNull()
        } catch (_: Throwable) {
            return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.AtomicWriteFailed)
        }
        if (existing != null) {
            existing.clearSensitiveBytes()
            return NewPassphraseResult.ExistingEnvelope
        }

        var rawKey: ByteArray? = null
        var serialized: ByteArray? = null
        try {
            rawKey = randomByteSource.nextBytes(SQL_CIPHER_KEY_BYTES)
            if (rawKey.size != SQL_CIPHER_KEY_BYTES) {
                return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
            }
            val keyResult = if (allowKeyProvisioning) {
                keyProvider.getOrCreate(KEY_ALIAS)
            } else {
                keyProvider.getExisting(KEY_ALIAS)
            }
            val key = (keyResult as? KeyEncryptionKeyResult.Available)?.key
                ?: return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.KeyUnavailable)
            val envelope = encrypt(rawKey, key, RAW_KEY_ENVELOPE_FORMAT_VERSION)
                ?: return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
            serialized = envelope.encode()
            try {
                atomicByteStore.writeAtomically(serialized)
            } catch (_: Throwable) {
                return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.AtomicWriteFailed)
            }
            val material = DatabaseKeyMaterial.RawKeyLiteral.takeOwnership(rawKey)
            rawKey = null
            return NewPassphraseResult.Available(material)
        } catch (_: Throwable) {
            return NewPassphraseResult.Failed(KeyEnvelopeFailureReason.ProvisioningFailed)
        } finally {
            rawKey?.clearSensitiveBytes()
            serialized?.clearSensitiveBytes()
        }
    }

    /** 使用固定 AAD 认证信封的用途与版本。 */
    private fun encrypt(rawKey: ByteArray, key: SecretKey, formatVersion: Int): DatabaseKeyEnvelope? = try {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aadFor(formatVersion))
        DatabaseKeyEnvelope(
            formatVersion = formatVersion,
            keyAliasVersion = KEY_ALIAS_VERSION,
            initializationVector = cipher.iv,
            ciphertext = cipher.doFinal(rawKey)
        )
    } catch (_: Throwable) {
        null
    }

    /** AES-GCM 认证失败时统一返回 null，不让底层异常进入恢复 UI 或日志。 */
    private fun decrypt(envelope: DatabaseKeyEnvelope, key: SecretKey): ByteArray? = try {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_AUTH_TAG_BITS, envelope.initializationVector)
        )
        cipher.updateAAD(aadFor(envelope.formatVersion))
        cipher.doFinal(envelope.ciphertext)
    } catch (_: Throwable) {
        null
    }

    private companion object {
        /** SQLCipher 随机口令长度。 */
        const val SQL_CIPHER_KEY_BYTES = 32

        /** AES-GCM 算法名称。 */
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 认证标签长度（位）。 */
        const val GCM_AUTH_TAG_BITS = 128

        /** 历史信封：32 字节作为 passphrase 进入 SQLCipher KDF。 */
        const val LEGACY_ENVELOPE_FORMAT_VERSION = 1

        /** 当前信封：32 字节编码为 SQLCipher raw-key literal。 */
        const val RAW_KEY_ENVELOPE_FORMAT_VERSION = 2

        /** Keystore 别名版本，轮换时递增并兼容读取旧版本。 */
        const val KEY_ALIAS_VERSION = 1

        /** 版本化 Keystore 别名。 */
        const val KEY_ALIAS = "com.dawncourse.database.key.v1"

        fun aadFor(formatVersion: Int): ByteArray =
            "dawn-course/database-key-envelope/v$formatVersion".toByteArray(Charsets.UTF_8)
    }
}

/** 信封的严格二进制编码，避免通用 JSON 反序列化接受未知字段或超大输入。 */
private data class DatabaseKeyEnvelope(
    val formatVersion: Int,
    val keyAliasVersion: Int,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray
) {
    /** 返回当前版本的 Keystore 别名；不支持的版本会在 decode 阶段拒绝。 */
    fun keyAlias(): String = "com.dawncourse.database.key.v$keyAliasVersion"

    /** 使用长度受限的固定格式编码。 */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + initializationVector.size + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(formatVersion)
        buffer.putInt(keyAliasVersion)
        buffer.putInt(initializationVector.size)
        buffer.putInt(ciphertext.size)
        buffer.put(initializationVector)
        buffer.put(ciphertext)
        return buffer.array()
    }

    companion object {
        /** 解析并验证所有长度与版本，防止损坏文件诱发过量分配。 */
        fun decode(bytes: ByteArray): DatabaseKeyEnvelope? {
            if (bytes.size < HEADER_BYTES || bytes.size > MAX_ENVELOPE_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val formatVersion = buffer.int
            val keyAliasVersion = buffer.int
            val ivLength = buffer.int
            val ciphertextLength = buffer.int
            if (formatVersion !in SUPPORTED_FORMAT_VERSIONS || keyAliasVersion != CURRENT_KEY_ALIAS_VERSION) {
                return null
            }
            if (ivLength != GCM_IV_BYTES || ciphertextLength != GCM_CIPHERTEXT_BYTES) return null
            if (buffer.remaining() != ivLength + ciphertextLength) return null
            val iv = ByteArray(ivLength)
            val ciphertext = ByteArray(ciphertextLength)
            buffer.get(iv)
            buffer.get(ciphertext)
            return DatabaseKeyEnvelope(formatVersion, keyAliasVersion, iv, ciphertext)
        }

        /** 文件头魔数。 */
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())

        /** 固定头部长度。 */
        private const val HEADER_BYTES = 20

        /** 允许的最大信封长度，拒绝意外大文件。 */
        private const val MAX_ENVELOPE_BYTES = 1024

        /** 可读取 v1 旧口令和 v2 raw key。 */
        private val SUPPORTED_FORMAT_VERSIONS = setOf(1, 2)

        /** 当前 Keystore 别名版本。 */
        private const val CURRENT_KEY_ALIAS_VERSION = 1

        /** GCM 建议 IV 长度。 */
        private const val GCM_IV_BYTES = 12

        /** 32 字节口令 + 16 字节 GCM 标签。 */
        private const val GCM_CIPHERTEXT_BYTES = 48
    }
}

/** 尽力清空临时敏感数组；JVM 不保证不会存在运行时复制。 */
private fun ByteArray.clearSensitiveBytes() {
    fill(0)
}

/** 把 32 字节 raw key 编码成 SQLCipher 认可的 67 字节 ASCII 字面量。 */
private fun ByteArray.toSqlCipherRawLiteral(): ByteArray {
    require(size == 32) { "SQLCipher raw key 必须为 32 字节" }
    val digits = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
    return ByteArray(67).also { output ->
        output[0] = 'x'.code.toByte()
        output[1] = '\''.code.toByte()
        forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            output[2 + index * 2] = digits[unsigned ushr 4]
            output[3 + index * 2] = digits[unsigned and 0x0f]
        }
        output[66] = '\''.code.toByte()
    }
}
