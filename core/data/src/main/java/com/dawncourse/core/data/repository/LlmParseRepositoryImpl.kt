/**
 * 文件说明：负责对接云端兜底解析接口，提交解析任务并轮询服务端状态。
 */
package com.dawncourse.core.data.repository

import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.LlmParseStatus
import com.dawncourse.core.domain.model.LlmParseStatusResult
import com.dawncourse.core.domain.model.LlmParseTaskResult
import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import com.dawncourse.core.domain.repository.LlmParseRepository
import com.google.gson.Gson
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
class LlmParseRepositoryImpl @Inject constructor() : LlmParseRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun submitParseTask(
        sample: SanitizedDiagnosticSample,
        consentAt: Long,
        schoolId: String?,
        schoolName: String?,
        schoolSystemType: String?,
        sourceUrl: String?,
        scriptName: String?,
        scriptVersion: Int?,
        scriptSource: String?,
        failureType: String?,
        clientVersion: String?,
        issueId: String?,
        attemptedParsers: List<String>
    ): LlmParseTaskResult = withContext(Dispatchers.IO) {
        val payload = LlmParseRequestJson.encode(
            sample = sample,
            consentAt = consentAt,
            schoolId = schoolId,
            schoolName = schoolName,
            schoolSystemType = schoolSystemType,
            sourceUrl = sourceUrl,
            scriptName = scriptName,
            scriptVersion = scriptVersion,
            scriptSource = scriptSource,
            failureType = failureType,
            clientVersion = clientVersion,
            issueId = issueId,
            attemptedParsers = attemptedParsers
        )
        val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        executeSubmitWithFallback(requestBody).getOrElse {
            LlmParseTaskResult(
                success = false,
                message = "任务提交失败：${CloudBackendEndpoints.toUserFacingMessage(it)}"
            )
        }
    }

    override suspend fun fetchTaskStatus(taskId: String): LlmParseStatusResult = withContext(Dispatchers.IO) {
        executeStatusWithFallback(taskId).getOrElse {
            LlmParseStatusResult(
                success = false,
                status = LlmParseStatus.FAILED,
                message = "状态查询失败：${CloudBackendEndpoints.toUserFacingMessage(it)}"
            )
        }
    }

    private fun executeSubmitWithFallback(body: okhttp3.RequestBody): Result<LlmParseTaskResult> {
        val errors = mutableListOf<Throwable>()
        var lastResult: LlmParseTaskResult? = null
        for (endpoint in CloudBackendEndpoints.apiBaseUrls) {
            val attempt = runCatching { executeSubmit(endpoint.baseUrl, body) }
            if (attempt.isSuccess) {
                val value = attempt.getOrThrow()
                if (value.success) {
                    return Result.success(value)
                }
                lastResult = value
                continue
            }
            errors += attempt.exceptionOrNull() ?: IllegalStateException("submit failed")
        }
        lastResult?.let { return Result.success(it) }
        return Result.failure(errors.lastOrNull() ?: IllegalStateException("submit failed"))
    }

    private fun executeStatusWithFallback(taskId: String): Result<LlmParseStatusResult> {
        val errors = mutableListOf<Throwable>()
        var lastResult: LlmParseStatusResult? = null
        for (endpoint in CloudBackendEndpoints.apiBaseUrls) {
            val attempt = runCatching { executeStatus(endpoint.baseUrl, taskId) }
            if (attempt.isSuccess) {
                val value = attempt.getOrThrow()
                if (value.success) {
                    return Result.success(value)
                }
                lastResult = value
                continue
            }
            errors += attempt.exceptionOrNull() ?: IllegalStateException("status failed")
        }
        lastResult?.let { return Result.success(it) }
        return Result.failure(errors.lastOrNull() ?: IllegalStateException("status failed"))
    }

    private fun executeSubmit(baseUrl: String, body: okhttp3.RequestBody): LlmParseTaskResult {
        val request = Request.Builder()
            .url("${baseUrl}api/v1/parse_task")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                val bodyJson = runCatching { JSONObject(bodyText) }.getOrNull()
                return LlmParseTaskResult(
                    success = false,
                    message = bodyJson?.optString("msg")?.ifBlank { null } ?: "任务提交失败：HTTP ${response.code}"
                )
            }
            val responseText = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(responseText) }.getOrNull()
            if (json == null) {
                return LlmParseTaskResult(
                    success = false,
                    message = "任务提交失败：响应格式不合法"
                )
            }
            val code = json.optInt("code", 200)
            if (code != 200) {
                return LlmParseTaskResult(
                    success = false,
                    message = json.optString("msg").ifBlank { "任务提交失败" }
                )
            }
            val taskId = json.optString("taskId").ifBlank {
                json.optJSONObject("data")?.optString("taskId").orEmpty()
            }
            if (taskId.isBlank()) {
                return LlmParseTaskResult(
                    success = false,
                    message = "任务提交失败：缺少 taskId"
                )
            }
            val message = json.optString("msg").takeIf { it.isNotBlank() }
            return LlmParseTaskResult(
                success = true,
                taskId = taskId,
                message = message
            )
        }
    }

    private fun executeStatus(baseUrl: String, taskId: String): LlmParseStatusResult {
        val request = Request.Builder()
            .url("${baseUrl}api/v1/task_status?taskId=${taskId}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return LlmParseStatusResult(
                    success = false,
                    status = LlmParseStatus.FAILED,
                    message = "状态查询失败：HTTP ${response.code}"
                )
            }
            val responseText = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(responseText) }.getOrNull()
            if (json == null) {
                return LlmParseStatusResult(
                    success = false,
                    status = LlmParseStatus.FAILED,
                    message = "状态查询失败：响应格式不合法"
                )
            }
            val code = json.optInt("code", 200)
            if (code != 200) {
                return LlmParseStatusResult(
                    success = false,
                    status = LlmParseStatus.FAILED,
                    message = json.optString("msg").ifBlank { "状态查询失败" }
                )
            }
            val data = json.optJSONObject("data")
            val statusRaw = (data?.optString("status") ?: json.optString("status")).lowercase()
            val status = when (statusRaw) {
                "pending" -> LlmParseStatus.PENDING
                "success" -> LlmParseStatus.SUCCESS
                "failed" -> LlmParseStatus.FAILED
                else -> LlmParseStatus.PROCESSING
            }
            val resultText = data?.optString("result")
                ?.takeIf { it.isNotBlank() }
                ?: data?.optString("data")?.takeIf { it.isNotBlank() }
                ?: json.optString("result").takeIf { it.isNotBlank() }
                ?: json.optString("data").takeIf { it.isNotBlank() }
            val message = data?.optString("pendingReason")?.takeIf { it.isNotBlank() }
                ?: data?.optString("error")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("msg").takeIf { it.isNotBlank() }
            return LlmParseStatusResult(
                success = true,
                status = status,
                resultText = resultText,
                message = message
            )
        }
    }
}

