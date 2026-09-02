package com.dawncourse.feature.timetable.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dawncourse.feature.timetable.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** UI/Receiver/Worker 可替换的恢复警示边界。 */
interface MuteRecoveryAttention {
    /** 展示耗尽警示。 */
    fun showExhausted()
    /** 清理警示。 */
    fun cancel()
    /** 按持久责任刷新警示。 */
    fun refreshForCurrentState()
}

/** 自动静音恢复耗尽后的独立、低打扰且不含课程数据的通知入口。 */
@Singleton
class MuteRecoveryNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AppMuteSessionStore
) : MuteRecoveryAttention {
    /** 展示单例持久警示；通知权限缺失时由应用前台对话框继续兜底。 */
    @SuppressLint("MissingPermission")
    override fun showExhausted() {
        if (!NotificationHelper.canPostNotifications(context)) return
        createChannel()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.mute_recovery_notification_title))
            .setContentText(context.getString(R.string.mute_recovery_notification_content))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** 责任恢复或由用户确认后清理警示。 */
    @SuppressLint("MissingPermission")
    override fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 根据全部持久责任刷新单例通知，避免清理一条时遮蔽其他耗尽责任。 */
    override fun refreshForCurrentState() {
        if (store.records().any { record ->
                record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
            }
        ) {
            showExhausted()
        } else {
            cancel()
        }
    }

    /** 创建与课程提醒/状态分离的低重要性渠道。 */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mute_recovery_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.mute_recovery_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "mute_recovery_attention_v1"
        const val NOTIFICATION_ID = 1000
    }
}
