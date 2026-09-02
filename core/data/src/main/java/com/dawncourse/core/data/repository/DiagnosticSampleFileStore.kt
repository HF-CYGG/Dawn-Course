package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.DiagnosticCleanupReport
import com.dawncourse.core.domain.model.DiagnosticSampleKind
import com.dawncourse.core.domain.model.DiagnosticSampleMetadata
import com.dawncourse.core.domain.model.RawDiagnosticRetentionAuthorization
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** 原始诊断内容的可替换加密边界；生产实现必须使用 Android Keystore。 */
interface RawDiagnosticCipher {
    /** 将完整记录加密为不可读密文。 */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** 解密完整记录；认证失败必须抛出异常。 */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * 诊断样本的纯文件策略实现。
 *
 * 根目录由生产仓库固定传入 `noBackupFilesDir/import_diagnostics`；此类自身仍对生成路径做
 * canonical 校验，并使用同目录原子移动，避免目录穿越和半写文件。
 */
class DiagnosticSampleFileStore(
    /** 应用私有、禁止系统备份的诊断根目录。 */
    private val rootDirectory: File,
    /** 原文加解密适配器。 */
    private val rawCipher: RawDiagnosticCipher
) {
    /** 保存默认脱敏副本。 */
    fun saveSanitized(
        sample: SanitizedDiagnosticSample,
        nowEpochMillis: Long
    ): Result<DiagnosticSampleMetadata> = runCatching {
        validateSessionId(sample.importSessionId)
        require(sample.sanitizerVersion > 0) { "invalid sanitizer version" }
        require(SHA_256_PATTERN.matches(sample.contentSha256)) { "invalid content hash" }
        require(nowEpochMillis > 0L) { "invalid diagnostic timestamp" }
        val contentBytes = sample.content.toByteArray(Charsets.UTF_8)
        require(contentBytes.isNotEmpty() && contentBytes.size <= MAX_CONTENT_BYTES) {
            "sanitized diagnostic exceeds size limit"
        }
        require(sha256(contentBytes) == sample.contentSha256) { "sanitized diagnostic hash mismatch" }
        val expiresAt = safeAdd(nowEpochMillis, SANITIZED_RETENTION_MILLIS)
        val sessionToken = sessionToken(sample.importSessionId)
        val recordBytes = DiagnosticRecordCodec.encode(
            StoredDiagnosticRecord(
                kind = DiagnosticSampleKind.SANITIZED,
                sessionToken = sessionToken,
                createdAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = expiresAt,
                sanitizerVersion = sample.sanitizerVersion,
                contentSha256 = sample.contentSha256,
                content = contentBytes
            )
        )
        try {
            val target = diagnosticFile(
                directory = sanitizedDirectory,
                kindPrefix = SANITIZED_PREFIX,
                sessionToken = sessionToken,
                expiresAtEpochMillis = expiresAt
            )
            writeAtomically(target, recordBytes)
        } finally {
            contentBytes.fill(0)
            recordBytes.fill(0)
        }
        DiagnosticSampleMetadata(
            importSessionId = sample.importSessionId,
            kind = DiagnosticSampleKind.SANITIZED,
            createdAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAt
        )
    }

    /** 保存显式授权的原始副本；保存时间起固定 24 小时后失效。 */
    fun saveRaw(
        rawContent: String,
        authorization: RawDiagnosticRetentionAuthorization,
        nowEpochMillis: Long
    ): Result<DiagnosticSampleMetadata> = runCatching {
        validateSessionId(authorization.importSessionId)
        require(nowEpochMillis >= authorization.grantedAtEpochMillis) { "authorization timestamp is in the future" }
        require(nowEpochMillis - authorization.grantedAtEpochMillis <= AUTHORIZATION_FRESHNESS_MILLIS) {
            "diagnostic authorization expired"
        }
        val rawBytes = rawContent.toByteArray(Charsets.UTF_8)
        require(rawBytes.isNotEmpty() && rawBytes.size <= MAX_CONTENT_BYTES) {
            "raw diagnostic exceeds size limit"
        }
        val expiresAt = safeAdd(nowEpochMillis, RAW_RETENTION_MILLIS)
        val sessionToken = sessionToken(authorization.importSessionId)
        val recordBytes = DiagnosticRecordCodec.encode(
            StoredDiagnosticRecord(
                kind = DiagnosticSampleKind.ENCRYPTED_RAW,
                sessionToken = sessionToken,
                createdAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = expiresAt,
                sanitizerVersion = 0,
                contentSha256 = sha256(rawBytes),
                content = rawBytes
            )
        )
        var ciphertext: ByteArray? = null
        try {
            ciphertext = rawCipher.encrypt(recordBytes)
            val target = diagnosticFile(
                directory = rawDirectory,
                kindPrefix = RAW_PREFIX,
                sessionToken = sessionToken,
                expiresAtEpochMillis = expiresAt
            )
            writeAtomically(target, ciphertext)
        } finally {
            rawBytes.fill(0)
            recordBytes.fill(0)
            ciphertext?.fill(0)
        }
        DiagnosticSampleMetadata(
            importSessionId = authorization.importSessionId,
            kind = DiagnosticSampleKind.ENCRYPTED_RAW,
            createdAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAt
        )
    }

    /** 逐个清理过期或损坏样本；任何单文件故障不阻止后续文件。 */
    fun cleanupExpired(nowEpochMillis: Long): DiagnosticCleanupReport {
        var removedCount = 0
        var corruptCount = 0
        var failureCount = 0
        listOf(
            DiagnosticDirectory(sanitizedDirectory, DiagnosticSampleKind.SANITIZED),
            DiagnosticDirectory(rawDirectory, DiagnosticSampleKind.ENCRYPTED_RAW)
        ).forEach { diagnosticDirectory ->
            diagnosticDirectory.directory.listFiles()
                ?.filter(File::isFile)
                ?.forEach { file ->
                    val result = runCatching { inspectRecord(file, diagnosticDirectory.kind) }
                    val record = result.getOrNull()
                    val isCorrupt = record == null
                    val shouldRemove = isCorrupt || record.expiresAtEpochMillis <= nowEpochMillis
                    if (isCorrupt) corruptCount += 1
                    if (shouldRemove) {
                        if (runCatching(file::delete).getOrDefault(false)) {
                            removedCount += 1
                        } else {
                            failureCount += 1
                        }
                    }
                }
        }
        return DiagnosticCleanupReport(removedCount, corruptCount, failureCount)
    }

    /** 仅清理目标会话的短期原文，不触碰其它会话和脱敏副本。 */
    fun clearRawForSession(importSessionId: String): DiagnosticCleanupReport {
        return runCatching {
            validateSessionId(importSessionId)
            val expectedPrefix = "$RAW_PREFIX${sessionToken(importSessionId)}_"
            var removedCount = 0
            var failureCount = 0
            rawDirectory.listFiles()
                ?.filter { file -> file.isFile && file.name.startsWith(expectedPrefix) }
                ?.forEach { file ->
                    if (runCatching(file::delete).getOrDefault(false)) {
                        removedCount += 1
                    } else {
                        failureCount += 1
                    }
                }
            DiagnosticCleanupReport(removedCount, corruptCount = 0, failureCount)
        }.getOrElse {
            DiagnosticCleanupReport(removedCount = 0, corruptCount = 0, failureCount = 1)
        }
    }

    /** 测试可见的原文文件枚举，不暴露内容读取能力。 */
    internal fun listRawFilesForTest(): List<File> = rawDirectory.listFiles()?.filter(File::isFile).orEmpty()

    /** 测试可见的原文目录，用于构造损坏文件。 */
    internal fun rawDirectoryForTest(): File = rawDirectory.apply(File::mkdirs)

    /** 读取并校验单个记录，不将内容写入日志或异常消息。 */
    private fun inspectRecord(file: File, expectedKind: DiagnosticSampleKind): StoredDiagnosticRecord {
        val fileIdentity = FILE_NAME_PATTERN.matchEntire(file.name) ?: error("invalid diagnostic file name")
        val expectedPrefix = if (expectedKind == DiagnosticSampleKind.SANITIZED) SANITIZED_PREFIX else RAW_PREFIX
        require(fileIdentity.groupValues[1] == expectedPrefix) { "diagnostic kind mismatch" }
        val expectedToken = fileIdentity.groupValues[2]
        val expectedExpiry = fileIdentity.groupValues[3].toLongOrNull() ?: error("invalid diagnostic expiry")
        require(file.isFile && file.length() in 1L..MAX_STORED_BYTES.toLong()) {
            "invalid diagnostic file size"
        }
        val diskBytes = file.readBytes()
        var plaintext: ByteArray? = null
        try {
            plaintext = if (expectedKind == DiagnosticSampleKind.ENCRYPTED_RAW) {
                rawCipher.decrypt(diskBytes)
            } else {
                diskBytes.copyOf()
            }
            val record = DiagnosticRecordCodec.decode(plaintext) ?: error("invalid diagnostic record")
            require(record.kind == expectedKind) { "diagnostic record kind mismatch" }
            require(record.sessionToken == expectedToken) { "diagnostic session mismatch" }
            require(record.expiresAtEpochMillis == expectedExpiry) { "diagnostic expiry mismatch" }
            require(record.content.size <= MAX_CONTENT_BYTES) { "diagnostic content too large" }
            require(sha256(record.content) == record.contentSha256) { "diagnostic content hash mismatch" }
            return record
        } finally {
            diskBytes.fill(0)
            plaintext?.fill(0)
        }
    }

    /** 仅从已校验的组成部分创建诊断路径，并再次检查 canonical 父目录。 */
    private fun diagnosticFile(
        directory: File,
        kindPrefix: String,
        sessionToken: String,
        expiresAtEpochMillis: Long
    ): File {
        directory.mkdirs()
        require(directory.isDirectory) { "diagnostic directory unavailable" }
        val target = File(directory, "$kindPrefix${sessionToken}_$expiresAtEpochMillis.diag")
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "invalid diagnostic path" }
        return target
    }