/** 云端解析请求的唯一序列化入口，类型上禁止传入原始 HTML 或 Profile 身份。 */
object LlmParseRequestJson {
    /** 纯 JVM 可用的 JSON 编码器。 */
    private val gson = Gson()

    /** 将已脱敏样本编码为服务端请求。 */
    fun encode(
        sample: SanitizedDiagnosticSample,
        consentAt: Long,
        schoolId: String?,
        schoolName: String?,
        schoolSystemType: String?,
        sourceUrl: String?,
        scriptName: String?,
        scriptVersion: Int?,
        scriptSource: String?,
        failureType: String?,
        clientVersion: String?,
        issueId: String?,
        attemptedParsers: List<String>
    ): String {
        require(IMPORT_SESSION_ID_PATTERN.matches(sample.importSessionId)) { "invalid import session id" }
        require(sample.sanitizerVersion > 0) { "invalid sanitizer version" }
        require(SHA_256_PATTERN.matches(sample.contentSha256)) { "invalid sanitized content hash" }
        require(sample.content.isNotBlank()) { "sanitized content is blank" }
        require(sha256(sample.content) == sample.contentSha256) { "sanitized content hash mismatch" }
        require(consentAt > 0L) { "invalid consent timestamp" }
        val payload = linkedMapOf<String, Any>(
            "sanitizedContent" to sample.content,
            "contentSha256" to sample.contentSha256,
            "sanitizerVersion" to sample.sanitizerVersion,
            "userConsent" to true,
            "consentAt" to consentAt,
            "importSessionId" to sample.importSessionId,
            "schoolId" to schoolId.orEmpty(),
            "schoolName" to schoolName.orEmpty(),
            "schoolSystemType" to schoolSystemType.orEmpty(),
            "sourceUrl" to DiagnosticUrlPolicy.originOnly(sourceUrl),
            "scriptName" to scriptName.orEmpty(),
            "scriptVersion" to (scriptVersion ?: 0),
            "scriptSource" to scriptSource.orEmpty(),
            "failureType" to failureType.orEmpty(),
            "clientVersion" to clientVersion.orEmpty(),
            "issueId" to issueId.orEmpty(),
            "attemptedParsers" to attemptedParsers
        )
        return gson.toJson(payload)
    }

    /** 计算脱敏内容 SHA-256，阻止伪造类型包装绕过。 */
    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private val IMPORT_SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")
    private val SHA_256_PATTERN = Regex("[a-f0-9]{64}")
}

/** 诊断网络元数据策略；任何 URL 只允许离开 origin。 */
object DiagnosticUrlPolicy {
    /** 移除 userinfo、path、query、fragment，并省略默认端口。 */
    fun originOnly(sourceUrl: String?): String {
        if (sourceUrl.isNullOrBlank()) return ""
        return runCatching {
            val uri = URI(sourceUrl)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            require((scheme == "http" || scheme == "https") && host.isNotBlank())
            val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            val port = uri.port.takeIf { value -> value > 0 && !defaultPort }?.let { value -> ":$value" }.orEmpty()
            "$scheme://$host$port"
        }.getOrDefault("")
    }
}
