package com.dawncourse.feature.timetable.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 自动静音广播接收器
 *
 * 接收来自 AlarmManager 的静音/取消静音广播。
 */
class SilenceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MUTE = "com.dawncourse.action.MUTE"
        const val ACTION_UNMUTE = "com.dawncourse.action.UNMUTE"
        private const val EXTRA_COURSE_ID = "COURSE_ID"
        private const val EXTRA_UNMUTE_TIME_MILLIS = "UNMUTE_TIME_MILLIS"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ReceiverEntryPoint::class.java
                )
                val settings = entryPoint.settingsRepository().settings.first()
                if (!settings.enableAutoMute) return@launch
                when (intent.action) {
                    ACTION_MUTE -> {
                        SilenceHelper.mute(context)
                        scheduleUnmuteIfNeeded(context, intent)
                    }
                    ACTION_UNMUTE -> SilenceHelper.unmute(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun scheduleUnmuteIfNeeded(context: Context, intent: Intent) {
        val triggerAt = intent.getLongExtra(EXTRA_UNMUTE_TIME_MILLIS, -1L)
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
        if (triggerAt <= System.currentTimeMillis() || courseId <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val unmuteIntent = Intent(context, SilenceReceiver::class.java).apply {
            action = ACTION_UNMUTE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            courseId.toInt() + 20000,
            unmuteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: Throwable) {
        }
    }
}
