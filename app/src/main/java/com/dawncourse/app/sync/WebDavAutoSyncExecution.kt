package com.dawncourse.app.sync

import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import kotlinx.coroutines.CancellationException

/** WebDAV 自动同步 Worker 可返回的最小外层结果。 */
internal enum class WebDavAutoSyncOutcome {
    /** 正常结束且无需系统重试。 */
    SUCCESS,

    /** 临时失败，交给 WorkManager 重试。 */
    RETRY,
}

/** WebDAV 自动同步的纯 Kotlin 外层执行边界。 */
internal object WebDavAutoSyncExecution {
    /**
     * 覆盖 readiness、设置读取和上传三个阶段。
     *
     * @param readiness 获取数据库可操作状态。
     * @param isEnabled 读取当前自动同步开关。
     * @param upload 执行一次非强制上传。
     */
    suspend fun execute(
        readiness: suspend () -> OperationalDataReadiness,
        isEnabled: suspend () -> Boolean,
        upload: suspend () -> WebDavSyncResult,
    ): WebDavAutoSyncOutcome = try {
        when (readiness()) {
            OperationalDataReadiness.STARTING -> WebDavAutoSyncOutcome.RETRY
            OperationalDataReadiness.RECOVERY_REQUIRED -> WebDavAutoSyncOutcome.SUCCESS
            OperationalDataReadiness.READY -> {
                if (!isEnabled()) {
                    WebDavAutoSyncOutcome.SUCCESS
                } else {
                    val result = upload()
                    when {
                        result.success -> WebDavAutoSyncOutcome.SUCCESS
                        result.code == SyncErrorCode.NO_CREDENTIALS -> WebDavAutoSyncOutcome.SUCCESS
                        result.code == SyncErrorCode.AUTH_FAILED -> WebDavAutoSyncOutcome.SUCCESS
                        else -> WebDavAutoSyncOutcome.RETRY
                    }
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        WebDavAutoSyncOutcome.RETRY
    }
}
