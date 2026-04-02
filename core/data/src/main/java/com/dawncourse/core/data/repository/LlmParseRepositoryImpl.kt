package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.LlmParseStatus
import com.dawncourse.core.domain.model.LlmParseStatusResult
import com.dawncourse.core.domain.model.LlmParseTaskResult
import com.dawncourse.core.domain.repository.LlmParseRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LLM 异步解析仓库实现
 *
 * 负责：
 * 1. 提交脱敏后的文本到服务端生成解析任务
 * 2. 轮询任务状态并获取解析结果
 * 3. 提供主域名与备用域名的兜底请求能力
 */
@Singleton
class LlmParseRepositoryImpl @Inject constructor() : LlmParseRepository {

    private val primaryUrl = "http://yyh163.xyz:10000/"
    private val fallbackUrl = "http://47.105.76.193/"

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
        sourceUrl: String?
    ): LlmParseTaskResult = withContext(Dispatchers.IO) {
        // 服务端要求必须携带用户明确同意的标记，避免自动上传
        val payload = JSONObject()
            .put("content", content)
            .put("userConsent", consent)
            .put("consentAt", consentAt)
            .put("schoolId", schoolId ?: "")
            .put("schoolName", schoolName ?: "")
            .put("schoolSystemType", schoolSystemType ?: "")
            .put("sourceUrl", sourceUrl ?: "")
            .toString()
        val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val primaryResult = runCatching { executeSubmit(primaryUrl, requestBody) }
        if (primaryResult.isSuccess) return@withContext primaryResult.getOrThrow()
        val fallbackResult = runCatching { executeSubmit(fallbackUrl, requestBody) }
        fallbackResult.getOrElse {
            LlmParseTaskResult(
                success = false,
                message = "任务提交失败：${it.message ?: "网络异常"}"
            )
        }
    }

    override suspend fun fetchTaskStatus(taskId: String): LlmParseStatusResult = withContext(Dispatchers.IO) {
        val primaryResult = runCatching { executeStatus(primaryUrl, taskId) }
        if (primaryResult.isSuccess) return@withContext primaryResult.getOrThrow()
        val fallbackResult = runCatching { executeStatus(fallbackUrl, taskId) }
        fallbackResult.getOrElse {
            LlmParseStatusResult(
                success = false,
                status = LlmParseStatus.FAILED,
                message = "状态查询失败：${it.message ?: "网络异常"}"
            )
        }
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
