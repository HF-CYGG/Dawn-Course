package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用所有的静音会话恢复策略测试。 */
class MuteSessionPolicyTest {

    @Test
    fun `用户原本已静音且无应用会话时不得建立恢复责任`() {
        assertFalse(
            MuteSessionPolicy.shouldOwnMuteSession(
                isRingerNormal = false,
                hasActiveOwnedSession = false
            )
        )
    }

    @Test
    fun `应用首次从响铃切换或重叠课程时持有恢复责任`() {
        assertTrue(MuteSessionPolicy.shouldOwnMuteSession(true, false))
        assertTrue(MuteSessionPolicy.shouldOwnMuteSession(false, true))
    }

    @Test
    fun `旧耗尽责任仍维持震动时新课程继续持有责任`() {
        assertTrue(
            MuteSessionPolicy.shouldOwnMuteSession(
                isRingerNormal = false,
                hasActiveOwnedSession = false,
                isRingerVibrate = true,
                hasExhaustedResponsibility = true
            )
        )
    }

    @Test
    fun `只有消费到最后一个应用会话且仍为震动时恢复响铃`() {
        assertTrue(MuteSessionPolicy.shouldRestoreRinger(consumed = true, remainingCount = 0, isVibrate = true))
        assertFalse(MuteSessionPolicy.shouldRestoreRinger(consumed = false, remainingCount = 0, isVibrate = true))
        assertFalse(MuteSessionPolicy.shouldRestoreRinger(consumed = true, remainingCount = 1, isVibrate = true))
        assertFalse(MuteSessionPolicy.shouldRestoreRinger(consumed = true, remainingCount = 0, isVibrate = false))
    }
}
