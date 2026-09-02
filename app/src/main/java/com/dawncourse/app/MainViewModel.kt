package com.dawncourse.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import com.dawncourse.core.data.repository.StartupSnapshotRuntime
import com.dawncourse.core.domain.model.createStartupSnapshot
import com.dawncourse.feature.timetable.notification.MuteRecoveryUserActionController
import com.dawncourse.feature.timetable.notification.MuteSessionRecord
import com.dawncourse.core.domain.model.TriggerKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 主界面 UI 状态
 */
sealed interface MainUiState {
    data object Loading : MainUiState
    data class Success(
        val settings: AppSettings,
        val scheduleRevision: ScheduleRevision,
        /** Ready 后构建整包启动快照所需的稳定实时聚合。 */
        val activeTimetableContext: ActiveTimetableContext? = null,
        val activeCourses: List<Course> = emptyList(),
    ) : MainUiState
}

/**
 * 仅包含会影响提醒、静音或课程状态通知的设置快照。
 */
data class ScheduleSettingsRevision(
    val enableClassReminder: Boolean,
    val reminderMinutes: Int,
    val enablePersistentNotification: Boolean,
    val enableAutoMute: Boolean,
    val sectionTimes: List<SectionTime>
)

/**
 * 当前学期中会影响系统调度的字段快照。
 */
data class ScheduleSemesterRevision(
    val id: Long,
    val startDate: Long,
    val weekCount: Int
)

/**
 * 单门课程中会影响提醒内容或状态边界的字段快照。
 */
data class ScheduleCourseRevision(
    val id: Long,
    val semesterId: Long,
    val name: String,
    val location: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val duration: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int
)

/**
 * MainActivity 监听的稳定课程调度版本。
 *
 * 视觉、同步等无关设置不会改变该对象；课程输入顺序也会被稳定排序消除。
 */
data class ScheduleRevision(
    /** 真实活动课表身份，保证两个空课表之间的切换也会触发系统 Surface 对账。 */
    val profileId: Long?,
    val settings: ScheduleSettingsRevision,
    val semester: ScheduleSemesterRevision?,
    val courses: List<ScheduleCourseRevision>
) {
    /** 任一系统课程功能是否开启。 */
    val hasEnabledSystemSchedule: Boolean
        get() = settings.enableClassReminder ||
            settings.enablePersistentNotification ||
            settings.enableAutoMute

    companion object {
        /**
         * 从完整领域对象收敛出稳定且最小的调度版本。
         */
        fun create(
            settings: AppSettings,
            semester: Semester?,
            courses: List<Course>,
            profileId: Long? = semester?.profileId
        ): ScheduleRevision = ScheduleRevision(
            profileId = profileId,
            settings = ScheduleSettingsRevision(
                enableClassReminder = settings.enableClassReminder,
                reminderMinutes = settings.reminderMinutes,
                enablePersistentNotification = settings.enablePersistentNotification,
                enableAutoMute = settings.enableAutoMute,
                sectionTimes = settings.sectionTimes
            ),
            semester = semester?.let { value ->
                ScheduleSemesterRevision(
                    id = value.id,
                    startDate = value.startDate,
                    weekCount = value.weekCount
                )
            },
            courses = courses.map { course ->
                ScheduleCourseRevision(
                    id = course.id,
                    semesterId = course.semesterId,
                    name = course.name,
                    location = course.location,
                    dayOfWeek = course.dayOfWeek,
                    startSection = course.startSection,
                    duration = course.duration,
                    startWeek = course.startWeek,
                    endWeek = course.endWeek,
                    weekType = course.weekType
                )
            }.sortedWith(
                compareBy<ScheduleCourseRevision>(
                    { course -> course.id },
                    { course -> course.dayOfWeek },
                    { course -> course.startSection }
                )
            )
        )
    }
}

/**
 * 同一次 Flow 发射中原子配对的当前学期与其课程。
 */
data class ActiveSemesterSchedule(
    val profileId: Long?,
    val semester: Semester?,
    val courses: List<Course>,
    val activeTimetableContext: ActiveTimetableContext? = null,
)

