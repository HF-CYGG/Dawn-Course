package com.dawncourse.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleReliabilityAvailabilityPolicyTest {

    @Test
    fun `Android 12 以下无需引导精确闹钟授权`() {
        val status = ScheduleReliabilityAvailability(
            exactAlarmAvailable = true,
            exactAlarmAccessRequestSupported = false,
            ignoringBatteryOptimizations = false,
        )

        assertFalse(status.shouldRequestExactAlarmAccess)
        assertTrue(status.batteryOptimizationMayDelay)
    }

    @Test
    fun `Android 12 以上仅在缺少能力时引导授权`() {
        assertTrue(
            ScheduleReliabilityAvailability(
                exactAlarmAvailable = false,
                exactAlarmAccessRequestSupported = true,
                ignoringBatteryOptimizations = true,
            ).shouldRequestExactAlarmAccess,
        )
        assertFalse(
            ScheduleReliabilityAvailability(
                exactAlarmAvailable = true,
                exactAlarmAccessRequestSupported = true,
                ignoringBatteryOptimizations = true,
            ).shouldRequestExactAlarmAccess,
        )
    }
}
