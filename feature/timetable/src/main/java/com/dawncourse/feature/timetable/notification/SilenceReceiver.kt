package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 自动静音广播接收器
 *
 * 接收来自 AlarmManager 的静音/取消静音广播。
 */
class SilenceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MUTE = "com.dawncourse.action.MUTE"
        const val ACTION_UNMUTE = "com.dawncourse.action.UNMUTE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MUTE -> SilenceHelper.mute(context)
            ACTION_UNMUTE -> SilenceHelper.unmute(context)
        }
    }
}
