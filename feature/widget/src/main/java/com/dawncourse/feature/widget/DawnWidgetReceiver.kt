package com.dawncourse.feature.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dawncourse.feature.widget.worker.WidgetSyncManager

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
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSyncManager.cancelUpdate(context)
    }
}
