package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
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
    fun `Reminder 与 Silence 在任何未就绪状态下都入队持久重试而非直接返回`() {
        listOf("ReminderReceiver.kt", "SilenceReceiver.kt").forEach { fileName ->
            val text = source(fileName)
            assertTrue(
                "$fileName 应依赖 TriggerReadinessRetryScheduler",
                text.contains("triggerReadinessRetryScheduler()")
            )
            assertTrue(
                "$fileName 未就绪分支应入队持久重试",
                Regex("""triggerReadinessRetryScheduler\(\)\s*\.enqueue\(key""")
                    .containsMatchIn(text)
            )
            // 未就绪分支不得再按具体状态收窄入队条件：STARTING 与 RECOVERY_REQUIRED
            // 都会让一次性广播丢事件，都必须入队。
            assertFalse(
                "$fileName 不应只在 STARTING 时入队",
                text.contains("readiness == OperationalDataReadiness.STARTING) {")
            )
        }
    }

    @Test
    fun `重试 Worker 未就绪时不设尝试上限并在就绪后显式补投`() {
        val worker = source("TriggerReadinessRetryWorker.kt")
        assertFalse("不得再有尝试次数上限", worker.contains("MAX_ATTEMPTS"))
        assertTrue(
            "STARTING 与 RECOVERY_REQUIRED 都应交给 WorkManager 退避重试",
            worker.contains("OperationalDataReadiness.STARTING,") &&
                worker.contains("OperationalDataReadiness.RECOVERY_REQUIRED -> Result.retry()")
        )
        assertTrue("就绪后应重新广播补投", worker.contains("sendBroadcast(intent)"))
        assertTrue(
            "补投 Intent 必须显式指向 Receiver",
            worker.contains("Intent(applicationContext, receiver)")
        )
        // 精度必须随任务持久保存并随补投广播下传：就绪后启动对账可能已清掉注册表记录，
        // 届时只能靠它判定非精确迟到宽限。
        assertTrue(
            "补投任务应持久保存原始闹钟精度",
            worker.contains("INPUT_PRECISION") && worker.contains("decodePrecision")
        )
        assertTrue(
            "补投广播应携带原始精度 extra",
            worker.contains("ReminderReceiver.EXTRA_TRIGGER_PRECISION")
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

    @Test
    fun `课程边界 Receiver 必须先持久化刷新责任再等待 WorkManager`() {
        val receiver = source("PersistentNotificationRefreshReceiver.kt")
        assertTrue(
            receiver.contains("triggerCourseSurfaceRefreshWorkAndAwait")
        )
        assertFalse(
            "课程边界不能绕过独立刷新 journal 直接入队",
            receiver.contains("triggerImmediateWorkAndAwait")
        )
    }
}
