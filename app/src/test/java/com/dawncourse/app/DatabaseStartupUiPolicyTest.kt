package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRecoveryReason
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动 Splash 与恢复页选择不依赖 Compose 的纯 JVM 契约。 */
class DatabaseStartupUiPolicyTest {
    @Test
    fun startupDecisionIsSealedToPreventInvalidBooleanCombinations() {
        assertTrue(DatabaseStartupUiDecision::class.java.isSealed)
    }

    @Test
    fun startingWithSnapshotReleasesSplashWithoutCreatingDatabaseViewModels() {
        assertEquals(
            DatabaseStartupUiDecision.Snapshot(snapshot(), createDatabaseViewModels = false),
            DatabaseStartupUiPolicy.decide(
                DatabaseRuntimeState.Starting,
                StartupSnapshotRuntimeState.Available(snapshot()),
            ),
        )
    }

    @Test
    fun startingWithoutSnapshotKeepsSplashWhileSnapshotIsLoadingOrMissing() {
        listOf(StartupSnapshotRuntimeState.Loading, StartupSnapshotRuntimeState.Missing).forEach { snapshotState ->
            assertEquals(
                DatabaseStartupUiDecision.Splash(createDatabaseViewModels = false),
                DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Starting, snapshotState),
            )
        }
    }

    @Test
    fun recoveryCarriesStateWithoutCreatingDatabaseViewModels() {
        val recoveryState = DatabaseRuntimeState.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid)
        assertEquals(
            DatabaseStartupUiDecision.Recovery(recoveryState),
            DatabaseStartupUiPolicy.decide(recoveryState, StartupSnapshotRuntimeState.Available(snapshot())),
        )
    }

    @Test
    fun readyCreatesNormalGraphOnlyAfterVerification() {
        assertEquals(
            DatabaseStartupUiDecision.LiveRoot,
            DatabaseStartupUiPolicy.decide(
                DatabaseRuntimeState.Ready,
                StartupSnapshotRuntimeState.Available(snapshot()),
                liveRootReady = true,
            ),
        )
    }

    @Test
    fun blockedStartupDoesNotExposeRecoveryActionsWithoutPersistentTransaction() {
        assertEquals(
            DatabaseStartupUiDecision.Blocked,
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.StartupBlocked, StartupSnapshotRuntimeState.Available(snapshot())),
        )
    }

    @Test
    fun readyKeepsSnapshotOrSplashUntilTheLiveRootActuallySucceeds() {
        assertEquals(
            DatabaseStartupUiDecision.Snapshot(snapshot(), createDatabaseViewModels = true),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Ready, StartupSnapshotRuntimeState.Available(snapshot())),
        )
        assertEquals(
            DatabaseStartupUiDecision.Splash(createDatabaseViewModels = true),
            DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Ready, StartupSnapshotRuntimeState.Missing),
        )
    }

    @Test
    fun readyWithRootFlowFailureUsesDedicatedSafeErrorSurface() {
        assertEquals(
            "RootError",
            DatabaseStartupUiPolicy.decide(
                databaseState = DatabaseRuntimeState.Ready,
                snapshotState = StartupSnapshotRuntimeState.Available(snapshot()),
                liveRootFailed = true,
            )::class.simpleName,
        )
    }

    @Test
    fun startingSnapshotReadyLoadingAndReadySuccessNeverExposeABlankOrPrematureLiveRoot() {
        val snapshotState = StartupSnapshotRuntimeState.Available(snapshot())
        val starting = DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Starting, snapshotState)
        val readyLoading = DatabaseStartupUiPolicy.decide(DatabaseRuntimeState.Ready, snapshotState)
        val readySuccess = DatabaseStartupUiPolicy.decide(
            DatabaseRuntimeState.Ready,
            snapshotState,
            liveRootReady = true,
        )

        assertEquals(DatabaseStartupUiDecision.Snapshot(snapshot(), false), starting)
        assertEquals(DatabaseStartupUiDecision.Snapshot(snapshot(), true), readyLoading)
        assertEquals(DatabaseStartupUiDecision.LiveRoot, readySuccess)
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
