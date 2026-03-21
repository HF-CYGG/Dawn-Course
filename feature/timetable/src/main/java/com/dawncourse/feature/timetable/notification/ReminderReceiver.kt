package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/**
 * 提醒广播接收器
 *
 * 接收来自 AlarmManager 的上课提醒广播，并触发 NotificationHelper 显示通知。
 */
class ReminderReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun courseRepository(): CourseRepository
        fun semesterRepository(): SemesterRepository
        fun calculateWeekUseCase(): CalculateWeekUseCase
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
                val courseId = intent.getLongExtra("COURSE_ID", 0L)
                if (courseId <= 0L) return@launch
                val course = entryPoint.courseRepository().getCourseById(courseId) ?: return@launch
                val currentSemester = entryPoint.semesterRepository().getCurrentSemester().first() ?: return@launch
                if (course.semesterId != currentSemester.id) return@launch
                val currentWeek = entryPoint.calculateWeekUseCase().invoke(currentSemester.startDate)
                if (currentWeek <= 0 || currentWeek > currentSemester.weekCount) return@launch
                val today = LocalDate.now()
                val dayOfWeek = today.dayOfWeek.value
                if (course.dayOfWeek != dayOfWeek) return@launch
                if (currentWeek < course.startWeek || currentWeek > course.endWeek) return@launch
                when (course.weekType) {
                    Course.WEEK_TYPE_ODD -> if (currentWeek % 2 == 0) return@launch
                    Course.WEEK_TYPE_EVEN -> if (currentWeek % 2 != 0) return@launch
                }
                val nowMillis = System.currentTimeMillis()
                val dedupeKey = "course_${course.id}_${today.toEpochDay()}"
                if (shouldSkipDuplicate(context, dedupeKey, nowMillis)) return@launch
                val notificationSeed = buildStableNotificationSeed(course.id, today.toEpochDay())
                NotificationHelper.showCourseReminder(
                    context = context,
                    courseName = course.name,
                    location = course.location,
                    notificationIdSeed = notificationSeed
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 判断是否需要跳过重复提醒
     *
     * 设计目标：
     * - 防止同一课程在短时间内被重复广播导致重复通知
     * - 不依赖数据库或复杂状态，避免对主流程造成额外负担
     */
    private fun shouldSkipDuplicate(context: Context, key: String, nowMillis: Long): Boolean {
        val prefs = context.getSharedPreferences("dc_reminder_dedupe", Context.MODE_PRIVATE)
        val lastMillis = prefs.getLong(key, 0L)
        val interval = nowMillis - lastMillis
        val minIntervalMillis = 2 * 60 * 1000L
        if (interval in 0 until minIntervalMillis) return true
        prefs.edit().putLong(key, nowMillis).apply()
        return false
    }

    /**
     * 构建稳定的通知 ID 种子
     *
     * 规则：
     * - 同一课程同一天的提醒使用同一通知 ID
     * - 发生重复广播时，后续通知会覆盖前一条，避免通知堆叠
     */
    private fun buildStableNotificationSeed(courseId: Long, epochDay: Long): Long {
        val base = abs(courseId) % 100_000L
        return epochDay * 100_000L + base
    }
}
