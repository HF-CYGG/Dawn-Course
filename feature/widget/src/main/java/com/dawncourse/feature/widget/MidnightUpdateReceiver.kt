package com.dawncourse.feature.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dawncourse.feature.widget.worker.WidgetSyncManager
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
        // 午夜回调同样必须先确认实例存在；实际刷新交给唯一 WorkManager 任务完成。
        WidgetSyncManager.restoreAfterSystemEvent(context)
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

            val intent = explicitIntent(context)
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

        /**
         * 取消午夜更新闹钟
         *
         * 触发时机：
         * - 当 Widget 被用户从桌面全部移除（onDisabled）时应调用
         *
         * 设计目的：
         * - 避免在没有任何 Widget 实例的情况下继续每天触发午夜闹钟
         * - 减少无意义的后台唤醒与系统资源开销
         */
        fun cancelNextMidnightUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // 使用与 scheduleNextMidnightUpdate 完全一致的 requestCode 与 intent，才能准确定位并取消同一条闹钟
            val intent = explicitIntent(context)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return

            try {
                alarmManager.cancel(pendingIntent)
            } catch (_: Throwable) {
            } finally {
                // 双保险：同时取消 PendingIntent 自身，避免被复用导致残留行为
                try {
                    pendingIntent.cancel()
                } catch (_: Throwable) {
                }
            }
        }

        /**
         * 用 Intent(Context, Class) 构造显式广播 Intent，避免 AlarmManager 持有的
         * PendingIntent 被解析到第三方组件，也让静态分析明确识别目标组件。
         */
        private fun explicitIntent(context: Context): Intent =
            Intent(context, MidnightUpdateReceiver::class.java)
    }
}
