package com.dawncourse.feature.widget.startup

import android.content.Context
import androidx.startup.Initializer
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        // 在默认的协程调度器中启动后台任务，避免阻塞主线程
        CoroutineScope(Dispatchers.Default).launch {
            // 调度小组件的后台定期刷新任务
            WidgetSyncManager.scheduleUpdate(context)
            // 注册系统时间/日期变化广播，以便在时间变更时立即刷新小组件
            WidgetSyncManager.registerTimeChangeReceiver(context)
        }
    }

    /**
     * 定义此初始化器依赖的其他初始化器
     *
     * 由于本初始化器没有前置依赖，因此返回空列表。
     */
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}