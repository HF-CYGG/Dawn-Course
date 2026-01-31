package com.dawncourse.feature.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dawncourse.feature.widget.worker.WidgetSyncManager

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
            WidgetSyncManager.triggerImmediateUpdate(context)
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
    }
}
