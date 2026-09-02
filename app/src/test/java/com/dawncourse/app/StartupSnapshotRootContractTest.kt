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
    fun `live refresh delegates latest wins and widget failure handling to StartupSnapshotRuntime`() {
        val source = File("src/main/java/com/dawncourse/app/MainViewModel.kt").readText()
        val refreshStart = source.indexOf("suspend fun refreshStartupSnapshot")
        assertTrue("refresh 必须由 LaunchedEffect 结构化等待", refreshStart >= 0)
        val refresh = source.substring(refreshStart)

        assertTrue(source.contains("StartupSnapshotRuntime"))
        assertTrue(refresh.contains("startupSnapshotRuntime.replaceLatest"))
        assertTrue(refresh.contains("runCatching { widgetUpdateRepository.triggerUpdate() }"))
        assertFalse(refresh.contains("viewModelScope.launch"))
    }

    @Test
    fun `MainActivity keeps one policy gate until live root is successful`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()

        assertTrue(source.contains("liveRootReady"))
        assertTrue(source.contains("decision.showLiveRoot"))
        assertTrue(source.contains("startupSnapshotRuntime.releaseVisibleSnapshot()"))
    }
}
