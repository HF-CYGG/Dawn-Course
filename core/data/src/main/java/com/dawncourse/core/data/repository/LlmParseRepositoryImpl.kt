package com.dawncourse.core.data.repository

import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.LlmParseStatus
import com.dawncourse.core.domain.model.LlmParseStatusResult
import com.dawncourse.core.domain.model.LlmParseTaskResult
import com.dawncourse.core.domain.repository.LlmParseRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class LlmParseRepositoryImpl @Inject constructor() : LlmParseRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun submitParseTask(
        content: String,
        consent: Boolean,
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
        parseSessionId: String?,
        issueId: String?,
        attemptedParsers: List<String>
    ): LlmParseTaskResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("content", content)
            .put("userConsent", consent)
            .put("consentAt", consentAt)
            .put("schoolId", schoolId ?: "")
            .put("schoolName", schoolName ?: "")
            .put("schoolSystemType", schoolSystemType ?: "")
            .put("sourceUrl", sourceUrl ?: "")
            .put("scriptName", scriptName ?: "")
            .put("scriptVersion", scriptVersion ?: 0)
            .put("scriptSource", scriptSource ?: "")
            .put("failureType", failureType ?: "")
            .put("clientVersion", clientVersion ?: "")
            .put("parseSessionId", parseSessionId ?: "")
            .put("issueId", issueId ?: "")
            .put("attemptedParsers", JSONArray(attemptedParsers))
            .toString()
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
