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
                NotificationHelper.showCourseReminder(context, course.name, course.location)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
