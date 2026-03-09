package com.dawncourse.feature.timetable.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
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
    @Inject lateinit var semesterRepository: SemesterRepository
    @Inject lateinit var calculateWeekUseCase: CalculateWeekUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 安全停止服务
     *
     * 说明：
     * - stopSelf() 只是请求系统停止服务，onDestroy 的触发存在时序
     * - 这里先取消通知更新协程，避免在“即将停止但尚未销毁”的窗口里仍持续更新通知
     * - 同时尝试移除前台通知，避免残留
     */
    private fun stopServiceSafely() {
        try {
            job?.cancel()
        } catch (_: Throwable) {
        }

        try {
            // removeNotification = true：移除前台通知，避免权限变化后残留一个空/不可见状态
            @Suppress("DEPRECATION")
            stopForeground(true)
        } catch (_: Throwable) {
        }

        try {
            stopSelf()
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须在很短时间内调用 startForeground()，否则系统会直接抛异常并杀死进程。
        // 但在 Android 13+ 或用户关闭系统通知开关时：
        // - 发送通知可能抛出 SecurityException
        // - 前台服务通知无法展示会导致服务启动链路不稳定
        //
        // 因此先做能力判断：若当前无法展示通知，则直接停止服务并返回 START_NOT_STICKY，
        // 避免系统反复重启服务造成额外开销/异常。
        if (!NotificationHelper.canPostNotifications(this)) {
            stopServiceSafely()
            return START_NOT_STICKY
        }

        // 立即启动前台服务，显示“加载中”，防止服务启动后未及时调用 startForeground 导致崩溃
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_BASE + 999,
                createNotification("正在加载课程信息...", "")
            )
        } catch (_: SecurityException) {
            stopServiceSafely()
            return START_NOT_STICKY
        } catch (_: Throwable) {
            stopServiceSafely()
            return START_NOT_STICKY
        }
        
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
                stopServiceSafely()
                return
            }

            // 运行期间用户可能在系统设置中关闭通知/撤销权限：
            // - 此时继续 notify 会抛异常或表现不一致
            // - 直接停止服务，行为与“开关关闭”保持一致（都不再常驻）
            if (!NotificationHelper.canPostNotifications(this)) {
                stopServiceSafely()
                return
            }

            val today = LocalDate.now()
            val dayOfWeek = today.dayOfWeek.value // 1 (Mon) to 7 (Sun)
            val currentSemester = semesterRepository.getCurrentSemester().first()
            val currentWeek = currentSemester?.let { calculateWeekUseCase(it.startDate) } ?: 0
            val allCourses = if (currentSemester != null && currentWeek > 0 && currentWeek <= currentSemester.weekCount) {
                courseRepository.getCoursesBySemester(currentSemester.id).first()
            } else {
                emptyList()
            }
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
                        
                        val startTime = parseLocalTimeOrNull(startTimeStr)
                        val endTime = parseLocalTimeOrNull(endTimeStr)
                        if (startTime == null || endTime == null) {
                            // 可恢复：时间字符串异常时跳过该课程，避免常驻通知更新流程崩溃
                            continue
                        }

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
                    }
                }
                
                if (!foundStatus) {
                    // All courses finished
                    title = "今日课程已结束"
                    content = "共完成 ${todayCourses.size} 门课程"
                }
            }

            val notification = createNotification(title, content)
            try {
                val manager = NotificationManagerCompat.from(this)
                manager.notify(NotificationHelper.NOTIFICATION_ID_BASE + 999, notification)
            } catch (_: SecurityException) {
                // 若出现权限/系统开关变化导致的异常，停止服务以避免每分钟重复抛错
                stopServiceSafely()
            } catch (_: Throwable) {
                // 兜底：常驻通知属于“增强体验”功能，失败时停止服务以避免持续异常循环
                stopServiceSafely()
            }
            
        } catch (_: SecurityException) {
            // 可预期异常：权限/系统开关变化导致，停止服务以保持行为一致
            stopServiceSafely()
        } catch (_: Throwable) {
            // 兜底：任何异常都不应导致服务崩溃或持续报错循环
            stopServiceSafely()
        }
    }

    /**
     * 将形如 "HH:mm" 或 "H:mm" 的时间字符串解析为 [LocalTime]
     *
     * 常驻通知每分钟刷新，若解析抛异常会造成“每分钟一次异常循环”，因此必须采用可恢复的解析方式。
     */
    private fun parseLocalTimeOrNull(value: String): LocalTime? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        if (minute !in 0..59) return null
        return LocalTime.of(hour, minute)
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