    /** 使用同目录临时文件与原子移动写入，失败时不会留下半成品目标。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_STORED_BYTES) { "invalid diagnostic payload size" }
        val parent = target.parentFile ?: error("missing diagnostic parent")
        require(target.canonicalFile.parentFile == parent.canonicalFile) { "invalid diagnostic target" }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        require(temporary.canonicalFile.parentFile == parent.canonicalFile) { "invalid diagnostic temporary path" }
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("atomic diagnostic write unsupported", error)
            }
        } finally {
            temporary.delete()
        }
    }

    /** sessionId 必须是应用生成的短标识，拒绝路径分隔符、空白和任意正文。 */
    private fun validateSessionId(importSessionId: String) {
        require(SESSION_ID_PATTERN.matches(importSessionId)) { "invalid import session id" }
    }

    /** 以哈希文件名避免在目录元数据中暴露会话原值。 */
    private fun sessionToken(importSessionId: String): String = sha256(importSessionId.toByteArray()).take(32)

    /** 计算小写十六进制 SHA-256。 */
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 防止时间加法溢出绕过清理。 */
    private fun safeAdd(value: Long, delta: Long): Long {
        require(value <= Long.MAX_VALUE - delta) { "diagnostic expiry overflow" }
        return value + delta
    }

    /** 私有目录及其记录类型。 */
    private data class DiagnosticDirectory(
        val directory: File,
        val kind: DiagnosticSampleKind
    )

