package com.dawncourse.core.domain.util

import kotlinx.coroutines.CancellationException

/**
 * 将可恢复的挂起操作异常显式交给调用者映射为状态。
 *
 * 协程取消必须保持结构化并发语义，致命 [Error] 也不能伪装成可恢复失败，
 * 因此只捕获普通 [Exception]。
 */
suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    Result.failure(failure)
}
