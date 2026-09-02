package com.dawncourse.feature.timetable.notification

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 立即调度的唯一任务策略测试。
 */
class ReminderSchedulerImmediateWorkTest {

    @Test
    fun `立即 reconcile 使用固定唯一任务名与 append or replace 策略`() {
        assertEquals("DailyReminderImmediateReconcile", ReminderScheduler.IMMEDIATE_WORK_NAME)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, ReminderScheduler.IMMEDIATE_WORK_POLICY)
    }
}
