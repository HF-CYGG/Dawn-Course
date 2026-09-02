package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRecoveryReason
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 启动 Splash 与恢复页选择不依赖 Compose 的纯 JVM 契约。 */
class DatabaseStartupUiPolicyTest {
    @Test
    fun startingWithSnapshotReleasesSplashWithoutCreatingDatabaseViewModels() {
        assertEquals(
            DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = false,
                showSnapshot = true,
                showRecovery = false,
                showBlocked = false,
            ),
            DatabaseStartupUiPolicy.decide(
                DatabaseRuntimeState.Starting,
                StartupSnapshotRuntimeState.Available(snapshot()),
            )
        )
    }

    @Test
    fun startingWithoutSnapshotKeepsSplashWhileSnapshotIsLoadingOrMissing() {
        listOf(StartupSnapshotRuntimeState.Loading, StartupSnapshotRuntimeState.Missing).forEach { snapshotState ->
            assertEquals(
                DatabaseStartupUiDecision(
                    keepSplash = true,
                    createDatabaseViewModels = false,
                    showSnapshot = false,
                    showRecovery = false,
                    showBlocked = false,
                ),
                DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Starting, snapshotState),
            )
        }
    }

    @Test
    fun recoveryReleasesSplashWithoutCreatingDatabaseViewModels() {
        assertEquals(
            DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = false,
                showSnapshot = false,
                showRecovery = true,
                showBlocked = false,
            ),
            DatabaseStartupUiPolicy.decide(
                DatabaseRuntimeState.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid),
                StartupSnapshotRuntimeState.Available(snapshot()),
            )
        )
    }

    @Test
    fun readyCreatesNormalGraphOnlyAfterVerification() {
        assertEquals(
            DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = true,
                showSnapshot = false,
                showRecovery = false,
                showBlocked = false,
            ),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Ready, StartupSnapshotRuntimeState.Available(snapshot()))
        )
    }

    @Test
    fun blockedStartupDoesNotExposeRecoveryActionsWithoutPersistentTransaction() {
        assertEquals(
            DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = false,
                showSnapshot = false,
                showRecovery = false,
                showBlocked = true,
            ),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.StartupBlocked, StartupSnapshotRuntimeState.Available(snapshot()))
        )
    }

    private fun snapshot() = com.dawncourse.core.domain.model.StartupSnapshot(
        protocolVersion = 1,
        profile = com.dawncourse.core.domain.model.StartupSnapshotProfile(1L, "profile"),
        semester = null,
        courses = emptyList(),
        visualSettings = com.dawncourse.core.domain.model.StartupSnapshotVisualSettings(
            dynamicColor = false,
            wallpaperUri = null,
            transparency = 0f,
            fontStyle = com.dawncourse.core.domain.model.StartupSnapshotFontStyle.SYSTEM,
            dividerType = com.dawncourse.core.domain.model.StartupSnapshotDividerType.SOLID,
            dividerWidthDp = 1f,
            dividerColor = "#ffffff",
            dividerAlpha = 1f,
            courseItemHeightDp = 64,
            maxDailySections = 12,
            sectionTimes = emptyList(),
            cardCornerRadius = 16,
            cardAlpha = 1f,
            showCourseIcons = true,
            wallpaperMode = com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode.CROP,
            themeMode = com.dawncourse.core.domain.model.StartupSnapshotThemeMode.SYSTEM,
            showWeekend = true,
            showSidebarTime = true,
            showSidebarIndex = true,
            hideNonThisWeek = false,
            showDateInHeader = false,
            backgroundBlur = 0f,
            backgroundBrightness = 1f,
        ),
        createdAtEpochMillis = 1L,
        expiresAtEpochMillis = 2L,
        zoneId = "UTC",
        revision = com.dawncourse.core.domain.model.StartupSnapshotRevision("revision"),
    )
}
