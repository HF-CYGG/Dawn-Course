package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性触发广播在启动窗口内没等到数据库就绪时，不得静默丢弃：
 * 必须把完整 TriggerKey 交给可重试的持久任务补投。
 */
class ReceiverReadinessRetryContractTest {
    private fun source(fileName: String): String =
        File("src/main/java/com/dawncourse/feature/timetable/notification/$fileName").readText()

    @Test
    fun `Reminder 与 Silence 在未就绪时改走持久重试而非直接返回`() {
        listOf("ReminderReceiver.kt", "SilenceReceiver.kt").forEach { fileName ->
            val text = source(fileName)
            assertTrue(
                "$fileName 应依赖 TriggerReadinessRetryScheduler",
                text.contains("triggerReadinessRetryScheduler()")
            )
            assertTrue(
                "$fileName 应在 STARTING 时入队持久重试",
                text.contains("OperationalDataReadiness.STARTING") &&
                    text.contains("triggerReadinessRetryScheduler().enqueue(key)")
            )
        }
    }

    @Test
    fun `重试 Worker 对无上限启动路径设有尝试上限并在就绪后补投`() {
        val worker = source("TriggerReadinessRetryWorker.kt")
        assertTrue("应有尝试次数上限", worker.contains("MAX_ATTEMPTS"))
        assertTrue(
            "达到上限后应收敛为 success 而非无限重试",
            worker.contains("runAttemptCount + 1 >= MAX_ATTEMPTS")
        )
        assertTrue("就绪后应重新广播补投", worker.contains("sendBroadcast(intent)"))
        assertTrue(
            "补投 Intent 必须显式指向 Receiver",
            worker.contains("Intent(applicationContext, receiver)")
        )
        assertTrue(
            "UNMUTE 不进入本 Worker",
            worker.contains("TriggerKind.UNMUTE -> return")
        )
        assertTrue("Worker 与调度器应完成 Hilt 绑定", worker.contains("@HiltWorker"))
    }

    @Test
    fun `调度器已在 Hilt 模块绑定`() {
        val module = source("TriggerSchedulingModule.kt")
        assertTrue(
            module.contains("WorkManagerTriggerReadinessRetryScheduler") &&
                module.contains(": TriggerReadinessRetryScheduler")
        )
    }
}
