package com.dawncourse.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 将首次 WorkManager 获取与调度移出 Compose 的 Main.immediate。 */
internal suspend fun runStartupBackgroundWork(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> Unit,
) = withContext(dispatcher) {
    block()
}
