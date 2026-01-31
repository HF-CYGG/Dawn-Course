package com.dawncourse.feature.timetable.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * 常驻通知服务 (Foreground Service)
 *
 * 在通知栏常驻显示当前或下一节课程信息。
 *
 * 核心逻辑：
 * 1. 启动为前台服务，避免被系统杀后台。
 * 2. 启动一个协程，每分钟更新一次通知内容。
 * 3. 根据当前时间和课表数据，判断显示“正在上课”、“下节课”或“今日结束”。
 */
@AndroidEntryPoint
class PersistentNotificationService : Service() {

    @Inject lateinit var courseRepository: CourseRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即启动前台服务，显示“加载中”，防止服务启动后未及时调用 startForeground 导致崩溃
        startForeground(NotificationHelper.NOTIFICATION_ID_BASE + 999, createNotification("正在加载课程信息...", ""))
        
        job?.cancel()
        job = serviceScope.launch {
            while (isActive) {
                updateNotification()
                // 对齐到下一分钟的 00 秒执行，保证时间显示的准确性
                val now = LocalTime.now()
                val secondsUntilNextMinute = 60 - now.second
                delay(secondsUntilNextMinute * 1000L)
            }
        }
        
        // START_STICKY: 如果服务被系统意外杀死，系统会尝试重建服务
        return START_STICKY
    }

    private suspend fun updateNotification() {
        try {
            val settings = settingsRepository.settings.first()
            if (!settings.enablePersistentNotification) {
                stopSelf()
                return
            }

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

            val allCourses = courseRepository.getAllCourses().first()
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
            }.sortedBy { it.startSection }

            val sectionTimes = settings.sectionTimes
            val nowTime = LocalTime.now()
            
            var title = "今日课程已结束"
            var content = "好好休息，准备明天的课程吧"
            
            if (todayCourses.isEmpty()) {
                title = "今天没有安排课程"
                content = "享受美好的一天"
            } else {
                // Find current status
                var foundStatus = false
                
                for (course in todayCourses) {
                    val endSection = course.startSection + course.duration - 1
                    if (sectionTimes.isNotEmpty() && 
                        course.startSection <= sectionTimes.size && 
                        endSection <= sectionTimes.size &&
                        course.startSection > 0 && endSection > 0) {
                        
                        val startTimeStr = sectionTimes[course.startSection - 1].startTime
                        val endTimeStr = sectionTimes[endSection - 1].endTime
                        
                        try {
                            val startParts = startTimeStr.split(":")
                            val endParts = endTimeStr.split(":")
                            val startTime = LocalTime.of(startParts[0].toInt(), startParts[1].toInt())
                            val endTime = LocalTime.of(endParts[0].toInt(), endParts[1].toInt())
                            
                            if (nowTime.isBefore(startTime)) {
                                // Upcoming course
                                title = "下节课: ${course.name}"
                                content = "${startTimeStr} @ ${course.location}"
                                foundStatus = true
                                break
                            } else if (!nowTime.isAfter(endTime)) {
                                // Currently in class
                                title = "正在上课: ${course.name}"
                                content = "下课时间: ${endTimeStr} @ ${course.location}"
                                foundStatus = true
                                break
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                if (!foundStatus) {
                    // All courses finished
                    title = "今日课程已结束"
                    content = "共完成 ${todayCourses.size} 门课程"
                }
            }

            val notification = createNotification(title, content)
            val manager = androidx.core.app.NotificationManagerCompat.from(this)
            try {
                manager.notify(NotificationHelper.NOTIFICATION_ID_BASE + 999, notification)
            } catch (e: SecurityException) { }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        NotificationHelper.createNotificationChannel(this)
        
        // Create an Intent to open the app when clicking the notification
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for persistent notifications
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