/**
 * 使用单次当前学期收集切换课程 Flow，避免 `combine` 发射“新学期 + 旧课程”的瞬时组合。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun pairActiveSemesterSchedule(
    activeContext: Flow<ActiveTimetableContext?>,
    coursesBySemester: (Long) -> Flow<List<Course>>
): Flow<ActiveSemesterSchedule> = activeContext.flatMapLatest { context ->
    val semester = context?.semester
    if (context == null || semester == null) {
        flowOf(
            ActiveSemesterSchedule(
                profileId = context?.profile?.id,
                semester = null,
                courses = emptyList(),
                activeTimetableContext = context,
            )
        )
    } else {
        coursesBySemester(semester.id).map { courses ->
            ActiveSemesterSchedule(
                profileId = context.profile.id,
                semester = semester,
                courses = courses,
                activeTimetableContext = context,
            )
        }
    }
}

/** 将课程映射和稳定排序放到默认计算调度器，避免首次订阅占用主线程。 */
internal fun scheduleRevisionFlow(
    settings: Flow<AppSettings>,
    activeSemesterSchedule: Flow<ActiveSemesterSchedule>,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<MainUiState> = combine(settings, activeSemesterSchedule) { currentSettings, activeSchedule ->
    MainUiState.Success(
        settings = currentSettings,
        scheduleRevision = ScheduleRevision.create(
            settings = currentSettings,
            semester = activeSchedule.semester,
            courses = activeSchedule.courses,
            profileId = activeSchedule.profileId,
        ),
        activeTimetableContext = activeSchedule.activeTimetableContext,
        activeCourses = activeSchedule.courses,
    )
}.flowOn(computationDispatcher)

/**
 * 主 Activity 的 ViewModel
 *
 * 负责为 MainActivity 提供应用级别的状态，例如全局主题设置。
 *
 * @property settingsRepository 设置仓库，用于获取应用设置
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    profileRepository: TimetableProfileRepository,
    courseRepository: CourseRepository,
    private val muteRecoveryController: MuteRecoveryUserActionController,
    private val startupSnapshotRuntime: StartupSnapshotRuntime,
    private val widgetUpdateRepository: WidgetUpdateRepository,
) : ViewModel() {
    init {
        // 进程重建时从持久状态补齐专用恢复 Work；不依赖通知权限或触发器注册表。
        viewModelScope.launch {
            muteRecoveryController.reconcilePersistedState()
        }
    }

    /**
     * 全局应用设置状态流
     *
     * 用于在应用启动时应用用户偏好的主题（如动态取色、字体等）。
     * 使用 MainUiState 包装，以便在数据加载完成前保持启动画面。
     */
    private val activeSemesterSchedule = pairActiveSemesterSchedule(
        activeContext = profileRepository.observeActiveContext(),
        coursesBySemester = courseRepository::getCoursesBySemester
    )

    val uiState: StateFlow<MainUiState> = scheduleRevisionFlow(
        settings = settingsRepository.settings,
        activeSemesterSchedule = activeSemesterSchedule,
    )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MainUiState.Loading
        )

    /** 即使通知权限被拒绝，应用前台仍持续观察需要用户处理的静音责任。 */
    val exhaustedMuteRecoveries: StateFlow<List<MuteSessionRecord>> =
        muteRecoveryController.observeExhaustedRecords().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** DND 权限已由 UI 检查后，重置有限次数并提交一次立即恢复。 */
    fun retryMuteRecovery(key: TriggerKey) {
        viewModelScope.launch {
            muteRecoveryController.retry(key)
        }
    }

    /** 用户确认已手动恢复或放弃应用责任。 */
    fun releaseMuteRecovery(key: TriggerKey) {
        viewModelScope.launch {
            muteRecoveryController.release(key)
        }
    }

    /**
     * 实时 Root 已取得完整且同一次发射的 Profile、学期、课程、视觉设置后，异步原子替换
     * 冷启动文件。失败仅放弃下一次加速，绝不影响当前 UI 或数据库状态。
     */
    suspend fun refreshStartupSnapshot(successState: MainUiState.Success) {
        val context = successState.activeTimetableContext ?: return
        val snapshot = try {
            withContext(Dispatchers.Default) {
                createStartupSnapshot(
                    activeContext = context,
                    courses = successState.activeCourses,
                    settings = successState.settings,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    zoneId = java.time.ZoneId.systemDefault().id,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        startupSnapshotRuntime.replaceLatest(snapshot) {
            // Widget 更新独立于快照持久化，广播异常只能放弃本次旁路同步。
            runCatching { widgetUpdateRepository.triggerUpdate() }
        }
    }
}
