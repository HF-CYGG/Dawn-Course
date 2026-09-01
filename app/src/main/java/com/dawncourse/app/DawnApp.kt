package com.dawncourse.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.startup.AppInitializer
import androidx.work.Configuration
import com.dawncourse.feature.widget.startup.WidgetSyncInitializer
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import dagger.hilt.android.HiltAndroidApp
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

    /** WorkManager 延迟初始化时读取的应用级配置。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        try {
            super.onCreate()
            requireHiltWorkerFactoryInjected()
            val processName = ApplicationProcessNameResolver.resolve(this)
            if (ApplicationProcessPolicy.shouldInitializeSystemSurfaces(packageName, processName)) {
                // 提交 IO 初始化后立即返回，让 Activity splash/恢复页保持可响应。
                databaseStartupRuntime.start()
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
        private val hiltWorkerFactoryInitializationGate = DawnAppInitializationGate()

        /** 供仅 benchmark 源集的 Provider 等待完整的 Application 初始化。 */
        internal fun awaitBenchmarkInitialization(
            timeoutMillis: Long
        ): DawnAppInitializationGate.AwaitResult =
            hiltWorkerFactoryInitializationGate.await(timeoutMillis)
    }
}
