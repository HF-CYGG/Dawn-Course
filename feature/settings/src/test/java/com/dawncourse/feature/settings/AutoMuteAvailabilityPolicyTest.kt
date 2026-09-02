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
    fun `期望开启时区分 DND 可用与震动降级`() {
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_AVAILABLE,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = true, hasPolicyAccess = true),
            ),
        )
        assertEquals(
            AutoMuteAvailability.ENABLED_DND_UNAVAILABLE_VIBRATE_FALLBACK,
            AutoMuteAvailabilityPolicy.resolve(
                desiredEnabled = true,
                capability = AutoMuteDndCapability(isSupported = true, hasPolicyAccess = false),
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
