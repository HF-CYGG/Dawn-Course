package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.ActiveTimetableActionGate
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 对新 TriggerKey URI 进行二次领域校验的课程提醒广播。 */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        /** 新提醒 PendingIntent 的唯一 action。 */
        const val ACTION_REMINDER = "com.dawncourse.action.REMINDER"
        private const val TAG = "ReminderReceiver"
    }

    /** Receiver 在应用单例组件中需要的领域依赖。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        /** 在解析数据库 Repository 前检查启动状态。 */
        fun operationalDataGate(): OperationalDataGate
        /** 课程仓库。 */
        fun courseRepository(): CourseRepository
        /** 与 Profile 切换共用的最终动作线性化门。 */
        fun activeTimetableActionGate(): ActiveTimetableActionGate
        /** 周次计算用例。 */
        fun calculateWeekUseCase(): CalculateWeekUseCase
        /** 设置仓库。 */
        fun settingsRepository(): SettingsRepository
    }

    /** 旧版无 URI 广播会在启动异步工作前直接拒绝。 */
    override fun onReceive(context: Context, intent: Intent) {
        val key = TriggerIntentPolicy.parse(intent.action, intent.dataString) ?: return
        if (key.profileId == TriggerKey.LEGACY_PROFILE_ID) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliverIfStillValid(context, key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.e(TAG, "课程提醒广播处理失败: ${failure.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 触发时重新校验开关、当前学期、课程与 occurrence 日期。 */
    private suspend fun deliverIfStillValid(context: Context, key: TriggerKey) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )
        if (entryPoint.operationalDataGate().readiness() != OperationalDataReadiness.READY) return
        val candidate = entryPoint.courseRepository().getCourseById(key.courseId) ?: return
        entryPoint.activeTimetableActionGate().executeIfActive(
            profileId = key.profileId,
            semesterId = candidate.semesterId,
        ) { activeContext ->
            val semester = activeContext.semester ?: return@executeIfActive
            val course = entryPoint.courseRepository().getCourseById(key.courseId)
                ?.takeIf { it.semesterId == semester.id } ?: return@executeIfActive
            val settings = entryPoint.settingsRepository().settings.first()
            if (!settings.enableClassReminder) return@executeIfActive
            val zoneId = ZoneId.systemDefault()
            val occurrenceMillis = key.occurrenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val currentWeek = entryPoint.calculateWeekUseCase().invoke(semester.startDate, occurrenceMillis)
            if (currentWeek !in 1..semester.weekCount) return@executeIfActive
            val now = Instant.now()
            if (!TriggerOccurrencePolicy.isInReminderWindow(
                    course = course,
                    occurrenceDate = key.occurrenceDate,
                    currentWeek = currentWeek,
                    now = now,
                    zoneId = zoneId,
                    reminderMinutes = settings.reminderMinutes,
                    sectionTimes = settings.sectionTimes
                )
            ) return@executeIfActive
            val dedupeKey = "${key.profileId}_${key.courseId}_${key.occurrenceDate}_${key.kind.name}"
            if (shouldSkipDuplicate(context, dedupeKey, now.toEpochMilli())) return@executeIfActive
            NotificationHelper.showCourseReminder(
                context = context,
                courseName = course.name,
                location = course.location,
                notificationIdSeed = buildStableNotificationSeed(course.id, key.occurrenceDate.toEpochDay())
            )
        }
    }

    /** 判断同一 occurrence 是否在两分钟内已显示过。 */
    private fun shouldSkipDuplicate(context: Context, key: String, nowMillis: Long): Boolean {
        val preferences = context.getSharedPreferences("dc_reminder_dedupe", Context.MODE_PRIVATE)
        val lastMillis = preferences.getLong(key, 0L)
        if (nowMillis - lastMillis in 0 until 2 * 60 * 1000L) return true
        preferences.edit().putLong(key, nowMillis).apply()
        return false
    }

    /** 生成不受 Long.MIN_VALUE 绝对值溢出影响的通知种子。 */
    private fun buildStableNotificationSeed(courseId: Long, epochDay: Long): Long =
        epochDay * 100_000L + Math.floorMod(courseId, 100_000L)

}
