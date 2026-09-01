package com.dawncourse.feature.widget.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件同步初始化器
 *
 * 使用 Jetpack App Startup 在应用冷启动时按需初始化桌面小组件的后台任务和广播监听。
 * 将这部分逻辑从 Application 的 onCreate 中解耦出来，以优化应用的启动速度和模块的独立性。
 */
class WidgetSyncInitializer : Initializer<Unit> {

    /**
     * 执行初始化操作
     *
     * @param context 应用上下文
     */
    override fun create(context: Context) {
        val appContext = context.applicationContext

        // 冷启动初始化必须是“绝对不会杀进程”的：
        //
        // InitializationProvider.onCreate() 运行在 Application.onCreate() 之前，此时窗口还是空白的。
        // 一旦这里抛出未捕获异常，用户看到的就是“白屏后闪退”，且没有任何界面可以提示。
        //
        // 因此这里做了三层防护：
        // 1) dependencies() 声明依赖 WorkManagerInitializer，消除与 WorkManager 初始化的竞态
        //    （详见 dependencies() 注释）
        // 2) 协程作用域挂载 CoroutineExceptionHandler，避免异常冒泡到
        //    Thread.UncaughtExceptionHandler 而直接杀死进程
        // 3) WidgetSyncManager 内部对每个系统调用单独兜底，保证一项失败不影响另一项
        val handler = CoroutineExceptionHandler { _, throwable ->
            // 小组件同步属于“增强体验”功能，初始化失败不应影响 App 正常启动
            Log.w(TAG, "Widget sync initialization failed", throwable)
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            // 调度小组件的后台定期刷新任务
            WidgetSyncManager.scheduleUpdate(appContext)
            // 注册系统时间/日期变化广播，以便在时间变更时立即刷新小组件
            WidgetSyncManager.registerTimeChangeReceiver(appContext)
        }
    }

    /**
     * 定义此初始化器依赖的其他初始化器
     *
     * 必须声明依赖 [WorkManagerInitializer]：
     *
     * WorkManager 与本初始化器挂载在同一个 androidx.startup.InitializationProvider 上，
     * 若不声明依赖关系，App Startup 不保证两者的执行顺序。
     * 而 [WidgetSyncManager.scheduleUpdate] 会调用 WorkManager.getInstance()，
     * 在 WorkManager 尚未初始化、且 Application 未实现 Configuration.Provider 时，
     * 该方法会直接抛出 IllegalStateException。
     *
     * 声明依赖后，App Startup 会保证 WorkManager 先完成初始化，再执行本初始化器。
     */
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(WorkManagerInitializer::class.java)
    }

    private companion object {
        private const val TAG = "WidgetSyncInitializer"
    }
}
