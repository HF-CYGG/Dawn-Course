package com.dawncourse.feature.timetable.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class DailySchedulerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun courseRepository(): CourseRepository
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java
        )
        val courseRepo = entryPoint.courseRepository()
        val settingsRepo = entryPoint.settingsRepository()
        val settings = settingsRepo.settings.first()

        if (!settings.enableClassReminder) return Result.success()

        val today = LocalDate.now()
        val dayOfWeek = today.dayOfWeek.value // 1 (Mon) to 7 (Sun)
        
        // Calculate Current Week
        var currentWeek = 1
        if (settings.startDateTimestamp > 0L) {
            val startDate = java.time.Instant.ofEpochMilli(settings.startDateTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val daysDiff = ChronoUnit.DAYS.between(startDate, today)
            if (daysDiff >= 0) {
                currentWeek = (daysDiff / 7).toInt() + 1
            }
        }

        val allCourses = courseRepo.getAllCourses().first()
        val todayCourses = allCourses.filter { course ->
            // 1. Check Day of Week
            if (course.dayOfWeek != dayOfWeek) return@filter false
            
            // 2. Check Week Range
            if (currentWeek < course.startWeek || currentWeek > course.endWeek) return@filter false
            
            // 3. Check Week Type
            when (course.weekType) {
                Course.WEEK_TYPE_ODD -> if (currentWeek % 2 == 0) return@filter false
                Course.WEEK_TYPE_EVEN -> if (currentWeek % 2 != 0) return@filter false
            }
            true
        }

        val reminderMinutes = settings.reminderMinutes
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Parse Section Times
        val sectionTimes = settings.sectionTimes
        
        todayCourses.forEach { course ->
            if (sectionTimes.isNotEmpty() && course.startSection <= sectionTimes.size && course.startSection > 0) {
                 val startTimeStr = sectionTimes[course.startSection - 1].startTime
                 try {
                     val timeParts = startTimeStr.split(":")
                     val hour = timeParts[0].toInt()
                     val minute = timeParts[1].toInt()
                     
                     val courseDateTime = LocalDateTime.of(today, LocalTime.of(hour, minute))
                     val triggerTime = courseDateTime.minusMinutes(reminderMinutes.toLong())
                     
                     // Only schedule if time is in the future
                     if (triggerTime.isAfter(LocalDateTime.now())) {
                         val intent = Intent(applicationContext, ReminderReceiver::class.java).apply {
                             putExtra("COURSE_NAME", course.name)
                             putExtra("LOCATION", course.location)
                         }
                         val pendingIntent = PendingIntent.getBroadcast(
                             applicationContext,
                             course.id.toInt(), // Use ID as RequestCode
                             intent,
                             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                         )
                         
                         // Use setExactAndAllowWhileIdle for reliability
                         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                             alarmManager.setExactAndAllowWhileIdle(
                                 AlarmManager.RTC_WAKEUP,
                                 triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                 pendingIntent
                             )
                         } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                             alarmManager.setExact(
                                 AlarmManager.RTC_WAKEUP,
                                 triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                 pendingIntent
                             )
                         } else {
                             alarmManager.set(
                                 AlarmManager.RTC_WAKEUP,
                                 triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                 pendingIntent
                             )
                         }
                     }
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
            }
        }

        return Result.success()
    }
}
