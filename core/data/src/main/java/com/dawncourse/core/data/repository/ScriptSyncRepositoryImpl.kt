package com.dawncourse.core.data.repository

import android.content.Context
import com.dawncourse.core.data.BuildConfig
import com.dawncourse.core.data.network.CloudBackendEndpoints
import com.dawncourse.core.domain.model.RemoteScriptDescriptor
import com.dawncourse.core.domain.model.ScriptSchoolContext
import com.dawncourse.core.domain.model.ScriptDependency
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

    /** 签名脚本与 manifest 的只读下载端点，允许兼容旧部署的 HTTP 回退。 */
    private val signedReadOnlyEndpoints = CloudBackendEndpoints.signedReadOnlyBaseUrls

    /** 携带诊断、统计或运行结果的写入端点，必须始终使用 HTTPS。 */
    private val sensitiveApiEndpoints = CloudBackendEndpoints.sensitiveApiBaseUrls
    /** Manifest 查询包含设备桶与学校范围，只能走 HTTPS API。 */
    private val manifestApiEndpoints = sensitiveApiEndpoints
    private val scriptBaseUrls = signedReadOnlyEndpoints.map { it.label to "${it.baseUrl}scripts/" }
    private val feedbackBaseUrls = sensitiveApiEndpoints.map { "${it.baseUrl}scripts/" }
    private val pullStatUrls = sensitiveApiEndpoints.map { "${it.baseUrl}api/v1/script_pull" }
    private val activationEventUrls = sensitiveApiEndpoints.map {
        "${it.baseUrl}api/v1/scripts/activation-events"
    }

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
    private val releaseCache = ScriptReleaseCache(File(context.filesDir, "scripts/v2"))

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

    /** 下载 manifest 指定的不可变候选并写入 staging。 */
    override suspend fun prepareScriptCandidate(
        descriptor: RemoteScriptDescriptor,
        pullTaskId: String
    ): ScriptFetchResult = withContext(Dispatchers.IO) {
        val scriptKey = descriptor.scriptKey.ifBlank {
            "${descriptor.targetType}/${descriptor.category}/${descriptor.name}/${descriptor.scopeKind}/${descriptor.scopeId}"
        }
        if (releaseCache.matchesActive(scriptKey, descriptor.releaseId, descriptor.sha256)) {
            val active = releaseCache.readActive(scriptKey) ?: return@withContext readReleaseFallback(descriptor, scriptKey)
            reportScriptPullStat(descriptor.name, descriptor.category, "release_active", pullTaskId)
            return@withContext ScriptFetchResult(
                content = active.content,
                fromCloud = false,
                source = "release_active",
                releaseId = active.releaseId,
                scriptKey = active.scriptKey,
                scopeKind = descriptor.scopeKind,
                scopeId = descriptor.scopeId,
                schoolSystemType = descriptor.schoolSystemType,
                version = descriptor.version,
                pendingActivation = false,
                dependencyContents = active.dependencies
            )
        }
        if (descriptor.releaseId.isNotBlank() && releaseCache.isQuarantined(scriptKey, descriptor.releaseId)) {
            return@withContext readReleaseFallback(descriptor, scriptKey)
        }
        val remote = fetchImmutableBundle(descriptor, scriptKey)
        if (remote != null) {
            releaseCache.stage(remote)
            reportActivationEvent(descriptor, "verified", "")
            reportScriptPullStat(descriptor.name, descriptor.category, "release_staging", pullTaskId)
            return@withContext ScriptFetchResult(
                content = remote.content,
                fromCloud = true,
                source = "release_staging",
                releaseId = remote.releaseId,
                scriptKey = remote.scriptKey,
                scopeKind = descriptor.scopeKind,
                scopeId = descriptor.scopeId,
                schoolSystemType = descriptor.schoolSystemType,
                version = descriptor.version,
                pendingActivation = true,
                dependencyContents = remote.dependencies
            )
        }
        readReleaseFallback(descriptor, scriptKey)
    }

    /** 解析成功后切换 active 指针并上报聚合激活事件。 */
    override suspend fun activatePreparedScript(result: ScriptFetchResult): Boolean = withContext(Dispatchers.IO) {
        if (!result.pendingActivation || result.scriptKey.isBlank() || result.releaseId.isBlank()) return@withContext false
        val activated = releaseCache.activate(result.scriptKey, result.releaseId)
        if (activated) {
            cacheMutex.withLock { memoryCache.remove(result.scriptKey) }
            val keyParts = result.scriptKey.split("/", limit = 5)
            if (keyParts.size >= 3) {
                releaseCache.markLegacySuperseded(keyParts[1], keyParts[2])
            }
            reportActivationEvent(result, "trial_passed", "")
            reportActivationEvent(result, "activated", "")
        }
        activated
    }

    /** 解析失败后隔离 staging，并保留当前 active。 */
    override suspend fun quarantinePreparedScript(result: ScriptFetchResult, reason: String): Boolean = withContext(Dispatchers.IO) {
        if (!result.pendingActivation || result.scriptKey.isBlank() || result.releaseId.isBlank()) return@withContext false
        val quarantined = releaseCache.quarantine(result.scriptKey, result.releaseId, reason)
        if (quarantined) {
            reportActivationEvent(result, "failed", reason)
            reportActivationEvent(result, "quarantined", reason)
        }
        quarantined
    }

    /** active release 失败时恢复 previous stable，并上报匿名聚合回滚事件。 */
    override suspend fun rollbackActiveScript(result: ScriptFetchResult, reason: String): Boolean = withContext(Dispatchers.IO) {
        if (result.pendingActivation || result.source != "release_active" ||
            result.scriptKey.isBlank() || result.releaseId.isBlank()
        ) {
            return@withContext false
        }
        reportActivationEvent(result, "failed", reason)
        val rolledBack = releaseCache.rollbackActive(result.scriptKey, result.releaseId, reason)
        if (rolledBack) {
            cacheMutex.withLock { memoryCache.remove(result.scriptKey) }
            reportActivationEvent(result, "quarantined", reason)
            reportActivationEvent(result, "rolled_back", reason)
        }
        rolledBack
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
            if (releaseCache.isLegacySuperseded(safeCategory, safeScriptName)) {
                val v2Active = readV2ActiveForLegacyName(safeCategory, safeScriptName)
                if (v2Active != null) {
                    updateMemoryCache(cacheKey, v2Active.content)
                    reportScriptPullStat(safeScriptName, safeCategory, v2Active.source, pullTaskId)
                    return@withContext v2Active
                }
                val assetsScript = readScriptFromAssets(safeScriptName, safeCategory)
                reportScriptPullStat(safeScriptName, safeCategory, "assets", pullTaskId)
                return@withContext ScriptFetchResult(assetsScript, false, "assets")
            }
            val remoteResult = runCatching { fetchScriptFromCloud(safeScriptName, safeCategory) }.getOrNull()
            val remoteScript = remoteResult?.content
            val remoteMeta = remoteResult?.metaRaw
                ?: runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            if (!remoteScript.isNullOrBlank() && verifyScript(remoteScript, remoteMeta, allowUnsigned = false)) {
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
            if (!cachedScript.isNullOrBlank() && verifyScript(cachedScript, cachedMeta, allowUnsigned = false)) {
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
            if (!previousScript.isNullOrBlank() && verifyScript(previousScript, previousMeta, allowUnsigned = false)) {
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
            feedbackBaseUrls.any { baseUrl ->
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
        for ((label, baseUrl) in manifestApiEndpoints.map { it.label to it.baseUrl }) {
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

    override suspend fun listParserCandidates(
        schoolSystemType: String,
        schoolId: String
    ): List<RemoteScriptDescriptor> {
        return withContext(Dispatchers.IO) {
            val resolvedSystemType = schoolSystemType.ifBlank { getSavedSchoolSystemType() }
            val resolvedSchoolId = schoolId.ifBlank { getSchoolIdForScript(resolvedSystemType) }
            for ((_, baseUrl) in manifestApiEndpoints.map { it.label to it.baseUrl }) {
                val manifestJson = fetchManifestJson(baseUrl, resolvedSystemType, resolvedSchoolId)
                    ?: continue
                val scripts = manifestJson.optJSONArray("scripts") ?: continue
                val appVersionCode = getAppVersionCode()
                val candidates = mutableListOf<RemoteScriptDescriptor>()
                for (index in 0 until scripts.length()) {
                    val item = scripts.optJSONObject(index) ?: continue
                    if (item.optString("category") != "parsers") continue
                    val descriptor = parseRemoteDescriptor(item)
                    if (descriptor.killSwitch) continue
                    if (appVersionCode < descriptor.minAppVersionCode) continue
                    val maxVersion = descriptor.maxAppVersionCode
                    if (maxVersion != null && appVersionCode > maxVersion) continue
                    if (!isInRollout(descriptor)) continue
                    candidates.add(descriptor)
                }
                if (candidates.isNotEmpty()) {
                    return@withContext candidates.sortedWith(
                        compareByDescending<RemoteScriptDescriptor> { it.priority }
                            .thenByDescending { it.version }
                    )
                }
            }
            emptyList()
        }
    }

    /** 拉取并验签 manifest，失败返回 null */
    private fun fetchManifestJson(
        baseUrl: String,
        schoolSystemType: String,
        schoolId: String
    ): JSONObject? {
        val manifestUrl = buildString {
            append(baseUrl).append("api/v1/scripts/manifest?platform=android")
            append("&appVersionCode=").append(getAppVersionCode())
            append("&installBucketIdHash=").append(urlEncode(hashSha256(getInstallBucketId())))
            append("&schoolSystemType=").append(urlEncode(schoolSystemType))
            if (schoolId.isNotBlank()) {
                append("&schoolId=").append(urlEncode(schoolId))
            }
            append("&selectionPolicy=").append(urlEncode(getScriptSelectionPolicy()))
        }
        val raw = tryFetch(manifestUrl) ?: return null
        val manifestJson = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return if (verifyManifest(manifestJson)) manifestJson else null
    }

    /** 读取本地记录的教务系统类型（由导入流程写入） */
    private fun getSavedSchoolSystemType(): String {
        val preferences = context.getSharedPreferences(
            ScriptSchoolContext.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        return preferences.getString(ScriptSchoolContext.KEY_SCHOOL_SYSTEM_TYPE, "").orEmpty()
    }

    private fun fetchScriptFromManifest(
        scriptName: String,
        category: String,
        baseUrl: String,
        source: String
    ): CloudScriptResult? {
        val schoolSystemType = systemTypeForScript(scriptName)
        val manifestJson = fetchManifestJson(
            baseUrl = baseUrl,
            schoolSystemType = schoolSystemType,
            schoolId = getSchoolIdForScript(schoolSystemType)
        ) ?: return null
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

    /** 下载、验签并解析一个不可变 release bundle。 */
    private fun fetchImmutableBundle(
        descriptor: RemoteScriptDescriptor,
        expectedScriptKey: String
    ): CachedScriptRelease? {
        if (descriptor.releaseId.isBlank()) return null
        if (descriptor.bundleUrl.isBlank()) {
            val content = tryFetch(descriptor.url)?.takeIf { it.isNotBlank() } ?: return null
            val metaRaw = tryFetch(descriptor.metaUrl) ?: buildDescriptorMeta(descriptor)
            if (!isWithinByteLimit(content, MAX_SCRIPT_BYTES) || !verifyScript(content, metaRaw, allowUnsigned = false)) return null
            return CachedScriptRelease(expectedScriptKey, descriptor.releaseId, content, metaRaw.orEmpty(), emptyList())
        }
        val raw = tryFetch(descriptor.bundleUrl) ?: return null
        val bundle = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!verifyBundle(bundle)) return null
        if (bundle.optString("releaseId") != descriptor.releaseId) return null
        val bundleScriptKey = bundle.optString("scriptKey")
        if (bundleScriptKey.isNotBlank() && bundleScriptKey != expectedScriptKey) return null
        val script = bundle.optJSONObject("script") ?: return null
        val content = script.optString("content")
        val metaRaw = JSONObject()
            .put("sha256", script.optString("sha256"))
            .put("signature", script.optString("signature"))
            .put("alg", script.optString("alg", "rsa-sha256"))
            .put("version", bundle.optInt("version", descriptor.version))
            .put("releaseId", descriptor.releaseId)
            .toString()
        if (!isWithinByteLimit(content, MAX_SCRIPT_BYTES) || !verifyScript(content, metaRaw, allowUnsigned = false)) return null
        val dependencies = mutableListOf<String>()
        val dependencyArray = bundle.optJSONArray("dependencies")
        if (dependencyArray != null) {
            for (index in 0 until dependencyArray.length()) {
                val dependency = dependencyArray.optJSONObject(index) ?: return null
                val dependencyContent = dependency.optString("content")
                val dependencyMeta = JSONObject()
                    .put("sha256", dependency.optString("sha256"))
                    .put("signature", dependency.optString("signature"))
                    .put("alg", dependency.optString("alg", "rsa-sha256"))
                    .put("version", dependency.optInt("version", 0))
                    .put("releaseId", dependency.optString("releaseId"))
                    .toString()
                if (!isWithinByteLimit(dependencyContent, MAX_SCRIPT_BYTES) ||
                    !verifyScript(dependencyContent, dependencyMeta, allowUnsigned = false)
                ) {
                    return null
                }
                dependencies.add(dependencyContent)
            }
        }
        val totalBytes = content.toByteArray(Charsets.UTF_8).size +
            dependencies.sumOf { dependency -> dependency.toByteArray(Charsets.UTF_8).size }
        if (totalBytes > MAX_BUNDLE_BYTES) return null
        return CachedScriptRelease(expectedScriptKey, descriptor.releaseId, content, metaRaw, dependencies)
    }

    /** bundle 整体签名防止依赖列表或作用域元数据被替换。 */
    private fun verifyBundle(bundle: JSONObject): Boolean {
        if (scriptVerifyPublicKey.isBlank()) return false
        val signature = bundle.optString("bundleSignature")
        if (signature.isBlank() || bundle.optString("bundleAlg") != "rsa-sha256") return false
        val payload = JSONObject(bundle.toString()).apply {
            remove("bundleSignature")
            remove("bundleAlg")
        }
        return verifyRsaSignature(canonicalJson(payload), signature)
    }

    /** 远端不可用或候选已隔离时，依次回落 active、previous stable 与 assets。 */
    private fun readReleaseFallback(descriptor: RemoteScriptDescriptor, scriptKey: String): ScriptFetchResult {
        val active = releaseCache.readActive(scriptKey)
        if (active != null) {
            return ScriptFetchResult(
                content = active.content,
                fromCloud = false,
                source = "release_active",
                releaseId = active.releaseId,
                scriptKey = active.scriptKey,
                scopeKind = descriptor.scopeKind,
                scopeId = descriptor.scopeId,
                schoolSystemType = descriptor.schoolSystemType,
                version = descriptor.version,
                dependencyContents = active.dependencies
            )
        }
        val previous = releaseCache.readPreviousStable(scriptKey)
        if (previous != null) {
            return ScriptFetchResult(
                content = previous.content,
                fromCloud = false,
                source = "release_previous_stable",
                releaseId = previous.releaseId,
                scriptKey = previous.scriptKey,
                scopeKind = descriptor.scopeKind,
                scopeId = descriptor.scopeId,
                schoolSystemType = descriptor.schoolSystemType,
                version = descriptor.version,
                dependencyContents = previous.dependencies
            )
        }
        return ScriptFetchResult(
            content = readScriptFromAssets(descriptor.name, descriptor.category),
            fromCloud = false,
            source = "assets",
            scriptKey = scriptKey,
            scopeKind = descriptor.scopeKind,
            scopeId = descriptor.scopeId,
            schoolSystemType = descriptor.schoolSystemType
        )
    }

    /** manifest 离线时按当前学校优先、系统通用其次读取已激活的 V2 parser。 */
    private fun readV2ActiveForLegacyName(category: String, scriptName: String): ScriptFetchResult? {
        if (category != "parsers") return null
        val systemType = systemTypeForScript(scriptName)
        if (systemType.isBlank()) return null
        val schoolId = getSchoolIdForScript(systemType)
        val candidateKeys = buildList {
            if (schoolId.isNotBlank()) add("parser/$category/$scriptName/school/$schoolId")
            add("parser/$category/$scriptName/system/$systemType")
        }
        for (scriptKey in candidateKeys) {
            val active = releaseCache.readActive(scriptKey) ?: continue
            return ScriptFetchResult(
                content = active.content,
                fromCloud = false,
                source = "release_active",
                releaseId = active.releaseId,
                scriptKey = active.scriptKey,
                scopeKind = if (scriptKey.contains("/school/")) "school" else "system",
                scopeId = if (scriptKey.contains("/school/")) schoolId else systemType,
                schoolSystemType = systemType,
                dependencyContents = active.dependencies
            )
        }
        return null
    }

    /** 上报 descriptor 对应的匿名聚合激活事件。 */
    private fun reportActivationEvent(descriptor: RemoteScriptDescriptor, eventType: String, errorCode: String) {
        reportActivationEvent(
            ScriptFetchResult(
                content = "",
                fromCloud = true,
                source = "release_staging",
                releaseId = descriptor.releaseId,
                scriptKey = descriptor.scriptKey,
                scopeKind = descriptor.scopeKind,
                scopeId = descriptor.scopeId,
                schoolSystemType = descriptor.schoolSystemType
            ),
            eventType,
            errorCode
        )
    }

    /** 上报 release、学校和结果维度，不包含安装桶或设备标识。 */
    private fun reportActivationEvent(result: ScriptFetchResult, eventType: String, errorCode: String) {
        if (result.releaseId.isBlank()) return
        runCatching {
            val payload = JSONObject()
                .put("releaseId", result.releaseId)
                .put("schoolId", result.scopeId.takeIf { result.scopeKind == "school" }.orEmpty())
                .put("schoolSystemType", result.schoolSystemType)
                .put("eventType", eventType)
                .put("errorCode", errorCode.take(80))
                .toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            activationEventUrls.any { url -> postActivationEvent(url, body) }
        }
    }

    /** 最佳努力提交激活事件。 */
    private fun postActivationEvent(url: String, body: okhttp3.RequestBody): Boolean {
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response -> return response.isSuccessful }
    }

    /** 按 UTF-8 字节数限制远端代码。 */
    private fun isWithinByteLimit(content: String, limit: Int): Boolean {
        return content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= limit
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
        return verifyRsaSignature(canonicalJson(payload), signature)
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
            dependencies = item.optJSONArray("dependencies").toDependencyList(),
            changelog = item.optString("changelog"),
            scriptKey = item.optString("scriptKey"),
            bundleUrl = item.optString("bundleUrl"),
            scopeKind = item.optString("scopeKind", "global"),
            scopeId = item.optString("scopeId"),
            schoolSystemType = item.optString("schoolSystemType", "UNKNOWN")
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

    private fun JSONArray?.toDependencyList(): List<ScriptDependency> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    ScriptDependency(
                        category = item.optString("category"),
                        name = item.optString("name"),
                        version = item.optInt("version", 0),
                        releaseId = item.optString("releaseId"),
                        url = item.optString("url"),
                        metaUrl = item.optString("metaUrl"),
                        sha256 = item.optString("sha256"),
                        signature = item.optString("signature"),
                        alg = item.optString("alg", "rsa-sha256")
                    )
                )
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

    private fun getScriptSelectionPolicy(): String {
        val preferences = context.getSharedPreferences("script_runtime", Context.MODE_PRIVATE)
        return preferences.getString("selection_policy", "auto") ?: "auto"
    }

    private fun getSchoolIdForScript(schoolSystemType: String): String {
        if (schoolSystemType.isBlank()) return ""
        val preferences = context.getSharedPreferences(
            ScriptSchoolContext.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val savedSystemType = preferences.getString(ScriptSchoolContext.KEY_SCHOOL_SYSTEM_TYPE, "").orEmpty()
        if (savedSystemType.isNotBlank() && savedSystemType != schoolSystemType) return ""
        return preferences.getString(ScriptSchoolContext.KEY_SCHOOL_ID, "").orEmpty()
    }

    private fun systemTypeForScript(scriptName: String): String {
        val lower = scriptName.lowercase()
        return when {
            lower.contains("qiangzhi") -> "QIANGZHI"
            lower.contains("kingosoft") -> "KINGOSOFT"
            lower.contains("qidi") -> "QIDI"
            lower.contains("chaoxing") -> "CHAOXING"
            lower.contains("zhengfang") || lower.startsWith("zf_") -> "ZF"
            else -> ""
        }
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

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
                }
            }
            is JSONArray -> {
                buildString {
                    append("[")
                    for (index in 0 until value.length()) {
                        if (index > 0) append(",")
                        append(canonicalJson(value.opt(index)))
                    }
                    append("]")
                }
            }
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 512 * 1024
        const val MAX_BUNDLE_BYTES = 512 * 1024
    }
}
