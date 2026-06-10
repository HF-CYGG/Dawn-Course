package com.dawncourse.core.data.repository

import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.PageFingerprint
import com.dawncourse.core.domain.model.ParseReportPayload
import com.dawncourse.core.domain.model.ParserAttemptReport
import com.dawncourse.core.domain.model.SanitizedSample
import com.dawncourse.core.domain.repository.ParseReportRepository
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
class ParseReportRepositoryImpl @Inject constructor() : ParseReportRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun reportParseResult(payload: ParseReportPayload): Result<Unit> = withContext(Dispatchers.IO) {
        val body = payload.toJson()
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val errors = mutableListOf<Throwable>()
        for (endpoint in CloudBackendEndpoints.apiBaseUrls) {
            val attempt = runCatching { post("${endpoint.baseUrl}api/v1/parse/report", body) }
            if (attempt.isSuccess) {
                return@withContext Result.success(Unit)
            }
            errors += attempt.exceptionOrNull() ?: IllegalStateException("parse report failed")
        }
        Result.failure(errors.lastOrNull() ?: IllegalStateException("parse report failed"))
    }

    private fun post(url: String, body: okhttp3.RequestBody) {
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("parse report http ${response.code}")
            }
        }
    }

    private fun ParseReportPayload.toJson(): JSONObject {
        return JSONObject()
            .put(
                "session",
                JSONObject()
                    .put("parseSessionId", session.parseSessionId)
                    .put("appVersionCode", session.appVersionCode)
                    .put("appVersionName", session.appVersionName)
                    .put("installBucketIdHash", session.installBucketIdHash)
                    .put("importSource", session.importSource.name)
                    .put("schoolId", session.schoolId ?: "")
                    .put("schoolName", session.schoolName ?: "")
                    .put("schoolSystemType", session.schoolSystemType?.name ?: "")
                    .put("sourceUrlHost", session.sourceUrlHost ?: "")
                    .put("startedAt", session.startedAt)
            )
            .put("pageFingerprint", pageFingerprint?.toJson())
            .put("attempts", JSONArray(attempts.map { it.toJson() }))
            .put("finalSuccess", finalSuccess)
            .put("finalFailureType", finalFailureType?.name ?: "")
            .put("failureStage", failureStage ?: "")
            .put("repairDomain", repairDomain ?: "")
            .put("targetType", targetType ?: "")
            .put("sourceUrl", sourceUrl ?: "")
            .put("classificationHint", JSONObject(classificationHint))
            .put("consentAt", consentAt ?: JSONObject.NULL)
            .put("sanitizedSample", sanitizedSample?.toJson())
    }

    private fun ParserAttemptReport.toJson(): JSONObject {
        return JSONObject()
            .put("parserName", parserName)
            .put("category", category)
            .put("parserVersion", parserVersion)
            .put("releaseId", releaseId ?: "")
            .put("scriptSource", scriptSource)
            .put("scriptSha256", scriptSha256 ?: "")
            .put("durationMs", durationMs)
            .put("success", success)
            .put("resultCount", resultCount)
            .put("failureType", failureType?.name ?: "")
            .put("safeErrorCode", safeErrorCode ?: "")
            .put("schemaValid", schemaValid)
            .put("confidence", confidence?.toDouble() ?: JSONObject.NULL)
    }

    private fun PageFingerprint.toJson(): JSONObject {
        return JSONObject()
            .put("host", host ?: "")
            .put("pathPattern", pathPattern ?: "")
            .put("titleHash", titleHash ?: "")
            .put("bodyTextHash", bodyTextHash ?: "")
            .put("htmlStructureHash", htmlStructureHash ?: "")
            .put("tableShape", tableShape ?: "")
            .put("formActionHash", formActionHash ?: "")
            .put("hasCaptcha", hasCaptcha)
            .put("hasLoginKeyword", hasLoginKeyword)
            .put("hasCourseKeyword", hasCourseKeyword)
    }

    private fun SanitizedSample.toJson(): JSONObject {
        return JSONObject()
            .put("hasUserConsent", hasUserConsent)
            .put("sanitizerVersion", sanitizerVersion)
            .put("contentSha256", contentSha256)
            .put("content", content ?: "")
    }
}
