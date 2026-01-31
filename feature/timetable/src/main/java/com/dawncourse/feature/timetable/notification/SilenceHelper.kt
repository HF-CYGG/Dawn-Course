package com.dawncourse.feature.timetable.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * 自动静音帮助类
 *
 * 处理“勿扰权限”检查和铃声模式切换。
 */
object SilenceHelper {
    
    /**
     * 检查是否拥有“勿扰权限” (Notification Policy Access)
     * Android 6.0+ 需要此权限才能修改铃声模式。
     */
    fun hasPermission(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    /**
     * 开启静音 (震动模式)
     */
    fun mute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (hasPermission(context)) {
            try {
                // 如果当前不是震动或静音模式，则设置为震动模式
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_VIBRATE && 
                    audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 关闭静音 (恢复正常模式)
     */
    fun unmute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (hasPermission(context)) {
            try {
                // 恢复为正常响铃模式
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
