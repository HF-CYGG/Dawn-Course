package com.dawncourse.feature.widget.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
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
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.w(TAG, "Widget sync initialization failed", throwable)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            // 启动时也必须先经过实例判定，避免没有 Widget 时创建后台任务。
            WidgetSyncManager.restoreAfterSystemEvent(appContext)
            // 注册系统时间/日期变化广播，以便在变化后再次按实例恢复。
            WidgetSyncManager.registerTimeChangeReceiver(appContext)
        }
    }

    /**
     * 定义此初始化器依赖的其他初始化器
     *
     * 本初始化器由 DawnApp 在 Hilt 字段注入完成后手动执行，不参与 App Startup 自动依赖图。
     */
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }

    private companion object {
        private const val TAG = "WidgetSyncInitializer"
    }
}
