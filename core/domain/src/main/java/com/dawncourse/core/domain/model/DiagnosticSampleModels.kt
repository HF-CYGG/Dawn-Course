package com.dawncourse.core.domain.model

/** 已经通过受信脱敏器处理、可以进入诊断落盘或上传链路的内容。 */
data class SanitizedDiagnosticSample(
    /** 本次导入会话的随机身份，不得使用 Profile 或用户身份代替。 */
    val importSessionId: String,
    /** 脱敏规则版本，用于后续诊断兼容。 */
    val sanitizerVersion: Int,
    /** 脱敏后内容的 SHA-256。 */
    val contentSha256: String,
    /** 脱敏后的完整页面结构。 */
    val content: String
)

/** 用户对短期保存原始诊断内容作出的单次明确授权。 */
data class RawDiagnosticRetentionAuthorization private constructor(
    /** 授权绑定的导入会话。 */
    val importSessionId: String,
    /** 用户作出授权的时间。 */
    val grantedAtEpochMillis: Long
) {
    companion object {
        /**
         * 仅在调用方明确传入用户已同意时签发授权对象。
         *
         * 返回 [Result] 使未授权路径无法伪装成一个可用授权。
         */
        fun create(
            importSessionId: String,
            userApproved: Boolean,
            grantedAtEpochMillis: Long
        ): Result<RawDiagnosticRetentionAuthorization> {
            if (!userApproved) {
                return Result.failure(IllegalArgumentException("raw diagnostic retention is not authorized"))
            }
            if (importSessionId.isBlank() || grantedAtEpochMillis <= 0L) {
                return Result.failure(IllegalArgumentException("invalid diagnostic authorization"))
            }
            return Result.success(
                RawDiagnosticRetentionAuthorization(
                    importSessionId = importSessionId,
                    grantedAtEpochMillis = grantedAtEpochMillis
                )
            )
        }
    }
}

/** 本地诊断副本的类型。 */
enum class DiagnosticSampleKind {
    /** 默认保存的脱敏副本。 */
    SANITIZED,

    /** 用户单次授权后保存的短期加密原文。 */
    ENCRYPTED_RAW
}

/** 不包含页面内容或个人信息的诊断副本元数据。 */
data class DiagnosticSampleMetadata(
    /** 导入会话随机身份。 */
    val importSessionId: String,
    /** 副本类型。 */
    val kind: DiagnosticSampleKind,
    /** 创建时间。 */
    val createdAtEpochMillis: Long,
    /** 强制清理时间。 */
    val expiresAtEpochMillis: Long
)

/** 批量清理结果；单个文件失败不会终止其它文件。 */
data class DiagnosticCleanupReport(
    /** 已删除的文件数。 */
    val removedCount: Int,
    /** 识别为损坏格式的文件数。 */
    val corruptCount: Int,
    /** 删除或读取失败的文件数。 */
    val failureCount: Int
)
