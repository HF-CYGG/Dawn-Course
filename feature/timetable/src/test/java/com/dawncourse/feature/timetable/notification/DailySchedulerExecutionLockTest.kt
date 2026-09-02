package com.dawncourse.feature.timetable.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 每日调度进程级执行锁测试。
 */
class DailySchedulerExecutionLockTest {

    @Test
    fun `不同 WorkManager 唯一任务进入时仍串行执行闹钟重建`() = runBlocking {
        val events = mutableListOf<String>()
        val firstHasLock = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        coroutineScope {
            launch(Dispatchers.Default) {
                DailySchedulerExecutionLock.withLock {
                    events += "first-start"
                    firstHasLock.complete(Unit)
                    releaseFirst.await()
                    events += "first-end"
                }
            }
            firstHasLock.await()
            launch(Dispatchers.Default) {
                DailySchedulerExecutionLock.withLock {
                    events += "second-start"
                    events += "second-end"
                }
            }
            releaseFirst.complete(Unit)
        }

        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            events
        )
    }
}
