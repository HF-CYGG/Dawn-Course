package com.dawncourse.feature.update

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import retrofit2.Retrofit
import retrofit2.Callback
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Call
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 更新检查 API 接口定义
 * 通过 Retrofit 调用服务端接口获取版本信息
 */
interface UpdateApi {
    /**
     * 获取最新版本信息
     * 请求 version.json 文件
     */
    @Headers(
        "Accept: application/vnd.github.raw+json",
        "X-GitHub-Api-Version: 2022-11-28"
    )
    @GET
    fun getUpdateInfo(@Url endpointUrl: String): Call<UpdateInfo>
}

/**
 * 更新仓库
 * 负责从网络获取最新的应用版本信息，并在结果进入 UI 前校验下载链接。
 *
 * 主要职责：
 * 1. 封装 Retrofit 网络请求
 * 2. 优先请求固定的自建元数据节点，并在失败时顺序降级
 * 3. 统一异常处理，返回 Result 类型
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val packageDownloader: UpdatePackageDownloader
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

    // 基础客户端集中保留连接规格；每个元数据节点在创建 Retrofit 时设置自己的总超时。
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // 增加超时时间，适应弱网环境
        .readTimeout(15, TimeUnit.SECONDS)
        // 默认仍只允许 TLS；创建具体节点客户端时才对白名单自建入口开放 HTTP。
        .connectionSpecs(buildUpdateConnectionSpecs())
        .build()

    /**
     * 创建 Retrofit API 实例
     * @param endpoint 节点地址与总请求超时
     */
    private fun createApi(endpoint: UpdateEndpointConfig): UpdateApi {
        val endpointClient = baseClient.newBuilder()
            .callTimeout(endpoint.requestTimeoutSeconds, TimeUnit.SECONDS)
            .connectionSpecs(buildUpdateMetadataConnectionSpecs(endpoint))
            .build()
        return Retrofit.Builder()
            .baseUrl(endpoint.baseUrl)
            .client(endpointClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(UpdateApi::class.java)
    }

    private val endpointConfigs = buildUpdateEndpointConfigs()

    /**
     * 检查更新
     * 按顺序请求写死的可信元数据入口；请求成功也必须通过下载链接和哈希校验。
     *
     * @return Result<UpdateInfo> 更新信息结果封装
     */
    suspend fun checkUpdate(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val body = resolveUpdateInfoFromEndpoints(endpointConfigs) { endpoint ->
                requestUpdateInfo(
                    api = createApi(endpoint),
                    endpointLabel = endpoint.label,
                    endpointUrl = endpoint.versionInfoUrl
                )
            }
            return@withContext Result.success(body)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: UpdateEndpointsExhaustedException) {
            val exception = UpdateCheckException(
                userMessage = "检查更新失败，请稍后重试",
                debugDetails = failure.failures,
                cause = failure.failures.lastOrNull() ?: failure
            )
            return@withContext Result.failure(exception)
        } catch (failure: Throwable) {
            val endpoint = endpointConfigs.first()
            val endpointFailure = UpdateEndpointRequestException(
                endpointLabel = endpoint.label,
                endpointUrl = endpoint.versionInfoUrl,
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
     * 在应用私有目录下载并验证更新 APK。
     *
     * 下载取消必须继续向上传播，避免用户关闭弹窗后仍在后台写文件。
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Int?) -> Unit
    ): Result<DownloadedUpdatePackage> {
        return try {
            Result.success(packageDownloader.download(updateInfo, onProgress))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    /**
     * 执行一次更新信息请求，并在失败时返回带节点上下文的异常。
     */
    private suspend fun requestUpdateInfo(
        api: UpdateApi,
        endpointLabel: String,
        endpointUrl: String
    ): UpdateInfo = suspendCancellableCoroutine { continuation ->
        val call = try {
            api.getUpdateInfo(endpointUrl)
        } catch (failure: Exception) {
            continuation.resumeWithException(
                UpdateEndpointRequestException(
                    endpointLabel = endpointLabel,
                    endpointUrl = endpointUrl,
                    stage = "request",
                    detail = failure.message ?: "unknown_error",
                    cause = failure
                )
            )
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback<UpdateInfo> {
            override fun onResponse(call: Call<UpdateInfo>, response: Response<UpdateInfo>) {
                if (!continuation.isActive) return
                val body = response.body()
                val actualResponseUrl = response.raw().request.url.toString()
                val result = when {
                    !isExpectedUpdateMetadataResponseUrl(endpointUrl, actualResponseUrl) -> Result.failure(
                        UpdateEndpointRequestException(
                            endpointLabel = endpointLabel,
                            endpointUrl = endpointUrl,
                            stage = "redirect",
                            detail = "更新元数据响应离开了预期来源或协议"
                        )
                    )
                    !response.isSuccessful || body == null -> Result.failure(
                        UpdateEndpointRequestException(
                            endpointLabel = endpointLabel,
                            endpointUrl = endpointUrl,
                            stage = "http",
                            detail = "HTTP ${response.code()}（响应为空或状态异常）"
                        )
                    )
                    else -> validateUpdateInfo(body)?.let(Result.Companion::success) ?: Result.failure(
                        UpdateEndpointRequestException(
                            endpointLabel = endpointLabel,
                            endpointUrl = endpointUrl,
                            stage = "validation",
                            detail = "更新元数据中的下载链接或 SHA-256 未通过安全校验"
                        )
                    )
                }
                result.fold(
                    onSuccess = continuation::resume,
                    onFailure = continuation::resumeWithException
                )
            }

            override fun onFailure(call: Call<UpdateInfo>, failure: Throwable) {
                if (!continuation.isActive) return
                continuation.resumeWithException(
                    UpdateEndpointRequestException(
                        endpointLabel = endpointLabel,
                        endpointUrl = endpointUrl,
                        stage = "request",
                        detail = failure.message ?: "unknown_error",
                        cause = failure
                    )
                )
            }
        })
    }
}
