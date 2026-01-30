package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra("COURSE_NAME") ?: return
        val location = intent.getStringExtra("LOCATION") ?: ""
        
        NotificationHelper.showCourseReminder(context, courseName, location)
    }
}
