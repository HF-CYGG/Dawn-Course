package com.dawncourse.feature.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * 午夜更新广播接收器
 *
 * 负责在每天午夜 (00:01) 强制刷新 Widget，
 * 以便及时切换到第二天的课程显示。
 */
// 1. 定义一个用于午夜刷新的广播接收器
class MidnightUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 强制刷新 Widget
        runBlocking {
            DawnWidget().updateAll(context)
        }
        // 重新调度明天的闹钟 (保底)
        scheduleNextMidnightUpdate(context)
    }

    companion object {
        // 2. 调度函数 (在 App 启动或 Widget 首次创建时调用)
        fun scheduleNextMidnightUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // 计算下一个 00:01 的时间戳
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
            }

            val intent = Intent(context, MidnightUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 设置精准闹钟 (Doze 模式下也能唤醒)
            // 注意：Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，这里假设已有或作为 best effort
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                // 如果没有精确闹钟权限，则使用非精确闹钟作为 fallback
                 alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    }
}
