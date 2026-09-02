package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worker 不得回退到 Long requestCode 全删全建的静态契约。 */
class DailySchedulerTriggerReconcilerContractTest {

    @Test
    fun `Worker 仅通过 TriggerReconciler 下发课程触发器`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/notification/DailySchedulerWorker.kt"
        ).readText()

        assertTrue(source.contains("private val triggerReconciler: TriggerReconciler"))
        assertTrue(source.contains("private val generateTriggerHorizonUseCase"))
        assertTrue(source.contains("profileId = snapshot.profileId"))
        assertTrue(source.contains("getActiveContext()"))
        assertTrue(source.contains("const val TRIGGER_HORIZON_DAYS = 2"))
        assertTrue(source.contains("record.status == MuteSessionStatus.ACTIVE"))
        assertTrue(source.contains("retainedUnmuteAlarmKeys"))
        assertTrue(source.contains("muteRecoveryController.reconcilePersistedState()"))
        assertFalse(source.contains("triggerMuteRecoveryWork"))
        assertTrue(source.contains("ReminderScheduler.INPUT_FORCE_REPLAY"))
        assertFalse(source.contains("PersistentNotificationRefreshScheduler.cancel(applicationContext)\n        if"))
        assertFalse(source.contains("course.id.toInt()"))
        assertFalse(source.contains("+ 10000"))
        assertFalse(source.contains("+ 20000"))
        assertFalse(source.contains("cancelExistingAlarms"))
        assertFalse(source.contains("setExactAlarm"))
    }
}
