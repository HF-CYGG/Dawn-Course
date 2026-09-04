package com.dawncourse.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.startup.AppInitializer
import androidx.work.Configuration
import com.dawncourse.app.crash.CrashReporter
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntime
import com.dawncourse.feature.widget.startup.WidgetSyncInitializer
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用程序入口类 (Application Class)
 *
 * 必须使用 @HiltAndroidApp 注解标记，以触发 Hilt 的代码生成。
 * Hilt 会生成一个基类 Application，充当应用级别的依赖容器。
 * 所有使用 Hilt 的模块都必须依赖此类。
 */
@HiltAndroidApp
class DawnApp : Application(), Configuration.Provider {

    /** Hilt 为 WorkManager 创建带依赖的 Worker。 */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** 主进程唯一数据库启动 Runtime；隔离脚本进程不会启动它。 */
    @Inject
    lateinit var databaseStartupRuntime: DatabaseStartupRuntime

    /** 仅从 DataStore 和 no-backup 文件读取，绝不触发 AppDatabase 或 DAO。 */
    @Inject
    lateinit var startupSnapshotRuntime: StartupSnapshotRuntime

    /** 应用级根协程只记录异常类型，避免 collector 载荷或数据细节进入日志。 */
    private val startupRuntimeExceptionHandler = CoroutineExceptionHandler { _, failure ->
        Log.e(TAG, "startup runtime collector failed type=${failure.javaClass.simpleName}")
    }

    private val startupRuntimeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + startupRuntimeExceptionHandler,
    )

    /** WorkManager 延迟初始化时读取的应用级配置。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 全局崩溃捕获必须在这里安装，而不是 onCreate()：
        //
        // Android 进程启动顺序为
        //   Application.attachBaseContext() → 各 ContentProvider.onCreate() → Application.onCreate()
        //
        // androidx.startup.InitializationProvider 及其 App Startup 初始化器
        // （WidgetSyncInitializer / WorkManagerInitializer 等）都在 onCreate() 之前的
        // provider 阶段执行。冷启动白屏闪退恰恰可能发生在这一段——若等到 onCreate()
        // 再安装 handler，这段崩溃就永远落不了盘。
        CrashReporter.install(base)
    }

    override fun onCreate() {
        try {
            super.onCreate()
            requireHiltWorkerFactoryInjected()
            val processName = ApplicationProcessNameResolver.resolve(this)
            if (ApplicationProcessPolicy.shouldInitializeSystemSurfaces(packageName, processName)) {
                // 两者各自提交 IO 后立即返回；快照读取从不等待或打开 Room。
                startupSnapshotRuntime.start()
                databaseStartupRuntime.start()
                startupRuntimeScope.launch {
                    databaseStartupRuntime.state.collect { state ->
                        if (state is DatabaseRuntimeState.RecoveryRequired ||
                            state == DatabaseRuntimeState.StartupBlocked
                        ) {
                            DatabaseRecoverySurfaceTransition.execute(
                                publishSafeSystemSurface = {
                                    runCatching {
                                        WidgetSyncManager.enterRecoveryState(this@DawnApp)
                                    }.onFailure { failure ->
                                        Log.w(TAG, "enter recovery-safe Widget state failed", failure)
                                    }
                                },
                                invalidateSnapshot = startupSnapshotRuntime::invalidate,
                            )
                        }
                    }
                }
                // 主进程先恢复 Widget 的 WorkManager 状态，再允许 benchmark Provider 清理和播种。
                AppInitializer.getInstance(this)
                    .initializeComponent(WidgetSyncInitializer::class.java)
            }
            hiltWorkerFactoryInitializationGate.markReady()
        } catch (cause: Throwable) {
            hiltWorkerFactoryInitializationGate.markFailed(cause)
            throw cause
        }
    }

    /**
     * Hilt 生成的 Application 会在此类调用 [super.onCreate] 时完成字段注入。
     * 完整的 Application 初始化在 Widget 初始化器完成后才对 benchmark Provider 可见。
     */
    private fun requireHiltWorkerFactoryInjected() {
        check(::workerFactory.isInitialized) {
            "DawnApp HiltWorkerFactory was not injected before Application.onCreate"
        }
    }

    companion object {
        private const val TAG = "DawnApp"
        private val hiltWorkerFactoryInitializationGate = DawnAppInitializationGate()

        /** 供仅 benchmark 源集的 Provider 等待完整的 Application 初始化。 */
        internal fun awaitBenchmarkInitialization(
            timeoutMillis: Long
        ): DawnAppInitializationGate.AwaitResult =
            hiltWorkerFactoryInitializationGate.await(timeoutMillis)
    }

}