    /** 默认脱敏副本目录。 */
    private val sanitizedDirectory: File
        get() = File(rootDirectory, SANITIZED_DIRECTORY_NAME)

    /** 用户授权的短期加密原文目录。 */
    private val rawDirectory: File
        get() = File(rootDirectory, RAW_DIRECTORY_NAME)

    companion object {
        /** 原文固定 24 小时 TTL。 */
        const val RAW_RETENTION_MILLIS = 24L * 60L * 60L * 1000L

        /** 脱敏副本同样限制为 24 小时，减少无界留存。 */
        const val SANITIZED_RETENTION_MILLIS = 24L * 60L * 60L * 1000L

        /** 授权仅在当前交互窗口内有效，防止跨流程复用。 */
        const val AUTHORIZATION_FRESHNESS_MILLIS = 10L * 60L * 1000L

        /** 单条原始或脱敏内容最大 4 MiB。 */
        const val MAX_CONTENT_BYTES = 4 * 1024 * 1024

        /** 加密、头部和认证标签预留后的最大落盘体积。 */
        private const val MAX_STORED_BYTES = MAX_CONTENT_BYTES + 64 * 1024
        private const val SANITIZED_DIRECTORY_NAME = "sanitized"
        private const val RAW_DIRECTORY_NAME = "raw"
        private const val SANITIZED_PREFIX = "s_"
        private const val RAW_PREFIX = "r_"
        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")
        private val SHA_256_PATTERN = Regex("[a-f0-9]{64}")
        private val FILE_NAME_PATTERN = Regex("(s_|r_)([a-f0-9]{32})_(\\d{1,19})\\.diag")
    }
}

