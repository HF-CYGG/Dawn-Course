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
import okhttp3.ConnectionSpec
import java.util.Collections
import retrofit2.Response
import retrofit2.Call

/**
 * 更新检查 API 接口定义
 * 通过 Retrofit 调用服务端接口获取版本信息
 */
interface UpdateApi {
    /**
     * 获取最新版本信息
     * 请求 version.json 文件
     */
    @GET("version.json")
    fun getUpdateInfo(): Call<UpdateInfo>
}

/**
 * 更新仓库
 * 负责从网络获取最新的应用版本信息，支持多域名备选策略
 *
 * 主要职责：
 * 1. 封装 Retrofit 网络请求
 * 2. 实现主域名失败后的备用域名重试机制
 * 3. 统一异常处理，返回 Result 类型
 */
@Singleton
class UpdateRepository @Inject constructor() {
    /**
     * 检查更新失败异常（可恢复）
     *
     * 设计目标：
     * - 对外返回 Result.failure 时提供“用户可理解”的 message
     * - 保留底层 cause（网络异常/HTTP 异常等）用于定位问题，但不打印堆栈
     */
    class UpdateCheckException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    // 配置 OkHttpClient，设置超时和连接规格
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // 增加超时时间，适应弱网环境
        .readTimeout(15, TimeUnit.SECONDS)
        // 显式允许明文传输，某些设备或内网环境可能需要
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS))
        .build()

    /**
     * 创建 Retrofit API 实例
     * @param baseUrl 基础 URL
     */
    private fun createApi(baseUrl: String): UpdateApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(UpdateApi::class.java)
    }

    // 懒加载主 API 实例
    private val primaryApi by lazy { createApi("http://yyh163.xyz:10000/") }
    // 懒加载备用 API 实例
    private val fallbackApi by lazy { createApi("http://47.105.76.193/") }

    /**
     * 检查更新
     * 依次尝试主域名和备用域名，确保高可用性
     *
     * 策略：
     * 1. 优先请求主域名
     * 2. 如果主域名请求失败（网络错误、非 200 响应、空 Body），则尝试备用 IP
     * 3. 如果两者都失败，抛出包含详细原因的异常
     *
     * @return Result<UpdateInfo> 更新信息结果封装
     */
    suspend fun checkUpdate(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        // 1. 尝试主域名
        val primaryAttempt = runCatching {
            val response = primaryApi.getUpdateInfo().execute()
            val body = response.body()
            // 避免使用非空断言：即使 isSuccessful，也可能出现 body 为空的情况（服务端返回空/解析失败）。
            if (response.isSuccessful && body != null) body
            // 如果主域名响应失败（如 404 / body 为空），抛出异常以便尝试 fallback
            else throw Exception("Primary API failed: HTTP ${response.code()}")
        }
        if (primaryAttempt.isSuccess) return@withContext Result.success(primaryAttempt.getOrThrow())
        val primaryFailure = primaryAttempt.exceptionOrNull() ?: Exception("Primary API failed")

        // 2. 尝试兜底 IP
        try {
            val response = fallbackApi.getUpdateInfo().execute()
            val body = response.body()
            // 避免使用非空断言：即使 isSuccessful，也可能出现 body 为空的情况（服务端返回空/解析失败）。
            if (response.isSuccessful && body != null) return@withContext Result.success(body)
            val fallbackFailure = Exception("Fallback API failed: HTTP ${response.code()}")
            val ex = UpdateCheckException("检查更新失败，请稍后重试", fallbackFailure)
            ex.addSuppressed(primaryFailure)
            return@withContext Result.failure(ex)
        } catch (e: Throwable) {
            // 主/备均失败：返回“用户可理解”的错误信息，并保留 cause 供排查问题
            val ex = UpdateCheckException("检查更新失败，请检查网络或稍后重试", e)
            ex.addSuppressed(primaryFailure)
            return@withContext Result.failure(ex)
        }
    }
}
