package com.dawncourse.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页不得再把学期元数据写回 AppSettings。 */
class SemesterSettingsSourceContractTest {
    private val viewModel = File(
        "src/main/java/com/dawncourse/feature/settings/SettingsViewModel.kt"
    ).readText()

    @Test
    fun viewModelExposesRoomCurrentSemesterAndHasNoLegacySetters() {
        assertTrue(viewModel.contains("val currentSemester: StateFlow<Semester?>"))
        assertFalse(viewModel.contains("fun setCurrentSemesterName"))
        assertFalse(viewModel.contains("fun setTotalWeeks"))
        assertFalse(viewModel.contains("fun setStartDateTimestamp"))
        assertFalse(viewModel.contains("settingsRepository.setCurrentSemesterName"))
        assertFalse(viewModel.contains("settingsRepository.setTotalWeeks"))
        assertFalse(viewModel.contains("settingsRepository.setStartDateTimestamp"))
    }
}
