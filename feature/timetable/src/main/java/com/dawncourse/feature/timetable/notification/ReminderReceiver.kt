package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒广播接收器
 *
 * 接收来自 AlarmManager 的上课提醒广播，并触发 NotificationHelper 显示通知。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra("COURSE_NAME") ?: return
        val location = intent.getStringExtra("LOCATION") ?: ""
        
        NotificationHelper.showCourseReminder(context, courseName, location)
    }
}
