package com.dawncourse.feature.timetable.notification

import kotlinx.coroutines.CancellationException

/** BroadcastReceiver 的异步任务生命周期边界。 */
object ReceiverTaskRunner {
    /**
     * 执行广播异步任务。
     *
     * @param task 接收器交出的后台任务。
     * @param onFailureType 只接收脱敏异常类型的记录回调。
     * @param finish 释放 BroadcastReceiver PendingResult 的回调。
     */
    suspend fun run(
        task: suspend () -> Unit,
        onFailureType: (String) -> Unit,
        finish: () -> Unit,
    ) {
        try {
            task()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            onFailureType(failure.javaClass.simpleName)
        } finally {
            finish()
        }
    }
}
