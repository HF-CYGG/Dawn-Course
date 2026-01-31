package com.dawncourse.feature.timetable.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示上课提醒通知
     */
    fun showCourseReminder(context: Context, courseName: String, location: String) {
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
            // 使用当前时间戳作为 ID，避免通知覆盖
            notificationManager.notify(NOTIFICATION_ID_BASE + System.currentTimeMillis().toInt(), builder.build())
        } catch (e: SecurityException) {
            // 处理权限缺失情况
        }
    }
}
