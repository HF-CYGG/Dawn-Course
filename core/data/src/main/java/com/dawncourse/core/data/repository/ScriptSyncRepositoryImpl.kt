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

@Singleton
class ScriptSyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : ScriptSyncRepository {

    // 云端脚本的存储路径
    private val primaryUrl = "http://yyh163.xyz:10000/scripts/"
    private val fallbackUrl = "http://47.105.76.193/scripts/"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun getScript(scriptName: String, category: String): String {
        return withContext(Dispatchers.IO) {
            // 1. 优先尝试从云端拉取最新脚本
            val remoteScript = runCatching { fetchScriptFromCloud(scriptName, category) }.getOrNull()
            if (!remoteScript.isNullOrBlank()) {
                saveScriptToLocalCache(scriptName, category, remoteScript)
                return@withContext remoteScript
            }

            // 2. 云端拉取失败，尝试读取本地缓存
            val cachedScript = readScriptFromLocalCache(scriptName, category)
            if (!cachedScript.isNullOrBlank()) {
                return@withContext cachedScript
            }

            // 3. 本地缓存也没有，读取 app 内置的 assets
            readScriptFromAssets(scriptName, category)
        }
    }

    override suspend fun fetchAndCacheScript(scriptName: String, category: String): String {
        return getScript(scriptName, category)
    }

    private fun fetchScriptFromCloud(scriptName: String, category: String): String? {
        // 先尝试主服务器
        var result = tryFetch(primaryUrl + category + "/" + scriptName)
        if (result != null) return result

        // 失败则尝试备用服务器
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
}
