package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.repository.CourseRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调课提交只在事务真正成功后才允许关闭当前编辑面板。 */
class CourseRescheduleCompletionPolicyTest {

    @Test
    fun `保存成功才允许关闭调课面板`() {
        assertTrue(shouldCloseReschedulePanel(CourseRepository.AtomicSaveResult.Success))
    }

    @Test
    fun `范围拒绝时必须保留调课面板`() {
        assertFalse(
            shouldCloseReschedulePanel(
                CourseRepository.AtomicSaveResult.Rejected("活动课表已变化，请刷新后重试"),
            )
        )
    }

    @Test
    fun `课程冲突数据未加载时禁止提交`() {
        assertFalse(canConfirmReschedule(RescheduleUiState(conflictDataReady = false)))
        assertTrue(canConfirmReschedule(RescheduleUiState(conflictDataReady = true)))
    }
}
