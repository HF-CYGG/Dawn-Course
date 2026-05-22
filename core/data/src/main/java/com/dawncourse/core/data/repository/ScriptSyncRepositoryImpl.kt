package com.dawncourse.core.data.repository

import android.content.Context
import com.dawncourse.core.data.BuildConfig
import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.RemoteScriptDescriptor
import com.dawncourse.core.domain.repository.ScriptFetchResult
import com.dawncourse.core.domain.repository.ScriptSyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class ScriptSyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ScriptSyncRepository {

    // 云端脚本的存储路径
    private val backendEndpoints = CloudBackendEndpoints.apiBaseUrls
    private val scriptBaseUrls = backendEndpoints.map { it.label to "${it.baseUrl}scripts/" }
    private val pullStatUrls = backendEndpoints.map { "${it.baseUrl}api/v1/script_pull" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private data class MemoryCacheEntry(
        val content: String,
        val fetchedAt: Long
    )

    private val memoryCache = mutableMapOf<String, MemoryCacheEntry>()
    private val cacheMutex = Mutex()
    private val memoryTtlMillis = TimeUnit.MINUTES.toMillis(5)
    private val scriptVerifyPublicKey = BuildConfig.SCRIPT_VERIFY_PUBLIC_KEY.trim()

    private data class ScriptMeta(
        val sha256: String,
        val signature: String,
        val alg: String,
        val version: Int?
    )

    private data class ManifestFetchResult(
        val script: String,
        val metaRaw: String?,
        val source: String
    )

    override suspend fun getScript(scriptName: String, category: String, pullTaskId: String): String {
        return getScriptWithInfo(scriptName, category, pullTaskId).content
    }

    override suspend fun getScriptWithInfo(
        scriptName: String,
        category: String,
        pullTaskId: String
    ): ScriptFetchResult {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category)
                ?: return@withContext ScriptFetchResult("", false, "invalid_category")
            val safeScriptName = normalizePathSegment(scriptName)
                ?: return@withContext ScriptFetchResult("", false, "invalid_script_name")
            val cacheKey = buildCacheKey(safeCategory, safeScriptName)
            val remoteResult = runCatching { fetchScriptFromCloud(safeScriptName, safeCategory) }.getOrNull()
            val remoteScript = remoteResult?.content
            val remoteMeta = remoteResult?.metaRaw
                ?: runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            if (!remoteScript.isNullOrBlank() && verifyScript(remoteScript, remoteMeta, safeCategory != "parsers")) {
                val remoteSource = remoteResult.source
                promoteCurrentScriptToPrevious(safeScriptName, safeCategory)
                saveScriptToScopedCache("current", safeScriptName, safeCategory, remoteScript)
                saveMetaToScopedCache("current", safeScriptName, safeCategory, remoteMeta)
                updateMemoryCache(cacheKey, remoteScript)
                reportScriptPullStat(
                    scriptName = safeScriptName,
                    category = safeCategory,
                    source = remoteSource,
                    pullTaskId = pullTaskId
                )
                return@withContext ScriptFetchResult(
                    content = remoteScript,
                    fromCloud = true,
                    source = remoteSource
                )
            }

            val cachedScript = readScriptFromScopedCache("current", safeScriptName, safeCategory)
                ?: readScriptFromLocalCache(safeScriptName, safeCategory)
            val cachedMeta = readMetaFromScopedCache("current", safeScriptName, safeCategory)
                ?: readMetaFromLocalCache(safeScriptName, safeCategory)
            if (!cachedScript.isNullOrBlank() && verifyScript(cachedScript, cachedMeta, safeCategory != "parsers")) {
                updateMemoryCache(cacheKey, cachedScript)
                reportScriptPullStat(
                    scriptName = safeScriptName,
                    category = safeCategory,
                    source = "local_cache",
                    pullTaskId = pullTaskId
                )
                return@withContext ScriptFetchResult(
                    content = cachedScript,
                    fromCloud = false,
                    source = "local_cache"
                )
            }

            val previousScript = readScriptFromScopedCache("previous_stable", safeScriptName, safeCategory)
            val previousMeta = readMetaFromScopedCache("previous_stable", safeScriptName, safeCategory)
            if (!previousScript.isNullOrBlank() && verifyScript(previousScript, previousMeta, safeCategory != "parsers")) {
                updateMemoryCache(cacheKey, previousScript)
                reportScriptPullStat(
                    scriptName = safeScriptName,
                    category = safeCategory,
                    source = "previous_stable",
                    pullTaskId = pullTaskId
                )
                return@withContext ScriptFetchResult(
                    content = previousScript,
                    fromCloud = false,
                    source = "previous_stable"
                )
            }

            val assetsScript = readScriptFromAssets(safeScriptName, safeCategory)
            reportScriptPullStat(
                scriptName = safeScriptName,
                category = safeCategory,
                source = "assets",
                pullTaskId = pullTaskId
            )
            ScriptFetchResult(
                content = assetsScript,
                fromCloud = false,
                source = "assets"
            )
        }
    }

    override suspend fun fetchAndCacheScript(scriptName: String, category: String, pullTaskId: String): String {
        return withContext(Dispatchers.IO) {
            getScriptWithInfo(scriptName, category, pullTaskId).content
        }
    }

    override suspend fun getScriptVersion(scriptName: String, category: String): Int? {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category) ?: return@withContext null
            val safeScriptName = normalizePathSegment(scriptName) ?: return@withContext null
            val metaRaw = readMetaFromScopedCache("current", safeScriptName, safeCategory)
                ?: readMetaFromLocalCache(safeScriptName, safeCategory)
                ?: runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            parseScriptMeta(metaRaw)?.version
        }
    }

    override suspend fun reportScriptParseFeedback(
        scriptName: String,
        category: String,
        success: Boolean,
        errorMessage: String?,
        sourceUrl: String?,
        parseSessionId: String?,
        isSessionFinal: Boolean,
        finalResult: String?,
        failureType: String?,
        schoolSystemType: String?,
        attemptedParsers: List<String>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category) ?: return@withContext false
            val safeScriptName = normalizePathSegment(scriptName) ?: return@withContext false
            val scriptVersion = getScriptVersion(safeScriptName, safeCategory) ?: 0
            val payload = JSONObject()
                .put("scriptName", safeScriptName)
                .put("category", safeCategory)
                .put("scriptVersion", scriptVersion)
                .put("success", success)
                .put("errorMessage", errorMessage ?: "")
                .put("sourceUrl", sourceUrl ?: "")
                .put("parseSessionId", parseSessionId ?: "")
                .put("isSessionFinal", isSessionFinal)
                .put("finalResult", finalResult ?: "")
                .put("failureType", failureType ?: "")
                .put("schoolSystemType", schoolSystemType ?: "")
                .put("attemptedParsers", JSONArray(attemptedParsers))
                .toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            scriptBaseUrls.any { (_, baseUrl) ->
                runCatching { postFeedback(baseUrl, body) }.getOrDefault(false)
            }
        }
    }

    private data class CloudScriptResult(
        val content: String?,
        val metaRaw: String?,
        val source: String
    )

    private fun fetchScriptFromCloud(scriptName: String, category: String): CloudScriptResult {
        for ((label, baseUrl) in backendEndpoints.map { it.label to it.baseUrl }) {
            fetchScriptFromManifest(scriptName, category, baseUrl, "manifest_$label")?.let { return it }
        }
        for ((label, baseUrl) in scriptBaseUrls) {
            val result = tryFetch(baseUrl + category + "/" + scriptName)
            if (result != null) {
                return CloudScriptResult(result, null, "cloud_$label")
            }
        }
        return CloudScriptResult(null, null, "cloud_failed")
    }

    private fun fetchScriptFromManifest(
        scriptName: String,
        category: String,
        baseUrl: String,
        source: String
    ): CloudScriptResult? {
        val manifestUrl = buildString {
            append(baseUrl).append("api/v1/scripts/manifest?platform=android")
            append("&appVersionCode=").append(getAppVersionCode())
        }
        val raw = tryFetch(manifestUrl) ?: return null
        val manifestJson = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!verifyManifest(manifestJson)) return null
        val descriptor = selectRemoteDescriptor(manifestJson, scriptName, category) ?: return null
        val script = tryFetch(descriptor.url).takeIf { !it.isNullOrBlank() } ?: return null
        if (descriptor.sha256.isNotBlank() && !hashSha256(script).equals(descriptor.sha256, ignoreCase = true)) {
            return null
        }
        val metaRaw = tryFetch(descriptor.metaUrl)
            ?: buildDescriptorMeta(descriptor)
        return CloudScriptResult(script, metaRaw, source)
    }

    private fun fetchScriptMetaFromCloud(scriptName: String, category: String): String? {
        val metaName = buildMetaFileName(scriptName)
        for ((_, baseUrl) in scriptBaseUrls) {
            val result = tryFetch(baseUrl + category + "/" + metaName)
            if (result != null) {
                return result
            }
        }
        return null
    }

    private fun tryFetch(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun postFeedback(baseScriptsUrl: String, body: okhttp3.RequestBody): Boolean {
        val baseHostUrl = baseScriptsUrl.removeSuffix("scripts/")
        val request = Request.Builder()
            .url("${baseHostUrl}api/v1/script_feedback")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    /**
     * 上报脚本拉取统计（最佳努力，不影响主流程）
     *
     * 每次脚本请求都会触发一次上报，用于服务端统计：
     * - 来自云端（主域名/备用域名）
     * - 降级本地缓存
     * - 降级 assets
     */
    private fun reportScriptPullStat(scriptName: String, category: String, source: String, pullTaskId: String) {
        runCatching {
            val payload = JSONObject()
                .put("scriptName", scriptName)
                .put("category", category)
                .put("source", source)
                .put("pullTaskId", pullTaskId)
                .put("fromCloud", source == "cloud_primary" || source == "cloud_fallback")
                .put("timestamp", System.currentTimeMillis())
                .toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            for (url in pullStatUrls) {
                if (postScriptPull(url, body)) {
                    break
                }
            }
        }
    }

    private fun postScriptPull(url: String, body: okhttp3.RequestBody): Boolean {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun saveScriptToLocalCache(scriptName: String, category: String, content: String) {
        try {
            val dir = File(context.filesDir, "scripts/$category")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, scriptName)
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveScriptToScopedCache(scope: String, scriptName: String, category: String, content: String) {
        try {
            val dir = File(context.filesDir, "scripts/$scope/$category")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, scriptName).writeText(content)
        } catch (_: Exception) {
        }
    }

    private fun saveMetaToLocalCache(scriptName: String, category: String, metaRaw: String?) {
        if (metaRaw.isNullOrBlank()) return
        try {
            val dir = File(context.filesDir, "scripts/$category")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, buildMetaFileName(scriptName))
            file.writeText(metaRaw)
        } catch (_: Exception) {
        }
    }

    private fun saveMetaToScopedCache(scope: String, scriptName: String, category: String, metaRaw: String?) {
        if (metaRaw.isNullOrBlank()) return
        try {
            val dir = File(context.filesDir, "scripts/$scope/$category")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, buildMetaFileName(scriptName)).writeText(metaRaw)
        } catch (_: Exception) {
        }
    }

    private fun promoteCurrentScriptToPrevious(scriptName: String, category: String) {
        val currentScript = readScriptFromScopedCache("current", scriptName, category)
            ?: readScriptFromLocalCache(scriptName, category)
            ?: return
        val currentMeta = readMetaFromScopedCache("current", scriptName, category)
            ?: readMetaFromLocalCache(scriptName, category)
        saveScriptToScopedCache("previous_stable", scriptName, category, currentScript)
        saveMetaToScopedCache("previous_stable", scriptName, category, currentMeta)
    }

    private fun readScriptFromLocalCache(scriptName: String, category: String): String? {
        return try {
            val file = File(context.filesDir, "scripts/$category/$scriptName")
            if (file.exists()) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readScriptFromScopedCache(scope: String, scriptName: String, category: String): String? {
        return try {
            val file = File(context.filesDir, "scripts/$scope/$category/$scriptName")
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readMetaFromLocalCache(scriptName: String, category: String): String? {
        return try {
            val file = File(context.filesDir, "scripts/$category/${buildMetaFileName(scriptName)}")
            if (file.exists()) {
                file.readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readMetaFromScopedCache(scope: String, scriptName: String, category: String): String? {
        return try {
            val file = File(context.filesDir, "scripts/$scope/$category/${buildMetaFileName(scriptName)}")
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readScriptFromAssets(scriptName: String, category: String): String {
        return try {
            val path = if (category.isNotEmpty()) "$category/$scriptName" else scriptName
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildCacheKey(category: String, scriptName: String): String {
        return "$category/$scriptName"
    }

    private fun buildMetaFileName(scriptName: String): String {
        val base = scriptName.removeSuffix(".js")
        return "$base.meta.json"
    }

    private suspend fun updateMemoryCache(cacheKey: String, content: String) {
        cacheMutex.withLock {
            memoryCache[cacheKey] = MemoryCacheEntry(content, System.currentTimeMillis())
        }
    }

    private fun isMemoryCacheValid(fetchedAt: Long): Boolean {
        val now = System.currentTimeMillis()
        return now - fetchedAt <= memoryTtlMillis
    }

    private fun normalizePathSegment(value: String): String? {
        if (value.isBlank()) return null
        val trimmed = value.trim()
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) return null
        val isValid = trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        return if (isValid) trimmed else null
    }

    private fun verifyScript(content: String, metaRaw: String?, allowUnsigned: Boolean): Boolean {
        val meta = parseScriptMeta(metaRaw) ?: return allowUnsigned
        val sha256 = hashSha256(content)
        if (!sha256.equals(meta.sha256, ignoreCase = true)) return false
        if (scriptVerifyPublicKey.isBlank()) return allowUnsigned
        if (meta.signature.isBlank() || meta.alg != "rsa-sha256") return false
        return verifyRsaSignature(content, meta.signature)
    }

    private fun parseScriptMeta(raw: String?): ScriptMeta? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            ScriptMeta(
                sha256 = json.optString("sha256"),
                signature = json.optString("signature"),
                alg = json.optString("alg"),
                version = json.optInt("version").takeIf { it > 0 }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun hashSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun verifyRsaSignature(content: String, signature: String): Boolean {
        return try {
            val cleanKey = scriptVerifyPublicKey
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\n", "")
                .replace("\\s".toRegex(), "")
            val decoded = Base64.getDecoder().decode(cleanKey)
            val spec = X509EncodedKeySpec(decoded)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(content.toByteArray())
            verifier.verify(Base64.getDecoder().decode(signature))
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyManifest(json: JSONObject): Boolean {
        if (scriptVerifyPublicKey.isBlank()) return false
        val signature = json.optString("signature")
        if (signature.isBlank()) return false
        val payload = JSONObject(json.toString())
        payload.remove("signature")
        payload.remove("alg")
        return verifyRsaSignature(payload.toString(), signature)
    }

    private fun selectRemoteDescriptor(
        manifestJson: JSONObject,
        scriptName: String,
        category: String
    ): RemoteScriptDescriptor? {
        val scripts = manifestJson.optJSONArray("scripts") ?: return null
        val appVersionCode = getAppVersionCode()
        val candidates = mutableListOf<RemoteScriptDescriptor>()
        for (index in 0 until scripts.length()) {
            val item = scripts.optJSONObject(index) ?: continue
            if (item.optString("name") != scriptName || item.optString("category") != category) continue
            val descriptor = parseRemoteDescriptor(item)
            if (descriptor.killSwitch) continue
            if (appVersionCode < descriptor.minAppVersionCode) continue
            val maxVersion = descriptor.maxAppVersionCode
            if (maxVersion != null && appVersionCode > maxVersion) continue
            if (!isInRollout(descriptor)) continue
            candidates.add(descriptor)
        }
        return candidates.maxWithOrNull(
            compareBy<RemoteScriptDescriptor> { it.priority }.thenBy { it.version }
        )
    }

    private fun parseRemoteDescriptor(item: JSONObject): RemoteScriptDescriptor {
        return RemoteScriptDescriptor(
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
            maxAppVersionCode = item.optLong("maxAppVersionCode", 0L).takeIf { it > 0L },
            parserApiVersion = item.optInt("parserApiVersion", 1),
            runnerContractVersion = item.optInt("runnerContractVersion", 1),
            schoolBindingId = item.optString("schoolBindingId").takeIf { value -> value.isNotBlank() },
            selectionPolicy = item.optString("selectionPolicy", "auto"),
            dependencies = emptyList(),
            changelog = item.optString("changelog")
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun buildDescriptorMeta(descriptor: RemoteScriptDescriptor): String? {
        if (descriptor.sha256.isBlank() || descriptor.signature.isBlank()) return null
        return JSONObject()
            .put("sha256", descriptor.sha256)
            .put("signature", descriptor.signature)
            .put("alg", descriptor.alg)
            .put("version", descriptor.version)
            .put("releaseId", descriptor.releaseId)
            .toString()
    }

    private fun isInRollout(descriptor: RemoteScriptDescriptor): Boolean {
        val rollout = descriptor.rolloutPercent.coerceIn(0, 100)
        if (rollout >= 100) return true
        if (rollout <= 0) return false
        val bucketSource = getInstallBucketId() + ":" + descriptor.scriptId + ":" + descriptor.releaseId
        val bucket = hashSha256(bucketSource).take(8).toLong(16) % 100
        return bucket < rollout
    }

    private fun getInstallBucketId(): String {
        val preferences = context.getSharedPreferences("script_runtime", Context.MODE_PRIVATE)
        val existing = preferences.getString("install_bucket_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString()
        preferences.edit().putString("install_bucket_id", created).apply()
        return created
    }

    private fun getAppVersionCode(): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
