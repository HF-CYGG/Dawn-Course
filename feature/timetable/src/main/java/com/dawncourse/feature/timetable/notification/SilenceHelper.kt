package com.dawncourse.feature.timetable.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build

object SilenceHelper {
    
    fun hasPermission(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    fun mute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (hasPermission(context)) {
            try {
                // Set to Vibrate mode
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_VIBRATE && 
                    audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun unmute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (hasPermission(context)) {
            try {
                // Restore to Normal mode
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
