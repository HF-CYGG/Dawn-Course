package com.dawncourse.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 启动读取的时钟、时区和活动 Profile 校验必须失败关闭。 */
class StartupSnapshotValidityTest {
    @Test
    fun missingExpiredFutureZoneAndProfileMismatchAreCacheMissReasons() {
        val now = 1_750_000_000_000L
        val valid = signedSnapshot(createdAt = now - 1, expiresAt = now + 1)

        assertEquals(
            StartupSnapshotValidity.PROFILE_MISMATCH,
            valid.validateForStartup(expectedProfileId = null, nowEpochMillis = now, expectedZoneId = "Asia/Shanghai"),
        )
        assertEquals(
            StartupSnapshotValidity.PROFILE_MISMATCH,
            valid.validateForStartup(expectedProfileId = 8L, nowEpochMillis = now, expectedZoneId = "Asia/Shanghai"),
        )
        assertEquals(
            StartupSnapshotValidity.EXPIRED,
            signedSnapshot(createdAt = now - 2, expiresAt = now).validateForStartup(7L, now, "Asia/Shanghai"),
        )
        assertEquals(
            StartupSnapshotValidity.FUTURE,
            signedSnapshot(createdAt = now + 1, expiresAt = now + 2).validateForStartup(7L, now, "Asia/Shanghai"),
        )
        assertEquals(
            StartupSnapshotValidity.ZONE_MISMATCH,
            valid.validateForStartup(7L, now, "UTC"),
        )
    }

    @Test
    fun snapshotWithoutActiveSemesterCannotCarryCourses() {
        val unsigned = signedSnapshot(createdAt = 1_000L, expiresAt = 2_000L).copy(
            courses = listOf(
                StartupSnapshotCourse(
                    id = 11L,
                    name = "不应脱离学期显示的课程",
                    teacher = "教师",
                    location = "教室",
                    dayOfWeek = 1,
                    startSection = 1,
                    duration = 1,
                    startWeek = 1,
                    endWeek = 1,
                    weekType = StartupSnapshotWeekType.ALL,
                    color = "#ffffff",
                ),
            ),
            revision = StartupSnapshotRevision("pending"),
        )
        val snapshot = unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))

        assertEquals(
            StartupSnapshotValidity.INVALID_SEMANTICS,
            snapshot.validateForStartup(
                expectedProfileId = 7L,
                nowEpochMillis = 1_500L,
                expectedZoneId = "Asia/Shanghai",
            ),
        )
    }

    private fun signedSnapshot(createdAt: Long, expiresAt: Long): StartupSnapshot {
        val unsigned = StartupSnapshot(
            protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
            profile = StartupSnapshotProfile(7L, "profile-seven"),
            semester = null,
            courses = emptyList(),
            visualSettings = StartupSnapshotVisualSettings(
                dynamicColor = false,
                wallpaperUri = null,
                transparency = 0f,
                fontStyle = StartupSnapshotFontStyle.SYSTEM,
                dividerType = StartupSnapshotDividerType.SOLID,
                dividerWidthDp = 1f,
                dividerColor = "#ffffff",
                dividerAlpha = 1f,
                courseItemHeightDp = 64,
                maxDailySections = 12,
                sectionTimes = emptyList(),
                cardCornerRadius = 16,
                cardAlpha = 1f,
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
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = expiresAt,
            zoneId = "Asia/Shanghai",
            revision = StartupSnapshotRevision("pending"),
        )
        return unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))
    }
}
