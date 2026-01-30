package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import androidx.compose.runtime.Immutable

sealed interface TimetableUiState {
    @Immutable
    data object Loading : TimetableUiState
    @Immutable
    data class Success(
        val courses: List<Course>,
        val currentWeek: Int,
        val totalWeeks: Int = 20,
        val semesterStartDate: LocalDate? = null
    ) : TimetableUiState
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModel @Inject constructor(
    private val repository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val calculateWeekUseCase: CalculateWeekUseCase
) : ViewModel() {

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
     * 当前学期状态流
     *
     * 统一承载对当前学期的订阅，避免重复触发数据库查询。
     * 同时在数据变化时计算当前周次并更新 [_currentWeek]。
     */
    private val currentSemesterFlow: StateFlow<com.dawncourse.core.domain.model.Semester?> =
        semesterRepository.getCurrentSemester()
            .onEach { semester ->
                if (semester != null) {
                    val week = calculateWeekUseCase(semester.startDate)
                    val validWeek = week.coerceIn(1, semester.weekCount)
                    _currentWeek.value = validWeek
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )

    val uiState: StateFlow<TimetableUiState> = currentSemesterFlow
        .flatMapLatest { semester ->
            val startDate = semester?.startDate?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val totalWeeks = semester?.weekCount ?: 20
            val coursesFlow = semester?.id?.let { repository.getCoursesBySemester(it) } ?: flowOf(emptyList())

            combine(coursesFlow, _currentWeek) { courses, currentWeek ->
                TimetableUiState.Success(
                    courses = courses,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    semesterStartDate = startDate
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TimetableUiState.Loading
        )

    fun updateCurrentWeek(week: Int) {
        _currentWeek.update { week }
    }

    /**
     * 添加测试课程
     *
     * 用于演示和测试数据库插入功能。
     * 在协程中调用 Repository 的 insert 方法。
     */
    fun saveCourse(course: Course) {
        viewModelScope.launch {
            if (course.id == 0L) {
                repository.insertCourse(course)
            } else {
                repository.updateCourse(course)
            }
        }
    }

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    private val deletedCoursesStack = ArrayDeque<List<Course>>()

    fun userMessageShown() {
        _userMessage.value = null
    }

    /**
     * 删除课程（支持撤销）
     *
     * @param courses 要删除的课程列表
     */
    fun deleteCoursesWithUndo(courses: List<Course>) {
        viewModelScope.launch {
            // 保存到撤销栈
            deletedCoursesStack.addFirst(courses)
            if (deletedCoursesStack.size > 5) {
                deletedCoursesStack.removeLast()
            }
            
            // 执行删除
            courses.forEach { repository.deleteCourse(it) }
            
            // 显示提示
            val message = if (courses.size == 1) {
                "课程已删除"
            } else {
                "已删除 ${courses.size} 个课程时段"
            }
            _userMessage.value = message
        }
    }

    /**
     * 撤销上一次删除操作
     */
    fun undoDelete() {
        val coursesToRestore = deletedCoursesStack.removeFirstOrNull() ?: return
        viewModelScope.launch {
            // 恢复课程 ID 为 0 以作为新记录插入，或者保留原 ID 如果是软删除？
            // Room 的 insert 策略是 REPLACE，如果保留原 ID 且 ID 未被占用，可以恢复。
            // 但如果这是自增 ID，删除后可能无法保证 ID 仍可用（虽然通常没问题）。
            // 安全起见，我们将 ID 设为 0 让数据库重新生成，或者尝试插入原对象。
            // 考虑到这是“撤销”，最好恢复原状。
            // 如果使用 insertCourses，ID 会被重置吗？如果对象有 ID，Room 会尝试使用它。
            // 我们尝试直接插入原对象。
            repository.insertCourses(coursesToRestore)
            _userMessage.value = "已撤销删除"
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
     * 根据 ID 获取课程
     *
     * @param id 课程 ID
     * @return 课程对象，若不存在返回 null
     */
    suspend fun getCourse(id: Long): Course? {
        return repository.getCourseById(id)
    }

    /**
     * 撤销调课
     * 将分裂的课程记录合并回原状态
     */
    fun undoReschedule(course: Course) {
        // 如果是新创建且未分裂的课程，originId 可能为 0。
        // 分裂后的课程 originId 必定不为 0（指向原始 ID）。
        // 迁移后的旧课程 originId = id。
        val targetOriginId = if (course.originId == 0L) course.id else course.originId
        
        viewModelScope.launch {
            val siblings = repository.getCoursesByOriginId(targetOriginId)
            // 如果只有一条记录且未修改，说明无需撤销
            if (siblings.size <= 1 && siblings.none { it.isModified }) return@launch
            
            // 找到“原始模板”：未修改的记录
            val template = siblings.firstOrNull { !it.isModified } ?: return@launch

            // 收集所有周次
            val allWeeks = siblings.flatMap { c ->
                val weeks = mutableSetOf<Int>()
                for (i in c.startWeek..c.endWeek) {
                    val match = when (c.weekType) {
                        Course.WEEK_TYPE_ODD -> i % 2 != 0
                        Course.WEEK_TYPE_EVEN -> i % 2 == 0
                        else -> true
                    }
                    if (match) weeks.add(i)
                }
                weeks
            }.toSet()

            // 计算合并后的片段
            val segments = convertToSegments(allWeeks)

            // 删除所有相关记录
            siblings.forEach { repository.deleteCourse(it) }

            // 插入合并后的新记录
            segments.forEach { (start, end, type) ->
                repository.insertCourse(template.copy(
                    id = 0,
                    startWeek = start,
                    endWeek = end,
                    weekType = type,
                    isModified = false,
                    note = "",
                    originId = 0 // 重置 originId
                ))
            }
        }
    }

    private fun convertToSegments(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.sorted()
        val segments = mutableListOf<Triple<Int, Int, Int>>()
        
        val pending = sorted.toMutableSet()
        while (pending.isNotEmpty()) {
            val first = pending.minOrNull()!!
            
            var endAll = first
            while (pending.contains(endAll + 1)) endAll++
            val countAll = endAll - first + 1
            
            var endParity = first
            val step = 2
            while (pending.contains(endParity + step)) endParity += step
            val countParity = (endParity - first) / 2 + 1
            
            if (countAll >= countParity) {
                segments.add(Triple(first, endAll, Course.WEEK_TYPE_ALL))
                for (i in first..endAll) pending.remove(i)
            } else {
                val type = if (first % 2 != 0) Course.WEEK_TYPE_ODD else Course.WEEK_TYPE_EVEN
                segments.add(Triple(first, endParity, type))
                var k = first
                while (k <= endParity) {
                    pending.remove(k)
                    k += 2
                }
            }
        }
        return segments.sortedBy { it.first }
    }
}
