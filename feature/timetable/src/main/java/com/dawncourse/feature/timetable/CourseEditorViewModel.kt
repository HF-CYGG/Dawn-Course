package com.dawncourse.feature.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import com.dawncourse.core.domain.util.runSuspendCatching
import com.dawncourse.core.domain.usecase.DetectConflictUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 课程编辑器 ViewModel
 *
 * 负责管理课程编辑界面的状态，处理数据加载和保存逻辑。
 *
 * @property repository 课程数据仓库
 * @property detectConflictUseCase 冲突检测用例（暂未使用，预留）
 * @property savedStateHandle 用于获取导航参数 courseId
 */
@HiltViewModel
class CourseEditorViewModel @Inject constructor(
    private val repository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val timetableProfileRepository: TimetableProfileRepository,
    private val detectConflictUseCase: DetectConflictUseCase,
    private val widgetUpdateRepository: WidgetUpdateRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val courseId: Long = savedStateHandle.get<String>("courseId")?.toLongOrNull() ?: 0L

    private val _course = MutableStateFlow<Course?>(null)
    val course: StateFlow<Course?> = _course.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    /** 加载或作用域读取失败的固定可见提示；不暴露异常详情。 */
    val operationError: StateFlow<String?> = _operationError.asStateFlow()
    fun onOperationErrorShown() { _operationError.value = null }

    /** 编辑既有课程时捕获的课表身份，切换课表后不得继续写入旧学期。 */
    private val editingProfileId = MutableStateFlow<Long?>(null)

    /** 当前 Room 学期；null 表示用户尚未选择或选择已失效。 */
    val currentSemester: StateFlow<Semester?> = timetableProfileRepository.observeActiveContext()
        .map { context -> context?.semester }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { _operationError.value = EDITOR_OPERATION_FAILURE_MESSAGE; emit(null) } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 当前学期 ID；0 表示没有有效选择。 */
    val currentSemesterId: StateFlow<Long> = currentSemester
        .map { semester -> semester?.id ?: 0L }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L
        )

    /** 当前学期周数，编辑界面不得再读取 AppSettings 的旧缓存。 */
    val currentSemesterWeekCount: StateFlow<Int> = currentSemester
        .map { semester -> semester?.weekCount ?: DEFAULT_SEMESTER_WEEK_COUNT }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DEFAULT_SEMESTER_WEEK_COUNT
        )

    /** UI 保存能力必须基于目标学期仍真实存在于 Room，而不只是 ID 为正数。 */
    val hasValidTargetSemester: StateFlow<Boolean> = combine(
        _course,
        currentSemester,
        semesterRepository.getAllSemesters().catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { _operationError.value = EDITOR_OPERATION_FAILURE_MESSAGE; emit(emptyList()) } else throw failure
        }
    ) { editingCourse, selectedSemester, semesters ->
        val targetId = if (courseId > 0L) editingCourse?.semesterId else selectedSemester?.id
        targetId != null && targetId > 0L && semesters.any { it.id == targetId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    private val _conflictCourses = MutableStateFlow<List<Course>>(emptyList())
    val conflictCourses: StateFlow<List<Course>> = _conflictCourses.asStateFlow()

    init {
        if (courseId != 0L) {
            viewModelScope.launch {
                val activeContext = runSuspendCatching {
                    timetableProfileRepository.getActiveContext()
                }.getOrElse { _operationError.value = EDITOR_OPERATION_FAILURE_MESSAGE; return@launch }
                val loadedCourse = runSuspendCatching {
                    repository.getCourseById(courseId)
                }.getOrElse { _operationError.value = EDITOR_OPERATION_FAILURE_MESSAGE; return@launch }
                if (loadedCourse != null && activeContext?.semester?.id == loadedCourse.semesterId) {
                    editingProfileId.value = activeContext.profile.id
                    _course.value = loadedCourse
                }
            }
        }
    }

    /**
     * 发送小组件更新广播
     */
    private fun sendWidgetUpdateBroadcast() {
        widgetUpdateRepository.triggerUpdate()
    }

    /**
     * 保存课程
     *
     * @param course 要保存的课程对象
     * @param onSaved 保存完成后的回调
     */
    fun saveCourse(course: Course, onSaved: () -> Unit) {
        saveCourses(listOf(course), onSaved = onSaved, onConflict = {})
    }

    /**
     * 保存多条课程片段，并执行冲突检测与数据库更新
     *
     * 核心逻辑：
     * 1. 根据 semesterId 查询当前学期下的所有现有课程。
     * 2. 如果处于编辑模式（有有效 ID），则在冲突检测时忽略自身及其分裂出的其他记录。
     * 3. 逐个检测目标课程片段与现有课程是否冲突（按星期、周次区间、节次区间交叉判断）。
     * 4. 若有冲突，通过回调抛出冲突信息，并中断保存。
     * 5. 若无冲突，继承原课程的关联信息（originId/isModified/note），执行保存。
     * 6. 若编辑时被拆分成多个时间段，则先删除原记录，再作为新记录插入，以保证数据完整性。
     *
     * @param courses 待保存的课程片段列表（如一个课程分成了 1-8 周和 10-16 周两个片段）
     * @param onSaved 保存成功后的回调
     * @param onConflict 发生冲突时的回调，包含结构化的冲突提示文本
     */
    fun saveCourses(
        courses: List<Course>,
        onSaved: () -> Unit,
        onConflict: (String) -> Unit
    ) {
        if (courses.isEmpty()) {
            onConflict("未选择任何周次，无法保存课程")
            return
        }
        viewModelScope.launch {
            val semesterId = courses.first().semesterId
            val capturedProfileId = editingProfileId.value
                ?: runSuspendCatching {
                    timetableProfileRepository.getActiveContext()?.profile?.id
                }.getOrNull()
                ?: run {
                    onConflict(PROFILE_SCOPE_CHANGED_MESSAGE)
                    return@launch
                }
            if (!isTargetStillActive(semesterId, capturedProfileId)) {
                onConflict(PROFILE_SCOPE_CHANGED_MESSAGE)
                return@launch
            }
            val semester = semesterId.takeIf { it > 0L }
                ?.let { id -> runSuspendCatching { semesterRepository.getSemesterById(id) }.getOrNull() }
            CourseSaveSemesterValidator.validate(courses, semester)?.let { message ->
                onConflict(message)
                return@launch
            }
            val existingCourses = runSuspendCatching {
                repository.getCoursesBySemester(semesterId).first()
            }.getOrElse {
                onConflict(EDITOR_OPERATION_FAILURE_MESSAGE)
                return@launch
            }
            val editingIds = courses.map { it.id }.filter { it != 0L }.toSet()
            val filteredExisting = if (editingIds.isEmpty()) {
                existingCourses
            } else {
                existingCourses.filter { it.id !in editingIds }
            }

            val conflictList = mutableListOf<Course>()
            courses.forEach { target ->
                val conflicts = detectConflictUseCase(target, filteredExisting)
                if (conflicts.isNotEmpty()) {
                    conflictList.addAll(conflicts)
                }
            }

            if (conflictList.isNotEmpty()) {
                _conflictCourses.value = conflictList.distinctBy { it.id }
                onConflict(buildConflictMessage(conflictList))
                return@launch
            }

            val editingId = courses.firstOrNull { it.id != 0L }?.id ?: 0L
            val originalCourse = if (editingId != 0L) {
                runSuspendCatching { repository.getCourseById(editingId) }.getOrNull()
            } else {
                null
            }

            val toInsert = courses.map { course ->
                val originId = originalCourse?.originId ?: course.originId
                val isModified = originalCourse?.isModified ?: course.isModified
                val note = originalCourse?.note ?: course.note
                if (course.id == 0L) {
                    course.copy(originId = originId, isModified = isModified, note = note)
                } else {
                    course.copy(originId = originId, isModified = isModified, note = note)
                }
            }

            // 冲突检测与数据库提交之间也可能切换课表，提交前必须再次确认捕获的上下文。
            if (!isTargetStillActive(semesterId, capturedProfileId)) {
                onConflict(PROFILE_SCOPE_CHANGED_MESSAGE)
                return@launch
            }

            val result = runSuspendCatching { repository.saveCoursesIfScopeActive(
                profileId = capturedProfileId,
                semesterId = semesterId,
                courses = toInsert,
                editingCourseId = editingId,
            ) }.getOrElse {
                onConflict(EDITOR_OPERATION_FAILURE_MESSAGE)
                return@launch
            }
            when (result) {
                CourseRepository.AtomicSaveResult.Success -> {
                    completeSuccessfulCourseSave(
                        triggerWidgetUpdate = ::sendWidgetUpdateBroadcast,
                        onSaved = onSaved,
                    )
                }
                is CourseRepository.AtomicSaveResult.Rejected -> onConflict(result.message)
            }
        }
    }

    /**
     * 构造结构化的冲突提示信息
     *
     * 将有冲突的课程列表转换为可读的文字描述。
     * 例如：“课程时间冲突：周一 第1-16周 第3-4节课；周三 单周第5-12周 第1节课”
     *
     * @param conflicts 存在冲突的课程列表
     * @return 格式化后的提示文本
     */
    private fun buildConflictMessage(conflicts: List<Course>): String {
        val items = conflicts
            .distinctBy { it.id }
            .map { conflict ->
                val weekText = buildWeekRangeText(conflict)
                val sectionText = buildSectionRangeText(conflict)
                val dayText = buildDayText(conflict.dayOfWeek)
                if (weekText.isBlank()) {
                    "周$dayText $sectionText"
                } else {
                    "周$dayText $weekText $sectionText"
                }
            }
        return "课程时间冲突：${items.joinToString("；")}"
    }

    /**
     * 构造周次范围文本
     *
     * 处理单/双周类型，并将相同起止周次合并。
     * 例如：“第1周”、“第1-16周”、“单周第5-12周”
     */
    private fun buildWeekRangeText(course: Course): String {
        val range = if (course.startWeek == course.endWeek) {
            "第${course.startWeek}周"
        } else {
            "第${course.startWeek}-${course.endWeek}周"
        }
        val type = when (course.weekType) {
            Course.WEEK_TYPE_ODD -> "单周"
            Course.WEEK_TYPE_EVEN -> "双周"
            else -> ""
        }
        return if (type.isBlank()) range else "$type$range"
    }

    /**
     * 构造节次范围文本
     *
     * 例如：“第3节课”、“第3-4节课”
     */
    private fun buildSectionRangeText(course: Course): String {
        val end = course.startSection + course.duration - 1
        return if (course.startSection == end) {
            "第${course.startSection}节课"
        } else {
            "第${course.startSection}-${end}节课"
        }
    }

    /**
     * 构造星期文本
     *
     * 将数字 (1-7) 转换为中文字符 (一-日)
     */
    private fun buildDayText(day: Int): String {
        return when (day) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7 -> "日"
            else -> day.toString()
        }
    }

    /** 只允许把编辑结果写入开始编辑时仍处于活动状态的学期。 */
    private suspend fun isTargetStillActive(semesterId: Long, profileId: Long?): Boolean {
        if (profileId == null || semesterId <= 0L) return false
        val current = runSuspendCatching {
            timetableProfileRepository.getActiveContext()
        }.getOrElse { _operationError.value = EDITOR_OPERATION_FAILURE_MESSAGE; return false } ?: return false
        return current.profile.id == profileId && current.semester?.id == semesterId
    }

    companion object {
        /** 尚未加载学期时的安全展示周数。 */
        private const val DEFAULT_SEMESTER_WEEK_COUNT = 20
        /** Profile 切换后拒绝旧编辑会话的明确反馈。 */
        private const val PROFILE_SCOPE_CHANGED_MESSAGE = "课表已切换，请重新打开课程编辑"
        /** 读取或保存课程失败时的安全反馈。 */
        private const val EDITOR_OPERATION_FAILURE_MESSAGE = "操作未完成，请稍后重试"
    }
}

/** 编辑页导航回调仅能由原子提交成功触发。 */
internal fun shouldCompleteCourseSave(result: CourseRepository.AtomicSaveResult): Boolean =
    result is CourseRepository.AtomicSaveResult.Success

/** 数据已提交后，小组件广播属于非关键副作用；普通框架异常不能阻断成功回调。 */
internal suspend fun completeSuccessfulCourseSave(
    triggerWidgetUpdate: () -> Unit,
    onSaved: () -> Unit,
) {
    runSuspendCatching { triggerWidgetUpdate() }
    onSaved()
}
