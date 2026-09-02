package com.dawncourse.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 启动快照版本必须覆盖所有实际持久化的业务字段，且集合输入顺序不能影响结果。 */
class StartupSnapshotRevisionTest {
    private val firstCourse = StartupSnapshotCourse(
        id = 4L,
        name = "高等数学",
        teacher = "张老师",
        location = "A101",
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 18,
        weekType = StartupSnapshotWeekType.ALL,
        color = "#112233",
    )
    private val secondCourse = firstCourse.copy(id = 2L, name = "大学英语", dayOfWeek = 3)
    private val snapshot = StartupSnapshot(
        protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
        profile = StartupSnapshotProfile(id = 11L, uuid = "profile-uuid"),
        semester = StartupSnapshotSemester(
            id = 7L,
            profileId = 11L,
            name = "2026 秋季",
            startDateEpochMillis = 1_750_000_000_000L,
            weekCount = 20,
        ),
        courses = listOf(firstCourse, secondCourse),
        visualSettings = StartupSnapshotVisualSettings(
            dynamicColor = true,
            wallpaperUri = "content://wallpaper",
            transparency = 0.2f,
            fontStyle = StartupSnapshotFontStyle.SYSTEM,
            dividerType = StartupSnapshotDividerType.SOLID,
            dividerWidthDp = 1f,
            dividerColor = "#E5E7EB",
            dividerAlpha = 0.7f,
            courseItemHeightDp = 64,
            maxDailySections = 12,
            sectionTimes = listOf(StartupSnapshotSectionTime("08:00", "08:45")),
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
        createdAtEpochMillis = 1_750_000_000_000L,
        expiresAtEpochMillis = 1_750_604_800_000L,
        zoneId = "Asia/Shanghai",
        revision = StartupSnapshotRevision("placeholder"),
    )

    @Test
    fun revisionChangesForEveryPersistedSnapshotField() {
        val baseline = StartupSnapshotRevision.create(snapshot)
        val variants = listOf(
            snapshot.copy(protocolVersion = 2),
            snapshot.copy(profile = snapshot.profile.copy(id = 12L)),
            snapshot.copy(profile = snapshot.profile.copy(uuid = "other-profile")),
            snapshot.copy(semester = snapshot.semester?.copy(id = 8L)),
            snapshot.copy(semester = snapshot.semester?.copy(profileId = 12L)),
            snapshot.copy(semester = snapshot.semester?.copy(name = "2027 春季")),
            snapshot.copy(semester = snapshot.semester?.copy(startDateEpochMillis = 1L)),
            snapshot.copy(semester = snapshot.semester?.copy(weekCount = 21)),
            snapshot.copy(courses = listOf(firstCourse.copy(id = 99L), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(name = "线性代数"), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(teacher = "李老师"), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(location = "B202"), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(dayOfWeek = 2), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(startSection = 2), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(duration = 3), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(startWeek = 2), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(endWeek = 17), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(weekType = StartupSnapshotWeekType.ODD), secondCourse)),
            snapshot.copy(courses = listOf(firstCourse.copy(color = "#445566"), secondCourse)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(dynamicColor = false)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(wallpaperUri = null)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(transparency = 0.3f)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(fontStyle = StartupSnapshotFontStyle.SERIF)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(dividerType = StartupSnapshotDividerType.DASHED)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(dividerWidthDp = 2f)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(dividerColor = "#000000")),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(dividerAlpha = 0.6f)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(courseItemHeightDp = 65)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(maxDailySections = 13)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(sectionTimes = listOf(StartupSnapshotSectionTime("09:00", "09:45")))),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(cardCornerRadius = 17)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(cardAlpha = 0.8f)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(showCourseIcons = false)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(wallpaperMode = StartupSnapshotWallpaperMode.FILL)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(themeMode = StartupSnapshotThemeMode.DARK)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(showWeekend = false)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(showSidebarTime = false)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(showSidebarIndex = false)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(hideNonThisWeek = true)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(showDateInHeader = true)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(backgroundBlur = 2f)),
            snapshot.copy(visualSettings = snapshot.visualSettings.copy(backgroundBrightness = 0.8f)),
            snapshot.copy(createdAtEpochMillis = snapshot.createdAtEpochMillis + 1),
            snapshot.copy(expiresAtEpochMillis = snapshot.expiresAtEpochMillis + 1),
            snapshot.copy(zoneId = "UTC"),
        )

        variants.forEach { variant ->
            assertNotEquals(baseline, StartupSnapshotRevision.create(variant))
        }
    }

    @Test
    fun revisionIsStableWhenCourseInputOrderChanges() {
        assertEquals(
            StartupSnapshotRevision.create(snapshot),
            StartupSnapshotRevision.create(snapshot.copy(courses = snapshot.courses.reversed())),
        )
    }
}
