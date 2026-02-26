package com.dawncourse.feature.timetable.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知帮助类
 *
 * 负责创建通知渠道和显示通知。
 */
object NotificationHelper {
    const val CHANNEL_ID = "course_reminder_channel"
    const val NOTIFICATION_ID_BASE = 1000
    private const val NOTIFICATION_ID_RANGE: Int = Int.MAX_VALUE - NOTIFICATION_ID_BASE - 1

    /**
     * 判断当前是否“允许向用户展示通知”
     *
     * 需要同时满足两类条件：
     * 1) Android 13+：必须拥有 POST_NOTIFICATIONS 运行时权限
     * 2) 系统层面：应用通知总开关需开启（用户可能在系统设置里关闭了本应用通知）
     *
     * 设计目标：
     * - 不因为权限缺失/系统开关关闭而崩溃
     * - 业务侧统一通过该方法做“安全降级”（无法通知则静默返回）
     */
    fun canPostNotifications(context: Context): Boolean {
        // 1) 系统层面通知总开关检查（适用于所有版本）
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }

        // 2) Android 13+ 运行时通知权限检查
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "课程提醒"
            val descriptionText = "在上课前发送通知"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            try {
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            } catch (_: Throwable) {
                // 创建渠道属于“尽力而为”，失败不应影响主流程
            }
        }
    }

    /**
     * 显示上课提醒通知
     */
    fun showCourseReminder(context: Context, courseName: String, location: String) {
        // 当通知权限缺失或系统通知被关闭时，直接静默返回：
        // - 避免 SecurityException
        // - 避免“提醒广播”导致应用崩溃
        if (!canPostNotifications(context)) return

        // 确保渠道已创建
        createNotificationChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: 使用应用图标
            .setContentTitle("上课提醒: $courseName")
            .setContentText("即将上课 @$location")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            // 通知 ID 需要满足：
            // - 非负：避免某些 ROM/系统实现对负数 ID 的兼容性问题
            // - 不溢出：避免 System.currentTimeMillis().toInt() 在 2038 之后/极端情况下溢出为负数
            // - 尽量不覆盖：不同提醒尽可能使用不同 ID
            //
            // 这里基于毫秒时间戳做取模，保证在 int 范围内稳定且非负。
            val notificationId = generateStableNotificationId(System.currentTimeMillis())
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // 兜底：即使上层检查通过，系统/ROM 仍可能在极端情况下抛出异常
        } catch (_: Throwable) {
        }
    }

    /**
     * 生成稳定且非负的通知 ID
     *
     * @param seed 用于生成 ID 的种子（通常使用毫秒时间戳）
     */
    private fun generateStableNotificationId(seed: Long): Int {
        // seed 通常为正数，但依然通过取模控制范围，避免任何溢出风险
        val offset = (seed % NOTIFICATION_ID_RANGE).toInt()
        return NOTIFICATION_ID_BASE + offset
    }
}
