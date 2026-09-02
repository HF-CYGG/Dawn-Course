package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSnapshotRootContractTest {
    @Test
    fun `MainActivity delegates all startup gates to the unified policy`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()

        assertTrue(source.contains("DatabaseStartupUiPolicy.decide"))
        assertTrue(source.contains("decision.showSnapshot"))
        assertTrue(source.contains("preparationDecision.createDatabaseViewModels"))
        assertTrue(source.contains("decision.showLiveRoot"))
        assertTrue(source.contains("decision.showRecovery"))
        assertTrue(source.contains("StartupTimetableContent"))
        assertFalse(source.contains("databaseState is DatabaseRuntimeState.Starting"))
    }

    @Test
    fun `live success refreshes the complete snapshot after root replacement`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()

        assertTrue(source.contains("LaunchedEffect(successState)"))
        assertTrue(source.contains("viewModel.refreshStartupSnapshot(successState)"))
    }

    @Test
    fun `snapshot branch cannot create a live graph entry point`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()
        val snapshotStart = source.indexOf("decision.showSnapshot ->")
        val liveStart = source.indexOf("decision.showLiveRoot ->")

        assertTrue("snapshot branch must exist", snapshotStart >= 0)
        assertTrue("live branch must follow snapshot branch", liveStart > snapshotStart)
        val snapshotBranch = source.substring(snapshotStart, liveStart)

        listOf("ViewModelProvider", "hiltViewModel", "NavHost", "TimetableRoute").forEach { forbidden ->
            assertFalse("snapshot branch must not reference $forbidden", snapshotBranch.contains(forbidden))
        }
    }

    @Test
    fun `snapshot refresh only commits cache and leaves Widget reconciliation to Activity revision effect`() {
        val source = File("src/main/java/com/dawncourse/app/MainViewModel.kt").readText()
        val refreshStart = source.indexOf("suspend fun refreshStartupSnapshot")
        assertTrue("refresh 必须由 LaunchedEffect 结构化等待", refreshStart >= 0)
        val refresh = source.substring(refreshStart)

        assertTrue(source.contains("StartupSnapshotRuntime"))
        assertTrue(refresh.contains("startupSnapshotRuntime.replaceLatest"))
        assertFalse(refresh.contains("WidgetUpdateRepository"))
        assertFalse(refresh.contains("triggerUpdate"))
        assertFalse(refresh.contains("viewModelScope.launch"))
    }

    @Test
    fun `MainActivity keeps one policy gate until live root is successful`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()

        assertTrue(source.contains("liveRootReady"))
        assertTrue(source.contains("decision.showLiveRoot"))
        assertTrue(source.contains("startupSnapshotRuntime.releaseVisibleSnapshot()"))
    }

    @Test
    fun `Widget only refreshes through the revision effect and never through snapshot or onStart`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()
        val onStart = source.substring(source.indexOf("override fun onStart"), source.indexOf("override fun onStop"))
        val snapshotEffectStart = source.indexOf("LaunchedEffect(successState)")
        val revisionEffectStart = source.indexOf("LaunchedEffect(scheduleRevision, widgetForegroundGeneration)")

        assertTrue("Widget 必须由 revision effect 领取", revisionEffectStart >= 0)
        assertTrue(source.contains("widgetRefreshDeduplicator.runIfCurrent"))
        assertFalse("首次 onStart 不得直接广播 Widget", onStart.contains("WidgetSyncManager"))
        assertFalse(
            "快照写入成功或失败不得成为第二条 Widget 广播路径",
            source.substring(snapshotEffectStart, revisionEffectStart).contains("WidgetSyncManager"),
        )
    }
}
