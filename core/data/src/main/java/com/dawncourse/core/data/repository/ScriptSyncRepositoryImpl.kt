package com.dawncourse.core.data.repository

import android.content.Context
import com.dawncourse.core.domain.repository.ScriptSyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            if (!remoteScript.isNullOrBlank()) {
                saveScriptToLocalCache(safeScriptName, safeCategory, remoteScript)
                updateMemoryCache(cacheKey, remoteScript)
                return@withContext remoteScript
            }

            val cachedScript = readScriptFromLocalCache(safeScriptName, safeCategory)
            if (!cachedScript.isNullOrBlank()) {
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
            if (!remoteScript.isNullOrBlank()) {
                saveScriptToLocalCache(safeScriptName, safeCategory, remoteScript)
                updateMemoryCache(cacheKey, remoteScript)
                return@withContext remoteScript
            }

            val cachedScript = readScriptFromLocalCache(safeScriptName, safeCategory)
            if (!cachedScript.isNullOrBlank()) {
                updateMemoryCache(cacheKey, cachedScript)
                return@withContext cachedScript
            }

            readScriptFromAssets(safeScriptName, safeCategory)
        }
    }

    private fun fetchScriptFromCloud(scriptName: String, category: String): String? {
        var result = tryFetch(primaryUrl + category + "/" + scriptName)
        if (result != null) return result
        result = tryFetch(fallbackUrl + category + "/" + scriptName)
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
}
