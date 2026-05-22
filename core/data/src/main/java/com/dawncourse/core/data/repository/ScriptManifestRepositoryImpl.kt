package com.dawncourse.core.data.repository

import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.RemoteScriptDescriptor
import com.dawncourse.core.domain.model.ScriptDependency
import com.dawncourse.core.domain.model.ScriptManifest
import com.dawncourse.core.domain.repository.ScriptManifestRepository
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class ScriptManifestRepositoryImpl @Inject constructor() : ScriptManifestRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchManifest(
        schoolId: String?,
        schoolSystemType: String?,
        appVersionCode: Long
    ): Result<ScriptManifest> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("platform=android")
            append("&appVersionCode=").append(appVersionCode)
            if (!schoolId.isNullOrBlank()) {
                append("&schoolId=").append(urlEncode(schoolId))
            }
            if (!schoolSystemType.isNullOrBlank()) {
                append("&schoolSystemType=").append(urlEncode(schoolSystemType))
            }
        }
        val errors = mutableListOf<Throwable>()
        for (endpoint in CloudBackendEndpoints.apiBaseUrls) {
            val attempt = runCatching { requestManifest("${endpoint.baseUrl}api/v1/scripts/manifest?$query") }
            if (attempt.isSuccess) {
                return@withContext attempt
            }
            errors += attempt.exceptionOrNull() ?: IllegalStateException("manifest fetch failed")
        }
        Result.failure(errors.lastOrNull() ?: IllegalStateException("manifest fetch failed"))
    }

    private fun requestManifest(url: String): ScriptManifest {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("manifest http ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) error("manifest empty")
            return parseManifest(JSONObject(text))
        }
    }

    private fun parseManifest(json: JSONObject): ScriptManifest {
        return ScriptManifest(
            manifestVersion = json.optInt("manifestVersion", 0),
            generatedAt = json.optLong("generatedAt", 0L),
            minClientVersionCode = json.optLong("minClientVersionCode", 0L),
            scripts = json.optJSONArray("scripts").toDescriptorList(),
            signature = json.optString("signature")
        )
    }

    private fun JSONArray?.toDescriptorList(): List<RemoteScriptDescriptor> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    RemoteScriptDescriptor(
                        scriptId = item.optString("scriptId"),
                        targetType = item.optString("targetType", "parser"),
                        category = item.optString("category"),
                        name = item.optString("name"),
                        version = item.optInt("version", 0),
                        releaseId = item.optString("releaseId"),
                        releaseStage = item.optString("releaseStage", item.optString("channel")),
                        channel = item.optString("channel"),
                        url = item.optString("url"),
                        metaUrl = item.optString("metaUrl"),
                        sha256 = item.optString("sha256"),
                        signature = item.optString("signature"),
                        alg = item.optString("alg"),
                        priority = item.optInt("priority", 0),
                        schoolSystemTypes = item.optJSONArray("schoolSystemTypes").toStringList(),
                        schoolIds = item.optJSONArray("schoolIds").toStringList(),
                        rolloutPercent = item.optInt("rolloutPercent", 100),
                        killSwitch = item.optBoolean("killSwitch", false),
                        minAppVersionCode = item.optLong("minAppVersionCode", 0L),
                        maxAppVersionCode = item.optLong("maxAppVersionCode", 0L)
                            .takeIf { value -> value > 0L },
                        parserApiVersion = item.optInt("parserApiVersion", 1),
                        runnerContractVersion = item.optInt("runnerContractVersion", 1),
                        schoolBindingId = item.optString("schoolBindingId").takeIf { value -> value.isNotBlank() },
                        selectionPolicy = item.optString("selectionPolicy", "auto"),
                        dependencies = item.optJSONArray("dependencies").toDependencyList(),
                        changelog = item.optString("changelog")
                    )
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toDependencyList(): List<ScriptDependency> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    ScriptDependency(
                        category = item.optString("category"),
                        name = item.optString("name"),
                        version = item.optInt("version", 0)
                    )
                )
            }
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
