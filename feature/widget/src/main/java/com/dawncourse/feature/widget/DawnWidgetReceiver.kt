package com.dawncourse.feature.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dawncourse.feature.widget.policy.WidgetBroadcastAction
import com.dawncourse.feature.widget.policy.WidgetBroadcastActionPolicy
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import android.util.Log
import kotlinx.coroutines.CancellationException
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
        when (WidgetBroadcastActionPolicy.resolve(intent.action)) {
            WidgetBroadcastAction.RESTORE_AFTER_SYSTEM_EVENT -> {
                runCatching { WidgetSyncManager.restoreAfterSystemEvent(context) }
                    .onFailure { Log.w(TAG, "widget system-event restore failed", it) }
            }
            WidgetBroadcastAction.REFRESH_WIDGET_CONTENT -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                receiverScope.launch {
                    try {
                        WidgetSyncManager.enqueueImmediateWidgetUpdateAndAwait(appContext)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        Log.w(TAG, "persist widget content refresh failed", failure)
                    } finally {
                        pendingResult.finish()
                    }
                }
                // 本分支独占 PendingResult；不得再委托 Glance 触发第二次 goAsync()。
                return
            }
            WidgetBroadcastAction.DELEGATE_TO_GLANCE -> Unit
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        runCatching { WidgetSyncManager.restoreAfterSystemEvent(context) }
            .onFailure { Log.w(TAG, "widget enable restore failed", it) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        runCatching { WidgetSyncManager.cancelUpdate(context) }
            .onFailure { Log.w(TAG, "widget work cancellation failed", it) }
    }

    private companion object {
        private const val TAG = "DawnWidgetReceiver"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
