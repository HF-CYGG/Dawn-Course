package com.dawncourse.app.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统课表恢复事件策略测试。
 *
 * 仅允许会使系统闹钟或本地时间基准失效的系统事件进入恢复链路，
 * 其余广播必须保持无副作用。
 */
class SystemScheduleEventPolicyTest {

    @Test
    fun `开机与时间基准变化事件需要恢复调度`() {
        val recoveryActions = listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED"
        )

        recoveryActions.forEach { action ->
            assertTrue("$action 必须进入恢复链路", SystemScheduleEventPolicy.shouldRestore(action))
        }
    }

    @Test
    fun `无关或空 action 不触发恢复`() {
        assertFalse(SystemScheduleEventPolicy.shouldRestore(null))
        assertFalse(SystemScheduleEventPolicy.shouldRestore("android.intent.action.DATE_CHANGED"))
        assertFalse(SystemScheduleEventPolicy.shouldRestore("com.dawncourse.widget.FORCE_UPDATE"))
    }
}
