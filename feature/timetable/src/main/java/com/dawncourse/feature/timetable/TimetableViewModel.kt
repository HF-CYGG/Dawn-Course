package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.util.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import com.dawncourse.core.domain.usecase.RunTimetableSyncUseCase
import com.dawncourse.core.domain.model.TimetableSyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import androidx.compose.runtime.Immutable

/**
 * 课表界面 UI 状态
 */
sealed interface TimetableUiState {
    /**
     * 加载中状态
     */
    @Immutable
    data object Loading : TimetableUiState

    /** 根数据源异常的明确安全错误态，不能伪装成空课表成功。 */
    @Immutable
    data object Error : TimetableUiState

    /**
     * 加载成功状态
     *
     * @property courses 当前学期的所有课程列表
     * @property currentWeek 当前周次 (1-20)
     * @property totalWeeks 学期总周数
     * @property semesterStartDate 学期开始日期
     */
    @Immutable
    data class Success(
        val courses: List<Course>,
        val currentWeek: Int,
        val totalWeeks: Int = 20,
        val semesterStartDate: LocalDate? = null
    ) : TimetableUiState
}

/** 根数据源异常时保持可渲染的显式错误态。 */
internal fun timetableFlowFailureState(): TimetableUiState = TimetableUiState.Error

internal fun recoverTimetableUiFlow(upstream: kotlinx.coroutines.flow.Flow<TimetableUiState>): kotlinx.coroutines.flow.Flow<TimetableUiState> =
    upstream.catch { failure ->
        if (failure is CancellationException) throw failure
        if (failure is Exception) emit(timetableFlowFailureState()) else throw failure
    }

