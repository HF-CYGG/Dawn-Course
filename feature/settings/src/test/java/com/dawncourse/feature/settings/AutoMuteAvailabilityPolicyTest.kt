package com.dawncourse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMuteAvailabilityPolicyTest {

    @Test
    fun `偏好关闭时权限不改变关闭状态`() {
        assertEquals(
            AutoMuteAvailability.DISABLED,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = false,
                capability = AutoMuteDndCapability(isSupported = true, hasPolicyAccess = true),
            ),
        )
    }

    @Test
    fun `期望开启且权限齐备时报告 DND 可用`() {
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_AVAILABLE,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = true, hasPolicyAccess = true),
            ),
        )
    }

    @Test
    fun `震动降级必须区分平台不支持与权限未授予`() {
        // 平台支持但缺权：用户可以自己修复，文案与入口都应指向授权。
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_PERMISSION_REQUIRED_VIBRATE,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = true, hasPolicyAccess = false),
            ),
        )
        // 平台不支持：这是永久事实，不能与"当前不可用"共用同一状态。
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_UNSUPPORTED_VIBRATE,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = false, hasPolicyAccess = false),
            ),
        )
        // 平台不支持时即使 hasPolicyAccess 为真也不得报告 DND 可用。
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_UNSUPPORTED_VIBRATE,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = false, hasPolicyAccess = true),
            ),
        )
    }

    @Test
    fun `仅 Android 15 以上缺权时引导申请 DND`() {
        assertTrue(
            AutoMuteDndCapability(isSupported = true, hasPolicyAccess = false)
                .shouldRequestPolicyAccess,
        )
        assertFalse(
            AutoMuteDndCapability(isSupported = false, hasPolicyAccess = false)
                .shouldRequestPolicyAccess,
        )
        assertFalse(
            AutoMuteDndCapability(isSupported = true, hasPolicyAccess = true)
                .shouldRequestPolicyAccess,
        )
    }
}
