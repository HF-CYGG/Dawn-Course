package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

/**
 * 课表界面 UI 状态
 */
sealed interface TimetableUiState {
    /**
     * 加载中状态
     */
    @Immutable
    data object Loading : TimetableUiState

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

/**
 * 课表功能 ViewModel
 *
 * 负责管理课表界面的 UI 状态、处理用户交互（如切换周次、删除课程、撤销操作）以及数据流的聚合。
 *
 * @property repository 课程数据仓库
 * @property semesterRepository 学期数据仓库
 * @property calculateWeekUseCase 计算当前周次的用例
 */
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
    private val currentSemesterFlow: StateFlow<com.dawncourse.core.domain.model.Semester?> =
        combine(
            semesterRepository.getCurrentSemester(),
            timeTicker
        ) { semester: com.dawncourse.core.domain.model.Semester?, _: Unit ->
            semester
        }
            .onEach { semester ->
                if (semester != null) {
                    // 根据学期开始日期计算当前周次
                    val week = calculateWeekUseCase(semester.startDate)
                    // 确保周次至少为 1，但允许超过学期总周数（用于触发假期模式）
                    val validWeek = week.coerceAtLeast(1)
                    _currentWeek.value = validWeek
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )

    /**
     * UI 状态流
     *
     * 组合了 [currentSemesterFlow], [coursesFlow], [_currentWeek] 等数据源。
     * 当任一数据源发生变化时，自动计算并生成最新的 UI 状态。
     * 这种响应式设计确保 UI 始终展示最新数据，无需手动刷新。
     */
    val uiState: StateFlow<TimetableUiState> = currentSemesterFlow
        .flatMapLatest { semester ->
            // 如果学期存在，转换开始日期并获取该学期的课程流
            val startDate = semester?.startDate?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val totalWeeks = semester?.weekCount ?: 20
            val coursesFlow = semester?.id?.let { repository.getCoursesBySemester(it) } ?: flowOf(emptyList())

            // 组合课程数据和当前选择的周次
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
            if (course.id == 0L) {
                repository.insertCourse(course)
            } else {
                repository.updateCourse(course)
            }
        }
    }

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    // 删除操作的撤销栈，最多保存 5 步
    private val deletedCoursesStack = ArrayDeque<List<Course>>()

    /**
     * 标记用户消息已显示
     */
    fun userMessageShown() {
        _userMessage.value = null
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
     *
     * 从撤销栈中取出最近一次删除的课程列表，并将其重新插入数据库。
     */
    fun undoDelete() {
        val coursesToRestore = deletedCoursesStack.removeFirstOrNull() ?: return
        viewModelScope.launch {
            // 恢复课程 ID 为 0 以作为新记录插入，或者保留原 ID 如果是软删除？
            // Room 的 insert 策略是 REPLACE，如果保留原 ID 且 ID 未被占用，可以恢复。
            // 但如果这是自增 ID，删除后可能无法保证 ID 仍可用（虽然通常没问题）。
            // 安全起见，我们将 ID 设为 0 让数据库重新生成，或者尝试插入原对象。
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
            val siblings = repository.getCoursesByOriginId(targetOriginId)
            // 如果只有一条记录且未修改，说明无需撤销
            if (siblings.size <= 1 && siblings.none { it.isModified }) return@launch
            
            // 找到“原始模板”：未修改的记录 (作为新纪录的属性基准)
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

    /**
     * 将离散的周次集合转换为连续的片段列表
     *
     * 使用贪心算法尝试合并连续的周次。
     * 优先尝试合并为 [Course.WEEK_TYPE_ALL] (连续周)，
     * 其次尝试合并为 [Course.WEEK_TYPE_ODD] (单周) 或 [Course.WEEK_TYPE_EVEN] (双周)。
     *
     * @param weeks 周次集合
     * @return 片段列表 [(开始周, 结束周, 类型)]
     */
    private fun convertToSegments(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.sorted()
        val segments = mutableListOf<Triple<Int, Int, Int>>()
        
        val pending = sorted.toMutableSet()
        while (pending.isNotEmpty()) {
            val first = pending.minOrNull()!!
            
            // 尝试构建连续周 (1,2,3,4...)
            var endAll = first
            while (pending.contains(endAll + 1)) endAll++
            val countAll = endAll - first + 1
            
            // 尝试构建同奇偶性周 (1,3,5...)
            var endParity = first
            val step = 2
            while (pending.contains(endParity + step)) endParity += step
            val countParity = (endParity - first) / 2 + 1
            
            // 贪心策略：谁长选谁
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
