package com.dawncourse.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在 ContentProvider 早于 [android.app.Application.onCreate] 调用时，等待完整的应用初始化，
 * 以保护依赖 Hilt [androidx.hilt.work.HiltWorkerFactory] 的 WorkManager 访问。
 *
 * 只允许有限等待：Provider 必须在应用没有完成初始化时抛出明确错误，不能无限阻塞或跳过重置。
 */
internal class DawnAppInitializationGate {
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var failure: Throwable? = null

    fun markReady() {
        readyLatch.countDown()
    }

    fun markFailed(cause: Throwable) {
        failure = cause
        readyLatch.countDown()
    }

    fun await(timeoutMillis: Long): AwaitResult {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return AwaitResult.TimedOut
        }
        return failure?.let(AwaitResult::Failed) ?: AwaitResult.Ready
    }

    sealed interface AwaitResult {
        data object Ready : AwaitResult

        data object TimedOut : AwaitResult

        data class Failed(val cause: Throwable) : AwaitResult
    }
}
