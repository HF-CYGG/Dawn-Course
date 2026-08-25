package com.dawncourse.feature.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget 广播接收器
 *
 * 负责处理 Widget 的生命周期事件和更新广播。
 * 集成了 [WidgetSyncManager] 和 [MidnightUpdateReceiver] 以确保数据及时更新。
 */
class DawnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DawnWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.dawncourse.widget.FORCE_UPDATE") {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                // 必须捕获 Throwable：仅用 try/finally 时，
                // Glance/RemoteViews 抛出的 LinkageError 等 Error 会冒泡到
                // 默认 UncaughtExceptionHandler 并杀死进程。
                try {
                    DawnWidget().updateAll(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "FORCE_UPDATE failed", t)
                } finally {
                    pendingResult.finish()
                }
            }
        } else if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_DATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            
            // 收到时间变化广播（通常来自 Manifest 注册，受限但作为保底）
            // 1. 重置午夜更新闹钟
            runCatching { MidnightUpdateReceiver.scheduleNextMidnightUpdate(context) }
                .onFailure { Log.w(TAG, "scheduleNextMidnightUpdate failed", it) }

            // 2. 立即刷新 Widget
            // goAsync() 允许在 Receiver 中执行异步操作
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    DawnWidget().updateAll(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "time-change widget update failed", t)
                } finally {
                    pendingResult.finish()
                }
            }
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncManager.scheduleUpdate(context)
        MidnightUpdateReceiver.scheduleNextMidnightUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSyncManager.cancelUpdate(context)
        
        // 当用户移除所有 Widget 实例时，主动取消“午夜刷新”闹钟：
        // - 避免无 Widget 时仍每天触发闹钟唤醒
        // - 减少后台开销，符合“本地优先/长期维护”的资源控制原则
        MidnightUpdateReceiver.cancelNextMidnightUpdate(context)
    }

    private companion object {
        private const val TAG = "DawnWidgetReceiver"
    }
}
