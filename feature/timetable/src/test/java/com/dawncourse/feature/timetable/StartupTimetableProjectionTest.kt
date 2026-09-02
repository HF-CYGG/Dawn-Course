package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotCourse
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotProfile
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.StartupSnapshotSemester
import com.dawncourse.core.domain.model.StartupSnapshotThemeMode
import com.dawncourse.core.domain.model.StartupSnapshotVisualSettings
import com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode
import com.dawncourse.core.domain.model.StartupSnapshotWeekType
import com.dawncourse.core.domain.model.Course
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTimetableProjectionTest {

    @Test
    fun `projects snapshot courses settings and current week using snapshot zone`() {
        val snapshot = snapshot(
            startDate = LocalDate.of(2026, 9, 7)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli(),
        )

        val presentation = snapshot.toStartupTimetablePresentation(LocalDate.of(2026, 9, 15))

        assertEquals(2, presentation.currentWeek)
        assertEquals(16, presentation.totalWeeks)
        assertEquals("线性代数", presentation.courses.single().name)
        assertEquals("#123456", presentation.settings.dividerColor)
        assertEquals(16, presentation.settings.cardCornerRadius)
        assertEquals(64, presentation.settings.courseItemHeightDp)
        assertEquals(Course.WEEK_TYPE_ODD, presentation.courses.single().weekType)
    }

    @Test
    fun `returns pre term state before snapshot semester begins`() {
        val snapshot = snapshot(
            startDate = LocalDate.of(2026, 9, 7)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli(),
        )

        val presentation = snapshot.toStartupTimetablePresentation(LocalDate.of(2026, 9, 6))

        assertEquals(0, presentation.currentWeek)
    }

    private fun snapshot(startDate: Long): StartupSnapshot = StartupSnapshot(
        protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
        profile = StartupSnapshotProfile(id = 7L, uuid = "profile-7"),
        semester = StartupSnapshotSemester(
            id = 13L,
            profileId = 7L,
            name = "2026 秋",
            startDateEpochMillis = startDate,
            weekCount = 16,
        ),
        courses = listOf(
            StartupSnapshotCourse(
                id = 42L,
                name = "线性代数",
                teacher = "王老师",
                location = "A101",
                dayOfWeek = 2,
                startSection = 3,
                duration = 2,
                startWeek = 1,
                endWeek = 16,
                weekType = StartupSnapshotWeekType.ODD,
                color = "#8ab4f8",
            ),
        ),
        visualSettings = StartupSnapshotVisualSettings(
            dynamicColor = false,
            wallpaperUri = null,
            transparency = 0.1f,
            fontStyle = StartupSnapshotFontStyle.MONOSPACE,
            dividerType = StartupSnapshotDividerType.DASHED,
            dividerWidthDp = 1.5f,
            dividerColor = "#123456",
            dividerAlpha = 0.8f,
            courseItemHeightDp = 64,
            maxDailySections = 12,
            sectionTimes = emptyList(),
            cardCornerRadius = 16,
            cardAlpha = 0.9f,
            showCourseIcons = true,
            wallpaperMode = StartupSnapshotWallpaperMode.CROP,
            themeMode = StartupSnapshotThemeMode.SYSTEM,
            showWeekend = true,
            showSidebarTime = true,
            showSidebarIndex = true,
            hideNonThisWeek = false,
            showDateInHeader = false,
            backgroundBlur = 0f,
            backgroundBrightness = 1f,
        ),
        createdAtEpochMillis = 0L,
        expiresAtEpochMillis = StartupSnapshot.TTL_MILLIS,
        zoneId = "Asia/Shanghai",
        revision = StartupSnapshotRevision("test"),
    )
}
