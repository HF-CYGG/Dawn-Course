package com.dawncourse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课程状态通知可用性决策测试。
 */
class CourseStatusNotificationAvailabilityPolicyTest {
    @Test
    fun `应用通知总开关关闭时优先引导应用通知设置`() {
        assertEquals(
            CourseStatusNotificationAvailability.APP_NOTIFICATIONS_DISABLED,
            CourseStatusNotificationAvailabilityPolicy.resolve(
                appNotificationsEnabled = false,
                channelExists = true,
                channelEnabled = false
            )
        )
    }

    @Test
    fun `已有状态渠道被关闭时引导渠道设置`() {
        assertEquals(
            CourseStatusNotificationAvailability.CHANNEL_DISABLED,
            CourseStatusNotificationAvailabilityPolicy.resolve(
                appNotificationsEnabled = true,
                channelExists = true,
                channelEnabled = false
            )
        )
    }

    @Test
    fun `渠道尚未创建或已启用时允许打开功能`() {
        assertEquals(
            CourseStatusNotificationAvailability.AVAILABLE,
            CourseStatusNotificationAvailabilityPolicy.resolve(
                appNotificationsEnabled = true,
                channelExists = false,
                channelEnabled = false
            )
        )
        assertEquals(
            CourseStatusNotificationAvailability.AVAILABLE,
            CourseStatusNotificationAvailabilityPolicy.resolve(
                appNotificationsEnabled = true,
                channelExists = true,
                channelEnabled = true
            )
        )
    }
}
