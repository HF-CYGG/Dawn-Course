package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 ID 空间的纯 JVM 回归测试。
 */
class NotificationHelperIdTest {
    @Test
    fun `课程状态通知使用固定保留ID`() {
        assertEquals(999, NotificationHelper.COURSE_STATUS_NOTIFICATION_ID)
    }

    @Test
    fun `提醒通知ID覆盖Long最小值且不会撞状态通知`() {
        val seeds = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)

        val ids = seeds.map(NotificationHelper::generateStableNotificationId)

        assertTrue(ids.all { it >= NotificationHelper.NOTIFICATION_ID_BASE })
        assertTrue(ids.none { it == NotificationHelper.COURSE_STATUS_NOTIFICATION_ID })
        assertTrue(ids.none { it == 1999 })
        assertEquals(2000, NotificationHelper.NOTIFICATION_ID_BASE)
    }
}
