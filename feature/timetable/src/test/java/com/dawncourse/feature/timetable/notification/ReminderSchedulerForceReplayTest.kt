package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统恢复与普通变化的 force replay 输入契约。 */
class ReminderSchedulerForceReplayTest {

    @Test
    fun `系统事件输入启用 force replay`() {
        assertTrue(
            ReminderScheduler.createImmediateWorkData(forceReplay = true)
                .getBoolean(ReminderScheduler.INPUT_FORCE_REPLAY, false)
        )
    }

    @Test
    fun `普通设置与课程变化不启用 force replay`() {
        assertFalse(
            ReminderScheduler.createImmediateWorkData(forceReplay = false)
                .getBoolean(ReminderScheduler.INPUT_FORCE_REPLAY, true)
        )
    }

    @Test
    fun `每日 WorkManager 保底重放 desired 以修复注册表与系统不一致`() {
        assertTrue(
            ReminderScheduler.createPeriodicWorkData()
                .getBoolean(ReminderScheduler.INPUT_FORCE_REPLAY, false)
        )
    }
}
