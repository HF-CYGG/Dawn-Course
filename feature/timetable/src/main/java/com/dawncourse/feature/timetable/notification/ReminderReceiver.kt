package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * 提醒广播接收器
 *
 * 接收来自 AlarmManager 的上课提醒广播，并触发 NotificationHelper 显示通知。
 */
class ReminderReceiver : BroadcastReceiver() {
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
                if (!settings.enableClassReminder) return@launch
                val courseName = intent.getStringExtra("COURSE_NAME") ?: return@launch
                val location = intent.getStringExtra("LOCATION") ?: ""
                NotificationHelper.showCourseReminder(context, courseName, location)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
