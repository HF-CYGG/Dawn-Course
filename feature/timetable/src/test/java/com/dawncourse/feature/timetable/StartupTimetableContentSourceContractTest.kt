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
}
