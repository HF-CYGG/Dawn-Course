package com.dawncourse.feature.timetable.notification

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 广播异步任务边界的真实行为测试。 */
class ReceiverTaskRunnerTest {

    @Test
    fun `任务成功时 finish 恰好一次且不记录失败`() = runBlocking {
        val events = mutableListOf<String>()

        ReceiverTaskRunner.run(
            task = { events += "task" },
            onFailureType = { type -> events += "failure:$type" },
            finish = { events += "finish" },
        )

        assertEquals(listOf("task", "finish"), events)
    }

    @Test
    fun `普通异常被封口且只记录异常类型并 finish 恰好一次`() = runBlocking {
        val events = mutableListOf<String>()

        ReceiverTaskRunner.run(
            task = {
                events += "task"
                throw IllegalStateException("sensitive payload")
            },
            onFailureType = { type -> events += "failure:$type" },
            finish = { events += "finish" },
        )

        assertEquals(listOf("task", "failure:IllegalStateException", "finish"), events)
    }

    @Test
    fun `取消异常向上传播且 finish 恰好一次`() {
        val events = mutableListOf<String>()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                ReceiverTaskRunner.run(
                    task = {
                        events += "task"
                        throw CancellationException("cancelled")
                    },
                    onFailureType = { type -> events += "failure:$type" },
                    finish = { events += "finish" },
                )
            }
        }

        assertEquals(listOf("task", "finish"), events)
    }
}
