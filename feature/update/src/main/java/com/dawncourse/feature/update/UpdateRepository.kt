package com.dawncourse.feature.update

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface UpdateApi {
    @GET("version.json")
    suspend fun getUpdateInfo(): UpdateInfo
}

@Singleton
class UpdateRepository @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun createApi(baseUrl: String): UpdateApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(UpdateApi::class.java)
    }

    private val primaryApi by lazy { createApi("http://yyh163.xyz:10000/") }
    private val fallbackApi by lazy { createApi("http://47.105.76.193/") }

    /**
     * 检查更新
     *
     * @return Result<UpdateInfo> 更新信息
     */
    suspend fun checkUpdate(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        // 1. 尝试主域名
        try {
            val info = primaryApi.getUpdateInfo()
            return@withContext Result.success(info)
        } catch (e: Exception) {
            e.printStackTrace()
            // 失败则继续尝试兜底 IP
        }

        // 2. 尝试兜底 IP
        try {
            val info = fallbackApi.getUpdateInfo()
            return@withContext Result.success(info)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.failure(e)
        }
    }
}
