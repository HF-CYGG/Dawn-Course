package com.dawncourse.feature.import_module

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入必须将捕获的 Profile 目标交给 data 层事务，禁止继续清空全库。 */
class ImportSemesterSelectionContractTest {
    private val importViewModelSource = File(
        "src/main/java/com/dawncourse/feature/import_module/ImportViewModel.kt"
    ).readText()

    @Test
    fun importCommitsCapturedDestinationWithoutGlobalDeleteOrLegacySettingsMirrors() {
        assertTrue(importViewModelSource.contains("importCommitRepository.commit(request)"))
        assertTrue(importViewModelSource.contains("fun beginImport(targetProfileId: Long? = null)"))
        assertTrue(importViewModelSource.contains("val destination = state.destination"))
        assertFalse(importViewModelSource.contains("courseRepository.deleteAllCourses()"))
        assertFalse(importViewModelSource.contains("semesterRepository.deleteAllSemesters()"))
        assertFalse(importViewModelSource.contains("settingsRepository.setCurrentSemesterName"))
        assertFalse(importViewModelSource.contains("settingsRepository.setStartDateTimestamp"))
        assertFalse(importViewModelSource.contains("settingsRepository.setTotalWeeks"))
    }

    @Test
    fun qidiNeverFallsBackToFirstSemester() {
        val source = File(
            "src/main/java/com/dawncourse/feature/import_module/QidiAutoSyncScreen.kt"
        ).readText()

        assertFalse(source.contains("allSemesters.first()"))
        assertFalse(source.contains("current?.id ?: semesterRepository.getAllSemesters()"))
        assertFalse(source.contains("自动兜底"))
    }
}
