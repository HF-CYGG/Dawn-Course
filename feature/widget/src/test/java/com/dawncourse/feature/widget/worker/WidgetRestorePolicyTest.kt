package com.dawncourse.feature.widget.worker

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget 系统事件恢复策略测试。
 */
class WidgetRestorePolicyTest {

    @Test
    fun `存在至少一个 DawnWidget 实例时恢复计划启用全部动作`() {
        val plan = WidgetRestorePolicy.planFor(hasWidget = true)

        assertTrue(plan.schedulePeriodicWork)
        assertTrue(plan.scheduleMidnightAlarm)
        assertTrue(plan.enqueueImmediateUpdate)
    }

    @Test
    fun `不存在 DawnWidget 实例时恢复计划禁用全部动作`() {
        val plan = WidgetRestorePolicy.planFor(hasWidget = false)

        assertEquals(WidgetRestorePlan.NONE, plan)
    }

    @Test
    fun `系统恢复刷新使用固定唯一任务和 replace 策略`() {
        assertEquals("DawnWidgetSystemRestore", WidgetSyncManager.IMMEDIATE_RESTORE_WORK_NAME)
        assertEquals(ExistingWorkPolicy.REPLACE, WidgetSyncManager.IMMEDIATE_RESTORE_WORK_POLICY)
    }
}
