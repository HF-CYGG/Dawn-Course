package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 专用恢复链路不得退化为 DailySchedulerWorker force replay。 */
class MuteRecoveryWorkerContractTest {
    @Test
    fun `恢复 Worker 使用 Hilt 且直接调用 SilenceHelper`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/notification/MuteRecoveryWorker.kt"
        ).readText()

        assertTrue(source.contains("@HiltWorker"))
        assertTrue(source.contains("silenceHelper.unmuteOwnedSession"))
        assertTrue(source.contains("Result.retry()"))
        assertFalse(source.contains("DailySchedulerWorker"))
        assertFalse(source.contains("INPUT_FORCE_REPLAY"))
        assertFalse(source.contains("ScheduledTriggerRegistry"))
    }
}
