package com.dawncourse.feature.timetable

import org.junit.Assert.assertSame
import org.junit.Test

/** 文件说明：锁定课表根 Flow 失败时的显式错误状态。 */
class TimetableFailurePolicyTest {

    @Test
    fun `根 Flow 失败不会伪装为空课表成功`() {
        assertSame(TimetableUiState.Error, timetableFlowFailureState())
    }
}
