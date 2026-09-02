package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.startup.BackupRecoveryActivation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 将“预置恢复 marker -> 破坏性替换 -> 验证清除 marker”的顺序从 Android/Room 细节中抽离。
 *
 * 一旦 marker 无法预置，绝不调用 [runRestore]；一旦 marker 无法确认清除，则保留恢复状态，
 * 由调用方在 [onMarkerStillRequired] 中先关闭进程级写入门、再发布重启状态。
 */
internal object BackupRestoreSafetyProtocol {
    suspend fun execute(
        prepareMarker: suspend () -> Unit,
        runRestore: suspend () -> Result<Unit>,
        clearMarkerAndVerify: suspend () -> Unit,
        onMarkerUnavailable: suspend (Throwable) -> Unit,
        onMarkerStillRequired: suspend () -> BackupRecoveryActivation,
    ): Result<Unit> {
        try {
            prepareMarker()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { onMarkerUnavailable(failure) }
            return Result.failure(BackupRecoveryMarkerNotPreparedException(failure))
        }

        val result = try {
            runRestore()
        } catch (failure: Throwable) {
            val activation = withContext(NonCancellable) { onMarkerStillRequired() }
            return Result.failure(
                BackupRecoveryRequiredException(
                    cause = failure,
                    compensationFailures = emptyList(),
                    recoveryActivation = activation,
                ),
            )
        }

        // CompensatingBackupRestore 已在补偿失败分支中要求调用方进入恢复状态并保留 marker。
        if (result.exceptionOrNull() is BackupRecoveryRequiredException) return result

        return try {
            clearMarkerAndVerify()
            result
        } catch (failure: Throwable) {
            val activation = withContext(NonCancellable) { onMarkerStillRequired() }
            Result.failure(
                BackupRecoveryRequiredException(
                    cause = failure,
                    compensationFailures = emptyList(),
                    recoveryActivation = activation,
                ),
            )
        }
    }
}

/** marker 未能确认写入时的 fail-closed 失败，调用方不得开始 Room 替换。 */
internal class BackupRecoveryMarkerNotPreparedException(cause: Throwable) :
    IllegalStateException("恢复保护标记未能写入，未开始替换课程数据", cause)
