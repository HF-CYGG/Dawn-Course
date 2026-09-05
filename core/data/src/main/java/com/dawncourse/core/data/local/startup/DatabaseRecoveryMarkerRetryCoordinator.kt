package com.dawncourse.core.data.local.startup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recovery UI 的 reason-aware marker 重试，不允许把完整性原因写成备份补偿原因。 */
internal class DatabaseRecoveryMarkerRetryCoordinator(
    private val persistBackupMarker: suspend () -> Unit,
    private val persistIntegrityMarker: suspend () -> Unit,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 仅支持具有在线 marker 协议的两个原因；持久化统一离开 Main，失败保持重试模式。 */
    suspend fun retry(reason: DatabaseRecoveryReason): Boolean = withContext(persistenceDispatcher) {
        runCatching {
            when (reason) {
                DatabaseRecoveryReason.RestoreFailed -> persistBackupMarker()
                DatabaseRecoveryReason.IntegrityVerificationFailed -> persistIntegrityMarker()
                else -> return@withContext false
            }
        }.isSuccess
    }
}
