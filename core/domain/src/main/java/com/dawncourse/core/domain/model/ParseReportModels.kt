package com.dawncourse.core.domain.model

/**
 * Session-level parse context. It does not include page content.
 */
data class ParseSessionContext(
    val parseSessionId: String,
    val appVersionCode: Long,
    val appVersionName: String,
    val installBucketIdHash: String,
    val importSource: ImportSourceType,
    val schoolId: String?,
    val schoolName: String?,
    val schoolSystemType: SchoolSystemType?,
    val sourceUrlHost: String?,
    val startedAt: Long
)

enum class ImportSourceType {
    WEBVIEW,
    ZF_WEB,
    QIANGZHI_API,
    WAKEUP,
    ICS,
    MANUAL_JSON,
    UNKNOWN
}

enum class SchoolSystemType {
    ZF,
    QIANGZHI,
    KINGOSOFT,
    QIDI,
    CHAOXING,
    UNKNOWN
}

enum class ParseFailureType {
    SCRIPT_FETCH_FAILED,
    SCRIPT_SIGNATURE_INVALID,
    SCRIPT_EXECUTION_EXCEPTION,
    PARSER_EMPTY_RESULT,
    PARSER_SCHEMA_INVALID,
    PARSER_LOW_CONFIDENCE,
    UNSUPPORTED_PAGE,
    USER_NOT_LOGGED_IN,
    PAGE_NOT_LOADED,
    CLOUD_TASK_FAILED,
    NETWORK_ERROR,
    UNKNOWN
}

data class ParserAttemptReport(
    val parserName: String,
    val category: String,
    val parserVersion: Int,
    val releaseId: String?,
    val scriptSource: String,
    val scriptSha256: String?,
    val durationMs: Long,
    val success: Boolean,
    val resultCount: Int,
    val failureType: ParseFailureType?,
    val safeErrorCode: String?,
    val schemaValid: Boolean,
    val confidence: Float?
)

data class PageFingerprint(
    val host: String?,
    val pathPattern: String?,
    val titleHash: String?,
    val bodyTextHash: String?,
    val htmlStructureHash: String?,
    val tableShape: String?,
    val formActionHash: String?,
    val hasCaptcha: Boolean,
    val hasLoginKeyword: Boolean,
    val hasCourseKeyword: Boolean
)

data class ParseReportPayload(
    val session: ParseSessionContext,
    val pageFingerprint: PageFingerprint?,
    val attempts: List<ParserAttemptReport>,
    val finalSuccess: Boolean,
    val finalFailureType: ParseFailureType?,
    val failureStage: String? = null,
    val repairDomain: String? = null,
    val targetType: String? = null,
    val sourceUrl: String? = null,
    val classificationHint: Map<String, String> = emptyMap(),
    val consentAt: Long? = null,
    val sanitizedSample: SanitizedSample? = null
)

data class SanitizedSample(
    val hasUserConsent: Boolean,
    val sanitizerVersion: Int,
    val contentSha256: String,
    val content: String?
)
