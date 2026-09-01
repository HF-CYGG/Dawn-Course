package com.dawncourse.feature.timetable

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表与编辑界面的学期元数据必须来自 Room 驱动的 ViewModel 状态。
 */
class SemesterMetadataSourceContractTest {
    private val timetableScreen = source("TimetableScreen.kt")
    private val editorViewModel = source("CourseEditorViewModel.kt")
    private val editorScreen = source("CourseEditorScreen.kt")
    private val rescheduleViewModel = source("CourseRescheduleViewModel.kt")
    private val rescheduleSheet = source("CourseRescheduleSheet.kt")

    @Test
    fun timetableUsesUiStateTotalWeeks() {
        assertTrue(timetableScreen.contains("uiState.totalWeeks"))
        assertFalse(timetableScreen.contains("settings.totalWeeks"))
    }

    @Test
    fun courseEditorUsesCurrentSemesterWeekCountFromViewModelContract() {
        assertTrue(editorViewModel.contains("currentSemesterWeekCount"))
        assertTrue(editorScreen.contains("currentSemesterWeekCount: Int"))
        assertFalse(editorScreen.contains("LocalAppSettings.current.totalWeeks"))
    }

    @Test
    fun rescheduleUsesSemesterWeekCountFromUiState() {
        assertTrue(rescheduleViewModel.contains("semesterWeekCount"))
        assertTrue(rescheduleSheet.contains("uiState.semesterWeekCount"))
        assertFalse(rescheduleSheet.contains("LocalAppSettings.current.totalWeeks"))
    }

    @Test
    fun editorShowsNoSemesterStateAndDisablesSave() {
        assertTrue(editorScreen.contains("hasValidSemester"))
        assertTrue(editorScreen.contains("请先在设置中选择当前学期"))
        assertTrue(editorScreen.contains("enabled = name.isNotBlank() && hasValidSemester"))
    }

    @Test
    fun viewModelRevalidatesSemesterBeforeCourseRepositoryWrite() {
        assertTrue(editorViewModel.contains("repository.saveCoursesIfScopeActive"))
        assertFalse(editorViewModel.contains("repository.deleteCourseById(editingId)"))
        assertFalse(editorViewModel.contains("repository.insertCourses(insertList)"))
    }

    private fun source(name: String): String = File("src/main/java/com/dawncourse/feature/timetable/$name").readText()
}
