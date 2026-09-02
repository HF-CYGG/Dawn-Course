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
        assertTrue(source.contains("decision.createDatabaseViewModels"))
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
        val liveStart = source.indexOf("decision.createDatabaseViewModels ->")
        val snapshotBranch = source.substring(snapshotStart, liveStart)

        listOf("ViewModelProvider", "hiltViewModel", "NavHost", "TimetableRoute").forEach { forbidden ->
            assertFalse("snapshot branch must not reference $forbidden", snapshotBranch.contains(forbidden))
        }
    }
}
