package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.Course
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证性能测试仅在真实课表内容已经由 UiState 渲染后继续。 */
class TimetableBenchmarkContractTest {

    @Test
    fun `加载中不能声明课表已就绪`() {
        assertFalse(TimetableBenchmarkContract.isContentReady(TimetableUiState.Loading))
    }

    @Test
    fun `成功状态可以声明课表已就绪`() {
        val success = TimetableUiState.Success(
            courses = listOf(
                Course(
                    name = "性能测试课程",
                    dayOfWeek = 1,
                    startSection = 1,
                    duration = 2,
                    startWeek = 1,
                    endWeek = 20
                )
            ),
            currentWeek = 1,
            semesterStartDate = LocalDate.of(2026, 8, 24)
        )

        assertTrue(TimetableBenchmarkContract.isContentReady(success))
    }
}
