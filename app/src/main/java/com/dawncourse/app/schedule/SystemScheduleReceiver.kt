package com.dawncourse.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dawncourse.feature.timetable.notification.ReminderScheduler
import com.dawncourse.feature.widget.worker.WidgetSyncManager

/**
 * 系统事件后的课表调度恢复广播接收器。
 *
 * 接收器本身不访问数据层、不启动前台服务，只将恢复请求交给 WorkManager 和 Widget 恢复入口。
 */
class SystemScheduleReceiver : BroadcastReceiver() {

    /**
     * 仅处理会清除闹钟或改变本地时间基准的系统广播。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (!SystemScheduleEventPolicy.shouldRestore(intent.action)) {
            return
        }

        val appContext = context.applicationContext
        ReminderScheduler.triggerImmediateWork(appContext, forceReplay = true)
        WidgetSyncManager.restoreAfterSystemEvent(appContext)
    }
}

/**
 * 系统恢复事件白名单。
 *
 * 使用字符串值保持为纯 Kotlin 策略，避免 JVM 测试依赖 Android framework 常量。
 */
internal object SystemScheduleEventPolicy {
    private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    private const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
    private const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"

    /**
     * 判断指定广播是否需要重新建立课表提醒与 Widget 更新链路。
     */
    fun shouldRestore(action: String?): Boolean = action in setOf(
        ACTION_BOOT_COMPLETED,
        ACTION_MY_PACKAGE_REPLACED,
        ACTION_TIME_SET,
        ACTION_TIMEZONE_CHANGED
    )
}
