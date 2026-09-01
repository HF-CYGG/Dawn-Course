package com.dawncourse.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 课程状态通知的系统可用性。
 */
enum class CourseStatusNotificationAvailability {
    /** 应用与渠道均允许通知。 */
    AVAILABLE,

    /** 应用级通知总开关已关闭。 */
    APP_NOTIFICATIONS_DISABLED,

    /** 已存在的课程状态渠道已关闭。 */
    CHANNEL_DISABLED
}

/**
 * 课程状态通知可用性的纯决策策略。
 */
object CourseStatusNotificationAvailabilityPolicy {
    /**
     * 应用总开关优先；仅当渠道已经存在且被禁用时才判为渠道不可用。
     */
    fun resolve(
        appNotificationsEnabled: Boolean,
        channelExists: Boolean,
        channelEnabled: Boolean
    ): CourseStatusNotificationAvailability = when {
        !appNotificationsEnabled ->
            CourseStatusNotificationAvailability.APP_NOTIFICATIONS_DISABLED
        channelExists && !channelEnabled ->
            CourseStatusNotificationAvailability.CHANNEL_DISABLED
        else -> CourseStatusNotificationAvailability.AVAILABLE
    }
}

/**
 * 读取系统通知开关并打开对应系统设置页。
 *
 * Channel ID 是 P0-2 已固定的跨模块协议值；settings 不依赖 timetable feature。
 */
object CourseStatusNotificationAvailabilityHelper {
    /** 课程状态通知的稳定渠道 ID。 */
    private const val COURSE_STATUS_CHANNEL_ID = "course_status_channel_v1"

    /**
     * 检查应用通知总开关和已经存在的课程状态渠道。
     */
    fun resolve(context: Context): CourseStatusNotificationAvailability {
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.getNotificationChannel(COURSE_STATUS_CHANNEL_ID)
        } else {
            null
        }
        return CourseStatusNotificationAvailabilityPolicy.resolve(
            appNotificationsEnabled = appNotificationsEnabled,
            channelExists = channel != null,
            channelEnabled = channel?.importance != NotificationManager.IMPORTANCE_NONE
        )
    }

    /**
     * 打开与不可用原因对应的系统通知设置页。
     */
    fun openSettings(
        context: Context,
        availability: CourseStatusNotificationAvailability
    ): Boolean {
        val intent = when (availability) {
            CourseStatusNotificationAvailability.APP_NOTIFICATIONS_DISABLED ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            CourseStatusNotificationAvailability.CHANNEL_DISABLED ->
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, COURSE_STATUS_CHANNEL_ID)
                }
            CourseStatusNotificationAvailability.AVAILABLE -> return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
