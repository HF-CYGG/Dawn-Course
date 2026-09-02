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
 * 负责从网络获取最新的应用版本信息，并在结果进入 UI 前校验下载链接。
 *
 * 主要职责：
 * 1. 封装 Retrofit 网络请求
 * 2. 只使用已确认的 HTTPS 元数据入口
 * 3. 统一异常处理，返回 Result 类型
 */
@Singleton
open class UpdateHttpClientFactory @Inject constructor() {
    /** 创建更新检查专用客户端，允许旧 TLS 服务端在 HTTPS 前提下保持兼容。 */
    open fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionSpecs(buildUpdateConnectionSpecs())
        .build()
}

@Singleton
class UpdateRepository @Inject constructor(
    private val clientFactory: UpdateHttpClientFactory,
) {

    /**
     * 检查更新失败异常（可恢复）
     *
     * 设计目标：
     * - 对外返回 Result.failure 时提供“用户可理解”的 message
     * - 保留底层 cause（网络异常/HTTP 异常等）用于定位问题，但不打印堆栈
     */
    class UpdateCheckException(
        val userMessage: String,
        val debugDetails: List<UpdateEndpointRequestException> = emptyList(),
        cause: Throwable? = null
    ) : Exception(userMessage, cause)

    /** 仅在首次更新检查构建 Retrofit 时创建，避免 Hilt 图构造阻塞冷启动。 */
    private val client: OkHttpClient by lazy(clientFactory::create)

    /**
     * 创建 Retrofit API 实例
     * @param baseUrl 基础 URL
     */
    private fun createApi(baseUrl: String): UpdateApi {
        return buildUpdateApi(baseUrl, client)
    }

    private val endpointConfigs = buildUpdateEndpointConfigs()
    // 更新元数据只保留一个经确认的 HTTPS 入口。
    private val primaryEndpoint by lazy { endpointConfigs[0] }
    private val primaryApi by lazy { createApi(primaryEndpoint.baseUrl) }

    /**
     * 检查更新
     * 仅请求已确认的 HTTPS 元数据入口；请求成功也必须通过下载链接校验后才返回成功。
     *
     * @return Result<UpdateInfo> 更新信息结果封装
     */
    suspend fun checkUpdate(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val body = requestUpdateInfo(primaryApi, primaryEndpoint.label, "${primaryEndpoint.baseUrl}version.json")
            return@withContext Result.success(body)
        } catch (failure: UpdateEndpointRequestException) {
            val exception = UpdateCheckException(
                userMessage = "检查更新失败，请稍后重试",
                debugDetails = listOf(failure),
                cause = failure
            )
            return@withContext Result.failure(exception)
        } catch (failure: Throwable) {
            val endpointFailure = UpdateEndpointRequestException(
                endpointLabel = primaryEndpoint.label,
                endpointUrl = "${primaryEndpoint.baseUrl}version.json",
                stage = "request",
                detail = failure.message ?: "unknown_error",
                cause = failure
            )
            val exception = UpdateCheckException(
                userMessage = "检查更新失败，请检查网络或稍后重试",
                debugDetails = listOf(endpointFailure),
                cause = endpointFailure
            )
            return@withContext Result.failure(exception)
        }
    }

    /**
     * 执行一次更新信息请求，并在失败时返回带节点上下文的异常。
     */
    private fun requestUpdateInfo(api: UpdateApi, endpointLabel: String, endpointUrl: String): UpdateInfo {
        try {
            val response = api.getUpdateInfo().execute()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                return validateUpdateInfo(body)
                    ?: throw UpdateEndpointRequestException(
                        endpointLabel = endpointLabel,
                        endpointUrl = endpointUrl,
                        stage = "validation",
                        detail = "更新元数据中的下载链接未通过 HTTPS 安全校验"
                    )
            }
            throw UpdateEndpointRequestException(
                endpointLabel = endpointLabel,
                endpointUrl = endpointUrl,
                stage = "http",
                detail = "HTTP ${response.code()}（响应为空或状态异常）"
            )
        } catch (e: UpdateEndpointRequestException) {
            throw e
        } catch (e: Throwable) {
            throw UpdateEndpointRequestException(
                endpointLabel = endpointLabel,
                endpointUrl = endpointUrl,
                stage = "request",
                detail = e.message ?: "unknown_error",
                cause = e
            )
        }
    }
}

/** 创建 Retrofit API；调用方已在惰性 client 首次访问后才会进入该方法。 */
private fun buildUpdateApi(baseUrl: String, client: OkHttpClient): UpdateApi = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(UpdateApi::class.java)
