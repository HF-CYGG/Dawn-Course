package com.dawncourse.feature.timetable

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimetableContentSourceContractTest {

    @Test
    fun `snapshot content has no live navigation or view model entry points`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/StartupTimetableContent.kt"
        ).readText()

        listOf(
            "hiltViewModel",
            "ViewModelProvider",
            "NavHost",
            "TimetableRoute",
            "ReportDrawnWhen",
        ).forEach { forbidden ->
            assertFalse("snapshot content must not reference $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("interactive = false"))
    }

    @Test
    fun `snapshot content reuses the live timetable background and contrast callback`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/StartupTimetableContent.kt"
        ).readText()

        assertTrue(source.contains("TimetableBackground("))
        listOf(
            "wallpaperUri = presentation.settings.wallpaperUri",
            "wallpaperMode = presentation.settings.wallpaperMode",
            "backgroundBlur = presentation.settings.backgroundBlur",
            "backgroundBrightness = presentation.settings.backgroundBrightness",
            "transparency = presentation.settings.transparency",
            "onWallpaperLightChanged",
        ).forEach { required ->
            assertTrue("snapshot background must consume $required", source.contains(required))
        }
    }

    @Test
    fun `snapshot content has an explicit no semester presentation instead of a pre term holiday`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/StartupTimetableContent.kt"
        ).readText()

        assertTrue(source.contains("StartupTimetablePresentationMode.NoSemester"))
        assertTrue(source.contains("StartupNoSemesterView("))
    }
}
