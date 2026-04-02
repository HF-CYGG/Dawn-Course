package com.dawncourse.core.data.repository

import android.content.Context
import com.dawncourse.core.data.BuildConfig
import com.dawncourse.core.domain.repository.ScriptSyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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
import org.json.JSONObject

@Singleton
class ScriptSyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ScriptSyncRepository {

    // 云端脚本的存储路径
    private val primaryUrl = "http://yyh163.xyz:10000/scripts/"
    private val fallbackUrl = "http://47.105.76.193/scripts/"

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

    override suspend fun getScript(scriptName: String, category: String): String {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category) ?: return@withContext ""
            val safeScriptName = normalizePathSegment(scriptName) ?: return@withContext ""
            val cacheKey = buildCacheKey(safeCategory, safeScriptName)

            val cached = cacheMutex.withLock { memoryCache[cacheKey] }
            if (cached != null && isMemoryCacheValid(cached.fetchedAt)) {
                return@withContext cached.content
            }

            val remoteScript = runCatching { fetchScriptFromCloud(safeScriptName, safeCategory) }.getOrNull()
            val remoteMeta = runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            if (!remoteScript.isNullOrBlank() && verifyScript(remoteScript, remoteMeta, safeCategory != "parsers")) {
                saveScriptToLocalCache(safeScriptName, safeCategory, remoteScript)
                saveMetaToLocalCache(safeScriptName, safeCategory, remoteMeta)
                updateMemoryCache(cacheKey, remoteScript)
                return@withContext remoteScript
            }

            val cachedScript = readScriptFromLocalCache(safeScriptName, safeCategory)
            val cachedMeta = readMetaFromLocalCache(safeScriptName, safeCategory)
            if (!cachedScript.isNullOrBlank() && verifyScript(cachedScript, cachedMeta, safeCategory != "parsers")) {
                updateMemoryCache(cacheKey, cachedScript)
                return@withContext cachedScript
            }

            readScriptFromAssets(safeScriptName, safeCategory)
        }
    }

    override suspend fun fetchAndCacheScript(scriptName: String, category: String): String {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category) ?: return@withContext ""
            val safeScriptName = normalizePathSegment(scriptName) ?: return@withContext ""
            val cacheKey = buildCacheKey(safeCategory, safeScriptName)

            val remoteScript = runCatching { fetchScriptFromCloud(safeScriptName, safeCategory) }.getOrNull()
            val remoteMeta = runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            if (!remoteScript.isNullOrBlank() && verifyScript(remoteScript, remoteMeta, safeCategory != "parsers")) {
                saveScriptToLocalCache(safeScriptName, safeCategory, remoteScript)
                saveMetaToLocalCache(safeScriptName, safeCategory, remoteMeta)
                updateMemoryCache(cacheKey, remoteScript)
                return@withContext remoteScript
            }

            val cachedScript = readScriptFromLocalCache(safeScriptName, safeCategory)
            val cachedMeta = readMetaFromLocalCache(safeScriptName, safeCategory)
            if (!cachedScript.isNullOrBlank() && verifyScript(cachedScript, cachedMeta, safeCategory != "parsers")) {
                updateMemoryCache(cacheKey, cachedScript)
                return@withContext cachedScript
            }

            readScriptFromAssets(safeScriptName, safeCategory)
        }
    }

    override suspend fun getScriptVersion(scriptName: String, category: String): Int? {
        return withContext(Dispatchers.IO) {
            val safeCategory = normalizePathSegment(category) ?: return@withContext null
            val safeScriptName = normalizePathSegment(scriptName) ?: return@withContext null
            val metaRaw = readMetaFromLocalCache(safeScriptName, safeCategory)
                ?: runCatching { fetchScriptMetaFromCloud(safeScriptName, safeCategory) }.getOrNull()
            parseScriptMeta(metaRaw)?.version
        }
    }

    override suspend fun reportScriptParseFeedback(
        scriptName: String,
        category: String,
        success: Boolean,
        errorMessage: String?,
        sourceUrl: String?
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
                .toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            runCatching { postFeedback(primaryUrl, body) }.getOrElse {
                runCatching { postFeedback(fallbackUrl, body) }.getOrDefault(false)
            }
        }
    }

    private fun fetchScriptFromCloud(scriptName: String, category: String): String? {
        var result = tryFetch(primaryUrl + category + "/" + scriptName)
        if (result != null) return result
        result = tryFetch(fallbackUrl + category + "/" + scriptName)
        return result
    }

    private fun fetchScriptMetaFromCloud(scriptName: String, category: String): String? {
        val metaName = buildMetaFileName(scriptName)
        var result = tryFetch(primaryUrl + category + "/" + metaName)
        if (result != null) return result
        result = tryFetch(fallbackUrl + category + "/" + metaName)
        return result
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
        if (scriptVerifyPublicKey.isBlank()) return true
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
}
