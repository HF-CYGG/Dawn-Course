package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hilt Worker 与 App Startup 初始化顺序的源码契约测试。
 *
 * WorkManager 的自定义 Factory 依赖 Application 完成 Hilt 字段注入；因此自动
 * Startup metadata 必须被主应用移除，并在 [DawnApp.onCreate] 中手动启动 Widget 初始化器。
 */
class DawnAppHiltWorkerContractTest {

    @Test
    fun `Application 完成 Hilt 与 Widget 初始化后才对 benchmark Provider 就绪`() {
        val source = projectFile("src/main/java/com/dawncourse/app/DawnApp.kt").readText()
        val superOnCreateIndex = source.indexOf("super.onCreate()")
        val workerFactoryCheckIndex = source.indexOf("requireHiltWorkerFactoryInjected()")
        val widgetInitializerIndex = source.indexOf("WidgetSyncInitializer::class.java")
        val readySignalIndex = source.indexOf("hiltWorkerFactoryInitializationGate.markReady()")

        assertTrue(source.contains("Configuration.Provider"))
        assertTrue(source.contains("HiltWorkerFactory"))
        assertTrue(source.contains("workManagerConfiguration"))
        assertTrue(source.contains("ApplicationProcessNameResolver.resolve(this)"))
        assertTrue(source.contains("ApplicationProcessPolicy.shouldInitializeSystemSurfaces"))
        assertTrue(source.contains("hiltWorkerFactoryInitializationGate.markFailed(cause)"))
        assertTrue(superOnCreateIndex >= 0)
        assertTrue(workerFactoryCheckIndex > superOnCreateIndex)
        assertTrue(widgetInitializerIndex > workerFactoryCheckIndex)
        assertTrue(readySignalIndex > widgetInitializerIndex)
    }

    @Test
    fun `主进程并行启动快照读取且恢复状态会撤销快照`() {
        val source = projectFile("src/main/java/com/dawncourse/app/DawnApp.kt").readText()

        assertTrue(source.contains("StartupSnapshotRuntime"))
        assertTrue(source.contains("startupSnapshotRuntime.start()"))
        assertTrue(source.contains("startupSnapshotRuntime.invalidate()"))
        assertTrue(
            source.indexOf("startupSnapshotRuntime.start()") <
                source.indexOf("databaseStartupRuntime.start()")
        )
    }

    @Test
    fun `benchmark Provider 在触发 WorkManager 前等待 DawnApp 的 Hilt WorkerFactory 就绪`() {
        val appSource = projectFile("src/main/java/com/dawncourse/app/DawnApp.kt").readText()
        val providerSource = projectFile(
            "src/benchmark/java/com/dawncourse/app/benchmark/BenchmarkSeedProvider.kt"
        ).readText()

        assertTrue(appSource.contains("DawnAppInitializationGate"))
        assertTrue(appSource.contains("awaitBenchmarkInitialization"))
        assertTrue(providerSource.contains("awaitDawnAppInitialization"))
        assertTrue(providerSource.contains("BENCHMARK_APP_READY_TIMEOUT_MS"))
        assertTrue(providerSource.contains("WorkManager.getInstance"))
        assertTrue(
            providerSource.indexOf("awaitDawnAppInitialization()") <
                providerSource.indexOf("WorkManager.getInstance")
        )
    }

    @Test
    fun `主 Manifest 移除两个自动 initializer metadata 但保留 provider`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("androidx.startup.InitializationProvider"))
        assertTrue(manifest.contains("androidx.work.WorkManagerInitializer"))
        assertTrue(manifest.contains("com.dawncourse.feature.widget.startup.WidgetSyncInitializer"))
        assertTrue(manifest.countOccurrences("tools:node=\"remove\"") >= 2)
        assertFalse(manifest.contains("tools:node=\"removeAll\""))
    }

    @Test
    fun `WebDAV Worker 使用 HiltWorker 构造注入而非 EntryPoint`() {
        val source = projectFile("src/main/java/com/dawncourse/app/sync/WebDavAutoSyncWorker.kt").readText()

        assertTrue(source.contains("@HiltWorker"))
        assertTrue(source.contains("@AssistedInject constructor"))
        assertTrue(source.contains("@Assisted appContext: Context"))
        assertTrue(source.contains("private val operationalDataGate: OperationalDataGate"))
        assertTrue(source.contains("private val settingsRepository: Lazy<SettingsRepository>"))
        assertTrue(source.contains("private val webDavSyncRepository: Lazy<WebDavSyncRepository>"))
        assertFalse(source.contains("EntryPointAccessors"))
        assertFalse(source.contains("WorkerEntryPoint"))
    }

    /** 返回当前 app 模块下的源码文件。 */
    private fun projectFile(relativePath: String): File = File(relativePath).also { file ->
        assertTrue("缺少契约文件：${file.absolutePath}", file.isFile)
    }

    /** 统计固定文本出现次数，避免只移除了其中一个 Startup metadata。 */
    private fun String.countOccurrences(value: String): Int = windowed(value.length)
        .count { candidate -> candidate == value }
}
