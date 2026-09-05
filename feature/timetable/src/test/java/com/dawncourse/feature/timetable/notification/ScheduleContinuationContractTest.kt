package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleContinuationContractTest {
    @Test
    fun `课程触发后统一进入唯一续排任务`() {
        listOf("ReminderReceiver.kt", "SilenceReceiver.kt").forEach { fileName ->
            val source = File(
                "src/main/java/com/dawncourse/feature/timetable/notification/$fileName",
            ).readText()
            assertTrue(source.contains("ReminderScheduler.triggerContinuationWorkAndAwait(context)"))
            assertTrue(source.contains("withContext(NonCancellable)"))
            assertTrue(
                source.indexOf("pendingResult.finish()") >
                    source.indexOf("ReminderScheduler.triggerContinuationWorkAndAwait(context)"),
            )
        }
    }

    @Test
    fun `Receiver 在结束 goAsync 前等待续排任务持久化`() {
        val scheduler = File(
            "src/main/java/com/dawncourse/feature/timetable/notification/ReminderScheduler.kt",
        ).readText()

        assertTrue(scheduler.contains("suspend fun triggerContinuationWorkAndAwait"))
        assertTrue(scheduler.contains("awaitWorkManagerOperation(enqueueContinuationWork(context))"))
    }

    @Test
    fun `每日 Worker 使用七天窗口并执行冷启动补静音策略`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/notification/DailySchedulerWorker.kt",
        ).readText()

        assertTrue(source.contains("ScheduleHorizonPolicy.DAY_COUNT"))
        assertTrue(source.contains("MissedMuteCatchUpPolicy.find"))
        assertTrue(source.contains("deliverMissedMute"))
    }
}
