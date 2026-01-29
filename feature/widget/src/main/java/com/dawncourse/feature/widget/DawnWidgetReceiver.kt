package com.dawncourse.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dawncourse.feature.widget.worker.WidgetSyncManager

class DawnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DawnWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncManager.scheduleUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSyncManager.cancelUpdate(context)
    }
}
