package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.repository.CourseRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** 文件说明：编辑保存只有原子提交成功时才能结束页面。 */
class CourseEditorCompletionPolicyTest {

    @Test
    fun `仅原子保存成功允许关闭编辑页`() {
        assertTrue(shouldCompleteCourseSave(CourseRepository.AtomicSaveResult.Success))
        assertFalse(
            shouldCompleteCourseSave(
                CourseRepository.AtomicSaveResult.Rejected("活动课表已变化"),
            ),
        )
    }

    @Test
    fun `小组件广播异常不阻断已提交课程的成功回调`() = runBlocking {
        var completed = false

        completeSuccessfulCourseSave(
            triggerWidgetUpdate = { throw IllegalStateException("framework") },
            onSaved = { completed = true },
        )

        assertTrue(completed)
    }
}
