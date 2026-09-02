package com.dawncourse.feature.timetable.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import com.dawncourse.core.domain.usecase.GenerateTriggerHorizonUseCase
import com.dawncourse.feature.timetable.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 每日闹钟重建的进程级串行锁。
 *
 * 周期 Worker 与系统事件即时 Worker 使用不同唯一任务名，WorkManager 可能并发执行；
 * 该锁确保两者不会交错对账同一批 TriggerKey。
 */
internal object DailySchedulerExecutionLock {
    private val mutex = Mutex()

    /**
     * 在唯一临界区内执行闹钟 Desired/Scheduled 对账。
     */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}

/**
 * 每日调度任务 (WorkManager)
 *
 * 负责在每天凌晨（或应用启动时）计算当天的课程，并设置精确的闹钟提醒。
 * 包含两个主要功能：
 * 1. 上课提醒 (Reminder)
 * 2. 自动静音 (Auto Mute)
 */
@HiltWorker
class DailySchedulerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val operationalDataGate: OperationalDataGate,
    private val courseRepository: Lazy<CourseRepository>,
    private val settingsRepository: Lazy<SettingsRepository>,
    private val timetableProfileRepository: Lazy<TimetableProfileRepository>,
    private val calculateWeekUseCase: CalculateWeekUseCase,
    private val generateTriggerHorizonUseCase: GenerateTriggerHorizonUseCase,
    private val triggerReconciler: TriggerReconciler,
    private val appMuteSessionStore: AppMuteSessionStore,
    private val muteRecoveryController: MuteRecoveryUserActionController,
    private val triggerReadinessRetryScheduler: TriggerReadinessRetryScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = DailySchedulerExecutionLock.withLock {
        doWorkLocked()
    }

    /**
     * 在进程级串行锁内读取课程并重建今日闹钟。
     */
    private suspend fun doWorkLocked(): Result {
        when (operationalDataGate.readiness()) {
            OperationalDataReadiness.STARTING -> return Result.retry()
            OperationalDataReadiness.RECOVERY_REQUIRED -> return Result.success()
            OperationalDataReadiness.READY -> Unit
        }
        var shouldRetry = false
        try {
            // 开机/升级/每日保底只补齐专用按 Key Worker，不再借 force replay 间接恢复。
            muteRecoveryController.reconcilePersistedState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "静音恢复状态对账失败: ${failure.javaClass.simpleName}")
            shouldRetry = true
        }
        try {
            // Receiver 先写 journal 再等待 WorkManager；异步入队失败或进程中断后，
            // 下一次启动/系统事件/每日对账在此恢复已经被系统消费的一次性触发器。
            if (!triggerReadinessRetryScheduler.reconcilePending()) {
                shouldRetry = true
                Log.w(TAG, "触发就绪补投 journal 对账未完全入队")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "触发就绪补投 journal 对账失败: ${failure.javaClass.simpleName}")
            shouldRetry = true
        }
        val snapshot = try {
            val settings = settingsRepository.get().settings.first()
            val now = Instant.now()
            val zoneId = ZoneId.systemDefault()
            // 先捕获同一 Flow 发射的 Profile + Semester，再读取课程；完成后复核，
            // Profile 切换竞态时宁可交给下一次 reconcile，也不能注册旧课表的 Desired。
            val activeContext = timetableProfileRepository.get().observeActiveContext().first()
            val semester = activeContext?.semester
            val courses = semester?.let { value ->
                courseRepository.get().getCoursesBySemester(value.id).first()
            }.orEmpty()
            val verifiedContext = timetableProfileRepository.get().observeActiveContext().first()
            if (activeContext?.profile?.id != verifiedContext?.profile?.id ||
                activeContext?.semester?.id != verifiedContext?.semester?.id
            ) {
                return Result.retry()
            }
            SchedulingSnapshot(
                settings = settings,
                now = now,
                zoneId = zoneId,
                profileId = activeContext?.profile?.id,
                semester = semester,
                courses = courses,
                currentWeek = semester?.let { value ->
                    calculateWeekUseCase(value.startDate, now.toEpochMilli())
                } ?: 0
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "课程调度快照读取失败: ${failure.javaClass.simpleName}")
            return Result.retry()
        }

        val desired = try {
            val profileId = snapshot.profileId
            val semester = snapshot.semester
            if (profileId == null || semester == null) {
                emptyList()
            } else {
                generateTriggerHorizonUseCase(
                    profileId = profileId,
                    firstDate = snapshot.now.atZone(snapshot.zoneId).toLocalDate(),
                    dayCount = TRIGGER_HORIZON_DAYS,
                    now = snapshot.now,
                    zoneId = snapshot.zoneId,
                    semesterStartDateMillis = semester.startDate,
                    semesterWeekCount = semester.weekCount,
                    courses = snapshot.courses,
                    settings = snapshot.settings
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "触发器生成失败: ${failure.javaClass.simpleName}")
            shouldRetry = true
            null
        }
        val replayJournal = AppSystemScheduleReplayJournal(applicationContext)
        // 捕获本轮代际；读取异常时仍强制幂等重放，但不允许清除未知或更新后的责任。
        val replayClaim = captureSystemScheduleReplayClaim(replayJournal)
        val forceReplay = shouldForceReplay(
            inputForceReplay = inputData.getBoolean(ReminderScheduler.INPUT_FORCE_REPLAY, false),
            markerPending = replayClaim.isPending,
        )
        var triggerReconciled = false
        if (desired != null) {
            try {
                val muteSessionRecords = appMuteSessionStore.records()
                triggerReconciler.reconcile(
                    desired = desired,
                    forceReplay = forceReplay,
                    protectedUnmuteKeys = muteSessionRecords.mapTo(mutableSetOf()) { record -> record.key },
                    retainedUnmuteAlarmKeys = muteSessionRecords
                        .filter { record -> record.status == MuteSessionStatus.ACTIVE }
                        .mapTo(mutableSetOf()) { record -> record.key }
                )
                triggerReconciled = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "系统触发器对账失败: ${failure.javaClass.simpleName}")
                shouldRetry = true
            }
        }

        if (!acknowledgeSystemScheduleReplay(
                journal = replayJournal,
                claim = replayClaim,
                triggerReconciled = triggerReconciled,
            )
        ) {
            // 清理失败只会造成下一次幂等重放；当前 Worker 仍请求 retry，避免把责任静默遗留。
            shouldRetry = true
            Log.w(TAG, "系统事件 force replay marker 清理失败")
        }

        val surfaceRefreshJournal = AppCourseSurfaceRefreshJournal(applicationContext)
        // 捕获本轮 Surface 责任代际；并发到达的新边界会因 compare-and-clear 保留下来。
        val surfaceRefreshClaim = captureCourseSurfaceRefreshClaim(surfaceRefreshJournal)
        var surfaceRefreshed = false
        try {
            if (snapshot.settings.enablePersistentNotification) {
                // 不预先取消旧刷新；新通知与下一边界成功发布后由同一 PendingIntent 身份覆盖。
                refreshCourseStatusNotification(
                    semester = snapshot.semester,
                    currentWeek = snapshot.currentWeek,
                    courses = snapshot.courses,
                    sectionTimes = snapshot.settings.sectionTimes,
                    now = snapshot.now,
                    zoneId = snapshot.zoneId
                )
            } else {
                PersistentNotificationRefreshScheduler.cancel(applicationContext)
                NotificationHelper.cancelCourseStatus(applicationContext)
            }
            surfaceRefreshed = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "课程状态 Surface 刷新失败: ${failure.javaClass.simpleName}")
            shouldRetry = true
        }
        if (!acknowledgeCourseSurfaceRefresh(
                journal = surfaceRefreshJournal,
                claim = surfaceRefreshClaim,
                surfaceRefreshed = surfaceRefreshed,
            )
        ) {
            shouldRetry = true
            Log.w(TAG, "课程状态 Surface refresh marker 清理失败")
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }

    /**
     * 生成课程状态通知，并只在下一真实边界或本地午夜安排一次刷新。
     */
    private fun refreshCourseStatusNotification(
        semester: Semester?,
        currentWeek: Int,
        courses: List<Course>,
        sectionTimes: List<com.dawncourse.core.domain.model.SectionTime>,
        now: Instant,
        zoneId: ZoneId
    ) {
        val initialPlan = PersistentNotificationPlanResolver.resolve(
            now = now,
            zoneId = zoneId,
            currentWeek = currentWeek,
            weekCount = semester?.weekCount ?: 0,
            courses = courses,
            sectionTimes = sectionTimes
        )
        // 数据读取、格式化与系统调度可能正好跨过课程边界；发布前用新时钟至多重算一次，
        // 避免先展示已过期状态，再因过期 Alarm 被取消后永久失去刷新入口。
        val plan = PersistentNotificationPlanResolver.recalculateForPublication(
            initialPlan = initialPlan,
            publicationNow = Instant.now(),
            zoneId = zoneId,
            currentWeek = currentWeek,
            weekCount = semester?.weekCount ?: 0,
            courses = courses,
            sectionTimes = sectionTimes
        )
        val titleAndContent = when {
            semester == null -> {
                applicationContext.getString(R.string.course_status_no_semester_title) to
                    applicationContext.getString(R.string.course_status_no_semester_content)
            }
            currentWeek !in 1..semester.weekCount -> {
                applicationContext.getString(R.string.course_status_out_of_semester_title) to
                    applicationContext.getString(R.string.course_status_out_of_semester_content)
            }
            plan.status == PersistentCourseStatus.IN_CLASS -> {
                val current = plan.currentCourses.firstOrNull()
                if (current == null) {
                    noCoursesText()
                } else {
                    val label = formatCourseLabel(plan.currentCourses)
                    val location = current.course.location.ifBlank {
                        applicationContext.getString(R.string.course_status_location_unknown)
                    }
                    applicationContext.getString(R.string.course_status_in_class_title, label) to
                        applicationContext.getString(
                            R.string.course_status_in_class_content,
                            formatTime(current.endAt, zoneId),
                            location
                        )
                }
            }
            plan.status == PersistentCourseStatus.UPCOMING -> {
                val next = plan.nextCourses.firstOrNull()
                if (next == null) {
                    noCoursesText()
                } else {
                    val label = formatCourseLabel(plan.nextCourses)
                    val location = next.course.location.ifBlank {
                        applicationContext.getString(R.string.course_status_location_unknown)
                    }
                    applicationContext.getString(R.string.course_status_upcoming_title, label) to
                        applicationContext.getString(
                            R.string.course_status_upcoming_content,
                            formatTime(next.startAt, zoneId),
                            location
                        )
                }
            }
            plan.status == PersistentCourseStatus.FINISHED -> {
                applicationContext.getString(R.string.course_status_finished_title) to
                    applicationContext.getString(R.string.course_status_finished_content)
            }
            else -> noCoursesText()
        }

        NotificationHelper.showCourseStatus(
            context = applicationContext,
            title = titleAndContent.first,
            content = titleAndContent.second
        )
        PersistentNotificationRefreshScheduler.schedule(applicationContext, plan.nextRefreshAt)
    }

    /**
     * 返回“今天无课”的资源化展示文案。
     */
    private fun noCoursesText(): Pair<String, String> =
        applicationContext.getString(R.string.course_status_no_courses_title) to
            applicationContext.getString(R.string.course_status_no_courses_content)

    /**
     * 使用领域层稳定顺序选择主课程，并显式展示被折叠的课程数量。
     */
    private fun formatCourseLabel(courses: List<PersistentCourseOccurrence>): String {
        val primaryName = courses.firstOrNull()?.course?.name.orEmpty()
        val hiddenCount = (courses.size - 1).coerceAtLeast(0)
        return if (hiddenCount == 0) {
            primaryName
        } else {
            applicationContext.getString(
                R.string.course_status_course_with_more,
                primaryName,
                hiddenCount
            )
        }
    }

    /**
     * 以当前系统时区格式化课程边界。
     */
    private fun formatTime(value: Instant, zoneId: ZoneId): String =
        value.atZone(zoneId).toLocalTime().format(STATUS_TIME_FORMATTER)

    private companion object {
        /** 课程状态通知使用的固定 24 小时时间格式。 */
        val STATUS_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val TRIGGER_HORIZON_DAYS = 2
        const val TAG = "DailySchedulerWorker"
    }

    private data class SchedulingSnapshot(
        val settings: com.dawncourse.core.domain.model.AppSettings,
        val now: Instant,
        val zoneId: ZoneId,
        val profileId: Long?,
        val semester: Semester?,
        val courses: List<Course>,
        val currentWeek: Int
    )
}
