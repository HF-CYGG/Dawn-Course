package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerPrecision
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
        /**
         * 补投广播携带的原始闹钟精度 extra（[TriggerPrecision] 名）。
         *
         * TriggerReadinessRetryWorker 在数据库就绪后补投时带上；此时启动对账可能已把
         * 该 occurrence 从注册表删除，Receiver 优先用它判定非精确迟到宽限。
         */
        const val EXTRA_TRIGGER_PRECISION = "com.dawncourse.extra.TRIGGER_PRECISION"
        private const val TAG = "ReminderReceiver"

        /** goAsync 窗口内可等待数据库就绪的上限；避免进程刚被拉起时因 STARTING 直接丢弃闹钟。 */
        private const val DATABASE_READY_AWAIT_TIMEOUT_MS = 8_000L

        /**
         * 非精确闹钟的迟到宽限（分钟）。
         *
         * 缺少 SCHEDULE_EXACT_ALARM 权限时 AlarmManager 会降级到 setAndAllowWhileIdle/set，
         * 可能被系统批处理到课程开始之后。此时若仍以 courseStart 硬截止，这些提醒会被静默
         * 丢弃。对记录为 INEXACT 的触发器放宽到 courseStart 之后一段时间仍可投递。
         */
        private const val INEXACT_REMINDER_LATENESS_GRACE_MINUTES = 15
    }

    /** Receiver 在应用单例组件中需要的领域依赖。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        /** 在解析数据库 Repository 前检查启动状态。 */
        fun operationalDataGate(): OperationalDataGate
        /** 启动窗口内没等到数据库就绪时，改由持久任务补投的调度器。 */
        fun triggerReadinessRetryScheduler(): TriggerReadinessRetryScheduler
        /** 读取本 occurrence 下发时记录的实际精度，用于决定迟到宽限。 */
        fun scheduledTriggerRegistry(): ScheduledTriggerRegistry
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
        val precisionHint = intent.getStringExtra(EXTRA_TRIGGER_PRECISION)?.let { name ->
            TriggerPrecision.entries.firstOrNull { it.name == name }
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliverIfStillValid(context, key, precisionHint)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.e(TAG, "课程提醒广播处理失败: ${failure.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 触发时重新校验开关、当前学期、课程与 occurrence 日期。
     *
     * [precisionHint] 来自补投广播 extra：数据库就绪后启动对账可能已删掉注册表记录，
     * 此时优先用它判定非精确迟到宽限，避免有效的非精确提醒被零宽限丢弃。
     */
    private suspend fun deliverIfStillValid(
        context: Context,
        key: TriggerKey,
        precisionHint: TriggerPrecision?
    ) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )
        val readiness = entryPoint.operationalDataGate()
            .awaitReadiness(DATABASE_READY_AWAIT_TIMEOUT_MS)
        if (readiness != OperationalDataReadiness.READY) {
            // 一次性闹钟已被系统消费且无自身重试。数据库仍在启动（STARTING）或需要前台
            // 恢复（RECOVERY_REQUIRED）时，都把完整 Key 交给 WorkManager 持久重试，就绪后
            // 再按同一显式 Intent 补投；启动对账只重排 triggerAt > now 的触发器，无法恢复
            // 已错过但仍有效的本次提醒。随任务保存下发精度：注册表记录可能在补投前被对账
            // 清除，届时只能靠它判定迟到宽限。
            val precision = precisionHint ?: runCatching { entryPoint.scheduledTriggerRegistry().read() }
                .getOrNull()?.records?.firstOrNull { it.key == key }?.precision
            entryPoint.triggerReadinessRetryScheduler().enqueue(key, precision)
            return
        }
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
            // 下发时记录为 INEXACT 的触发器可能被系统批处理到课程开始之后，放宽迟到宽限，
            // 避免无精确闹钟权限的用户在 Doze 下稳定漏提醒。优先用补投 extra 里的精度，
            // 其次读注册表；两者都拿不到时按精确窗口处理。
            val scheduledPrecision = precisionHint
                ?: runCatching { entryPoint.scheduledTriggerRegistry().read() }
                    .getOrNull()?.records?.firstOrNull { it.key == key }?.precision
            val latenessGraceMinutes = if (scheduledPrecision == TriggerPrecision.INEXACT) {
                INEXACT_REMINDER_LATENESS_GRACE_MINUTES
            } else {
                0
            }
            if (!TriggerOccurrencePolicy.isInReminderWindow(
                    course = course,
                    occurrenceDate = key.occurrenceDate,
                    currentWeek = currentWeek,
                    now = now,
                    zoneId = zoneId,
                    reminderMinutes = settings.reminderMinutes,
                    sectionTimes = settings.sectionTimes,
                    latenessGraceMinutes = latenessGraceMinutes
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
