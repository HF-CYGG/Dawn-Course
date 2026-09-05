package com.dawncourse.app

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动调度不会在 Compose 的 Main.immediate 上首次触发 WorkManager。 */
class StartupBackgroundWorkTest {
    @Test
    fun startupSchedulingRunsOnTheProvidedBackgroundDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "startup-scheduling-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        var executingThreadName: String? = null

        try {
            runStartupBackgroundWork(dispatcher) {
                executingThreadName = Thread.currentThread().name
            }

            assertTrue(executingThreadName?.contains("startup-scheduling-test") == true)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
