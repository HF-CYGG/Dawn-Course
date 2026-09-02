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
    fun `Widget only refreshes through direct revision effect and keeps Reminder independent`() {
        val source = File("src/main/java/com/dawncourse/app/MainActivity.kt").readText()
        val onStart = source.substring(source.indexOf("override fun onStart"), source.indexOf("override fun onStop"))
        val onStop = source.substring(source.indexOf("override fun onStop"), source.indexOf("override fun onCreate"))
        val revisionEffectStart = source.indexOf("LaunchedEffect(scheduleRevision, widgetForegroundGeneration)")
        val revisionEffect = source.substring(revisionEffectStart, source.indexOf("// 监听 WebDAV", revisionEffectStart))
        val widgetCall = revisionEffect.indexOf("WidgetSyncManager.updateWidgetNow(applicationContext)")
        val reminderWork = revisionEffect.indexOf("runStartupBackgroundWork")

        assertTrue("Widget 必须由 revision effect 领取", revisionEffectStart >= 0)
        assertTrue("首次 onStart 只能写入初始 generation 0", onStart.contains("widgetForegroundGeneration = 0L"))
        assertTrue("onStop 后的下一次 onStart 才递增 generation", onStart.contains("widgetForegroundGeneration += 1"))
        assertTrue("onStop 仅记录生命周期事实", onStop.contains("hasWidgetStoppedSinceStart = true"))
        assertFalse("onStop 自身不得改变 Compose key 或重启 effect", onStop.contains("widgetForegroundGeneration"))
        assertFalse("首次 onStart 不得直接广播 Widget", onStart.contains("WidgetSyncManager"))
        assertFalse("不得保留跨 dispatcher 的 Widget 去重状态", source.contains("WidgetRefreshDeduplicator"))
        assertFalse("Widget 失败不得提前返回并跳过 Reminder", revisionEffect.contains("return@LaunchedEffect"))
        assertTrue("Widget 必须在进入后台 Reminder 前同步 best-effort 调用", widgetCall in 0 until reminderWork)
    }
}