/**
 * 课表功能 ViewModel
 *
 * 负责管理课表界面的 UI 状态、处理用户交互（如切换周次、删除课程、撤销操作）以及数据流的聚合。
 *
 * @property repository 课程数据仓库
 * @property timetableProfileRepository 活动课表与学期上下文仓库
 * @property calculateWeekUseCase 计算当前周次的用例
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModel @Inject constructor(
    private val repository: CourseRepository,
    private val timetableProfileRepository: TimetableProfileRepository,
    private val calculateWeekUseCase: CalculateWeekUseCase,
    private val runTimetableSyncUseCase: RunTimetableSyncUseCase,
    private val credentialsRepository: CredentialsRepository
) : ViewModel() {

    private var lastActiveScope: Pair<Long?, Long?>? = null

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    /** 用户操作异常的固定文案，不携带数据库或凭据详情。 */
    private fun reportOperationFailure() {
        _userMessage.value = "操作未完成，请稍后重试"
    }

    /**
     * 标记是否已自动滚动到当前周
     *
     * 默认为 false。当 UI 首次加载并滚动到当前周后置为 true。
     * 由于 ViewModel 在配置变更（如屏幕旋转）时会保留，因此该标志位能防止旋转后重复滚动。
     */
    var hasScrolledToCurrentWeek = false

    /**
     * 当前周次状态
     *
     * 用于在界面层展示当前周次，并支持手动更新周次。
     */
    private val _currentWeek = MutableStateFlow(1)

    /**
     * 时间跳动流
     *
     * 每分钟发射一次信号，用于驱动周次重新计算。
     * 解决应用长期后台驻留或跨天时，周次不更新的问题。
     */
    private val timeTicker = flow {
        emit(Unit) // 立即发射一次，确保初次加载
        while (true) {
            delay(60_000) // 每分钟检查一次
            emit(Unit)
        }
    }

    /**
     * 当前学期状态流
     *
     * 统一承载对当前学期的订阅，避免重复触发数据库查询。
     * 同时在数据变化时计算当前周次并更新 [_currentWeek]。
     * 结合 timeTicker，确保自然时间流逝也能触发周次更新。
     * 使用 stateIn 转换为热流，SharingStarted.WhileSubscribed(5000) 确保在配置变更时保持活跃。
     */
    private val currentSemesterFlow: Flow<com.dawncourse.core.domain.model.Semester?> =
        combine(
            timetableProfileRepository.observeActiveContext(),
            timeTicker
        ) { context, _: Unit ->
            context
        }
            .onEach { context ->
                val scope = context?.profile?.id to context?.semester?.id
                if (scope != lastActiveScope) {
                    lastActiveScope = scope
                    hasScrolledToCurrentWeek = false
                    // 作用域（Profile/学期）切换后，上一课表的删除撤销栈与待显示提示不再适用，
                    // 必须同时失效；否则用户切到另一课表再返回时点“撤销”，会把旧课表的课程
                    // 写回旧学期，而当前看到的是另一张课表。
                    invalidatePendingUndo()
                }
                val semester = context?.semester
                if (semester != null) {
                    // 根据学期开始日期计算当前周次
                    val week = calculateWeekUseCase(semester.startDate)
                    // 允许开学前显示 0 周，避免把“未开学”误判为第 1 周
                    val validWeek = week.coerceAtLeast(0)
                    _currentWeek.value = validWeek
                } else {
                    _currentWeek.value = 0
                }
            }
            .map { context -> context?.semester }

    /**
     * UI 状态流
     *
     * 组合了 [currentSemesterFlow], [coursesFlow], [_currentWeek] 等数据源。
     * 当任一数据源发生变化时，自动计算并生成最新的 UI 状态。
     * 这种响应式设计确保 UI 始终展示最新数据，无需手动刷新。
     */
    val uiState: StateFlow<TimetableUiState> = recoverTimetableUiFlow(currentSemesterFlow
        .flatMapLatest { semester ->
            // 如果学期存在，转换开始日期并获取该学期的课程流
            val startDate = semester?.startDate?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val totalWeeks = semester?.weekCount ?: 20
            val coursesFlow = semester?.id?.let { repository.getCoursesBySemester(it) } ?: flowOf(emptyList())

            // 组合课程数据和当前选择的周次
            combine(coursesFlow, _currentWeek) { courses, currentWeek ->
                val state: TimetableUiState = TimetableUiState.Success(
                    courses = courses,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    semesterStartDate = startDate
                )
                state
            }
        }
        )
        .onEach { if (it is TimetableUiState.Error) reportOperationFailure() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TimetableUiState.Loading
        )

    val boundProvider: StateFlow<SyncProviderType?> = timetableProfileRepository.observeActiveContext()
        .flatMapLatest { context ->
            context?.profile?.id?.let(credentialsRepository::observeBoundProvider) ?: flowOf(null)
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) {
                reportOperationFailure()
                emit(null)
            } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * 更新当前展示的周次
     *
     * @param week 目标周次
     */
    fun updateCurrentWeek(week: Int) {
        _currentWeek.update { week }
    }

    /**
     * 添加/更新课程
     *
     * 如果课程 ID 为 0，则执行插入；否则执行更新。
     *
     * @param course 课程对象
     */
    fun saveCourse(course: Course) {
        viewModelScope.launch {
            val result = runSuspendCatching {
                if (course.id == 0L) {
                    repository.insertCourse(course)
                } else {
                    repository.updateCourse(course)
                }
            }
            if (result.isFailure) {
                reportOperationFailure()
            }
        }
    }

    private data class DeletedCoursesUndo(
        val profileId: Long,
        val semesterId: Long,
        val courses: List<Course>,
    )

    // 删除操作的撤销栈，连同触发时的 Profile/学期作用域最多保存 5 步。
    private val deletedCoursesStack = ArrayDeque<DeletedCoursesUndo>()

    /**
     * 作用域切换时失效待处理的删除撤销：清空撤销栈并撤下仍在显示的删除提示。
     *
     * 仅在确实存在待撤销记录时才清理提示，避免误清其他无关的用户消息。
     * 与 currentSemesterFlow 的收集同在 viewModelScope 主调度器上执行，无需额外同步。
     */
    private fun invalidatePendingUndo() {
        if (deletedCoursesStack.isEmpty()) return
        deletedCoursesStack.clear()
        _userMessage.value = null
    }

    /**
     * 标记用户消息已显示
     */
    fun userMessageShown() {
        _userMessage.value = null
    }

    fun showUserMessage(message: String) {
        _userMessage.value = message
    }

    /**
     * 删除课程（支持撤销）
     *
     * 将待删除的课程存入撤销栈，然后从数据库中删除。
     *
     * @param courses 要删除的课程列表
     */
    fun deleteCoursesWithUndo(courses: List<Course>) {
        viewModelScope.launch {
            val (profileId, semesterId) = captureActiveScope(courses)
                ?: return@launch showUserMessage("活动课表已变化，请刷新后重试")
            val result = runSuspendCatching { repository.deleteCoursesIfScopeActive(
                profileId = profileId,
                semesterId = semesterId,
                courseIds = courses.mapTo(linkedSetOf()) { course -> course.id },
            ) }.getOrElse {
                reportOperationFailure()
                return@launch
            }
            when (result) {
                CourseRepository.AtomicSaveResult.Success -> {
                    deletedCoursesStack.addFirst(
                        DeletedCoursesUndo(profileId, semesterId, courses.toList())
                    )
                    if (deletedCoursesStack.size > 5) deletedCoursesStack.removeLast()
                    _userMessage.value = if (courses.size == 1) {
                        "课程已删除"
                    } else {
                        "已删除 ${courses.size} 个课程时段"
                    }
                }
                is CourseRepository.AtomicSaveResult.Rejected -> _userMessage.value = result.message
            }
        }
    }

    /**
     * 撤销上一次删除操作
     *
     * 从撤销栈中取出最近一次删除的课程列表，并将其重新插入数据库。
     */
    fun undoDelete() {
        val undo = deletedCoursesStack.firstOrNull() ?: return
        viewModelScope.launch {
            val result = runSuspendCatching { repository.restoreCoursesIfScopeActive(
                profileId = undo.profileId,
                semesterId = undo.semesterId,
                courses = undo.courses,
            ) }.getOrElse {
                reportOperationFailure()
                return@launch
            }
            when (result) {
                CourseRepository.AtomicSaveResult.Success -> {
                    if (deletedCoursesStack.firstOrNull() == undo) deletedCoursesStack.removeFirst()
                    _userMessage.value = "已撤销删除"
                }
                is CourseRepository.AtomicSaveResult.Rejected -> _userMessage.value = result.message
            }
        }
    }

    /**
     * 删除课程 (旧接口，保留兼容)
     *
     * @param course 要删除的课程对象
     */
    fun deleteCourse(course: Course) {
        deleteCoursesWithUndo(listOf(course))
    }

    /**
     * 自动更新课表
     *
     * 调用用例执行同步流程，并通过用户消息反馈结果。
     */
    fun syncNow() {
        viewModelScope.launch {
            val result = runSuspendCatching { runTimetableSyncUseCase() }.getOrElse {
                reportOperationFailure()
                return@launch
            }
            when (result) {
                is TimetableSyncResult.Success -> {
                    _userMessage.value = "课表更新完成"
                }
                is TimetableSyncResult.Failure -> {
                    _userMessage.value = "更新失败，请稍后重试"
                }
            }
        }
    }

    /**
     * 根据 ID 获取课程
     *
     * @param id 课程 ID
     * @return 课程对象，若不存在返回 null
     */
    suspend fun getCourse(id: Long): Course? {
        return runSuspendCatching { repository.getCourseById(id) }.getOrNull()
    }

    /**
     * 撤销调课
     *
     * 将分裂的课程记录合并回原状态。
     * 逻辑：
     * 1. 找到所有具有相同 originId 的课程记录（兄弟节点）。
     * 2. 收集它们覆盖的所有周次。
     * 3. 重新计算合并后的连续片段。
     * 4. 删除旧记录，插入合并后的新记录。
     *
     * @param course 触发撤销的课程对象
     */
    fun undoReschedule(course: Course) {
        // 如果是新创建且未分裂的课程，originId 可能为 0。
        // 分裂后的课程 originId 必定不为 0（指向原始 ID）。
        // 迁移后的旧课程 originId = id。
        val targetOriginId = if (course.originId == 0L) course.id else course.originId
        
        viewModelScope.launch {
            val (profileId, semesterId) = captureActiveScope(listOf(course))
                ?: return@launch showUserMessage("活动课表已变化，请刷新后重试")
            val result = runSuspendCatching { repository.undoRescheduleIfScopeActive(
                profileId = profileId,
                semesterId = semesterId,
                originId = targetOriginId,
            ) }.getOrElse {
                reportOperationFailure()
                return@launch
            }
            when (result) {
                CourseRepository.AtomicSaveResult.Success -> Unit
                is CourseRepository.AtomicSaveResult.Rejected -> _userMessage.value = result.message
            }
        }
    }

    private fun captureActiveScope(courses: List<Course>): Pair<Long, Long>? {
        if (courses.isEmpty()) return null
        val profileId = lastActiveScope?.first ?: return null
        val semesterId = lastActiveScope?.second ?: return null
        if (courses.any { course -> course.semesterId != semesterId || course.id <= 0L }) return null
        return profileId to semesterId
    }
}
