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

    private val api: UpdateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://yyh163.xyz/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(UpdateApi::class.java)
    }

    /**
     * 检查更新
     *
     * @return Result<UpdateInfo> 更新信息
     */
    suspend fun checkUpdate(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val info = api.getUpdateInfo()
            Result.success(info)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