/** 文件中的诊断记录；元数据不包含页面内容之外的用户身份。 */
private data class StoredDiagnosticRecord(
    val kind: DiagnosticSampleKind,
    val sessionToken: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val sanitizerVersion: Int,
    val contentSha256: String,
    val content: ByteArray
)

/** 有上限、版本化的二进制诊断记录编解码器。 */
private object DiagnosticRecordCodec {
    /** 编码记录，供脱敏明文文件或原文加密前使用。 */
    fun encode(record: StoredDiagnosticRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(record.kind.ordinal)
            data.writeUTF(record.sessionToken)
            data.writeLong(record.createdAtEpochMillis)
            data.writeLong(record.expiresAtEpochMillis)
            data.writeInt(record.sanitizerVersion)
            data.writeUTF(record.contentSha256)
            data.writeInt(record.content.size)
            data.write(record.content)
        }
        return output.toByteArray()
    }

    /** 严格解码记录；任何损坏均返回 null，不回显内容。 */
    fun decode(bytes: ByteArray): StoredDiagnosticRecord? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            require(magic.contentEquals(MAGIC))
            require(data.readInt() == VERSION)
            val kind = DiagnosticSampleKind.entries.getOrNull(data.readInt()) ?: error("unknown diagnostic kind")
            val sessionToken = data.readUTF()
            require(sessionToken.matches(Regex("[a-f0-9]{32}")))
            val createdAt = data.readLong()
            val expiresAt = data.readLong()
            require(createdAt > 0L && expiresAt > createdAt)
            val sanitizerVersion = data.readInt()
            val contentSha256 = data.readUTF()
            require(contentSha256.matches(Regex("[a-f0-9]{64}")))
            val contentLength = data.readInt()
            require(contentLength in 1..DiagnosticSampleFileStore.MAX_CONTENT_BYTES)
            val content = ByteArray(contentLength)
            data.readFully(content)
            require(data.read() == -1)
            StoredDiagnosticRecord(
                kind = kind,
                sessionToken = sessionToken,
                createdAtEpochMillis = createdAt,
                expiresAtEpochMillis = expiresAt,
                sanitizerVersion = sanitizerVersion,
                contentSha256 = contentSha256,
                content = content
            )
        }
    }.getOrNull()

    private const val VERSION = 1
    private val MAGIC = "DAWN-DIAGNOSTIC".toByteArray(Charsets.US_ASCII)
}
