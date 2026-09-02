package com.dawncourse.core.data.local.startup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.KeyGenerator

/** 基于数据库主文件的保守检查器；非明文 SQLite 一律不透明处理。 */
class SqliteDatabaseFileInspector(
    private val databaseFile: File
) : DatabaseFileInspector {
    /** 只读取 SQLite 文件头，不会尝试用错误口令打开数据库。 */
    override fun inspect(): DatabaseFileInspection {
        if (!databaseFile.exists()) return DatabaseFileInspection.NoDatabase
        if (!databaseFile.isFile || databaseFile.length() < SQLITE_HEADER.size) {
            return DatabaseFileInspection.CorruptOrUnknown
        }
        return try {
            val header = ByteArray(SQLITE_HEADER.size)
            databaseFile.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read <= 0) return DatabaseFileInspection.CorruptOrUnknown
                    offset += read
                }
            }
            if (header.contentEquals(SQLITE_HEADER)) {
                DatabaseFileInspection.PlaintextSqlite
            } else {
                DatabaseFileInspection.OpaqueData
            }
        } catch (_: Throwable) {
            DatabaseFileInspection.CorruptOrUnknown
        }
    }

    private companion object {
        /** SQLite v3 固定文件头。 */
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}

/** Android Keystore AES-GCM 密钥提供者；只将 SecretKey 交给 Cipher，绝不导出原始字节。 */
class AndroidKeystoreAesGcmKeyProvider : KeyEncryptionKeyProvider {
    /** 仅查询既有 Keystore 别名，缺失或异常均返回安全失败。 */
    override fun getExisting(alias: String): KeyEncryptionKeyResult = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
            ?: return KeyEncryptionKeyResult.MissingOrInvalid
        KeyEncryptionKeyResult.Available(key)
    } catch (_: Throwable) {
        KeyEncryptionKeyResult.MissingOrInvalid
    }

    /** 仅在首个库或明文迁移允许时生成版本化 AES-GCM 密钥。 */
    override fun getOrCreate(alias: String): KeyEncryptionKeyResult {
        when (val existing = getExisting(alias)) {
            is KeyEncryptionKeyResult.Available -> return existing
            KeyEncryptionKeyResult.MissingOrInvalid -> Unit
        }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val specification = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .build()
            generator.init(specification)
            KeyEncryptionKeyResult.Available(generator.generateKey())
        } catch (_: Throwable) {
            KeyEncryptionKeyResult.MissingOrInvalid
        }
    }

    private companion object {
        /** Android Keystore Provider 名称。 */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** AES-256 密钥长度。 */
        const val AES_KEY_BITS = 256
    }
}

/** 基于 Android AtomicFile 的信封存储；失败时保留旧信封内容。 */
class AndroidAtomicByteStore(
    file: File
) : AtomicByteStore {
    private val atomicFile = AtomicFile(file)
    private val lockFile = File(file.parentFile, "${file.name}.lock")
    private val localLock = processLocks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }

    /** 使用同目录文件锁执行不可分割的跨进程操作。 */
    override fun <T> withExclusiveLock(block: () -> T): T {
        lockFile.parentFile?.mkdirs()
        localLock.lock()
        try {
            return FileOutputStream(lockFile, true).channel.use { channel ->
                channel.lock().use { block() }
            }
        } finally {
            localLock.unlock()
        }
    }

    /** 返回防御性副本，调用方无需接触文件流。 */
    override fun readOrNull(): ByteArray? = AtomicFileArtifactProtocol.readOrNull(atomicFile)

    /** 通过 AtomicFile 完整写入或回滚；任何写入异常都调用 failWrite。 */
    override fun writeAtomically(bytes: ByteArray) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private companion object {
        /** 同进程双实例先串行化，跨进程互斥仍由 FileChannel 锁提供。 */
        val processLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

/** 为应用上下文创建默认信封文件位置；放入 noBackupFilesDir 避免系统迁移敏感元数据。 */
class AndroidDatabasePassphraseEnvelopeStore(context: Context) : DatabasePassphraseEnvelopeStore by DatabaseKeyEnvelopeStore(
    atomicByteStore = AndroidAtomicByteStore(
        databaseKeyEnvelopeFile(context)
    ),
    keyProvider = AndroidKeystoreAesGcmKeyProvider()
)

/** 活动数据库密钥信封的唯一生产路径。 */
internal fun databaseKeyEnvelopeFile(context: Context): File =
    File(context.noBackupFilesDir, "database/dawn_course_key_envelope.bin")
