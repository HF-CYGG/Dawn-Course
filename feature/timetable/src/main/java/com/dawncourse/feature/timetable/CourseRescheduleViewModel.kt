package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 调课功能 ViewModel
 *
 * 负责处理调课逻辑，包括：
 * 1. 加载课程信息
 * 2. 管理选择的调整周次
 * 3. 冲突检测
 * 4. 执行调课（课程分裂与新记录插入）
 */
@HiltViewModel
class CourseRescheduleViewModel @Inject constructor(
    private val repository: CourseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RescheduleUiState())
    val uiState: StateFlow<RescheduleUiState> = _uiState.asStateFlow()

    private var allCourses: List<Course> = emptyList()

    init {
        viewModelScope.launch {
            repository.getAllCourses().collect { courses ->
                allCourses = courses
                recalculateConflicts()
            }
        }
    }

    fun loadCourse(courseId: Long) {
        viewModelScope.launch {
            val course = repository.getCourseById(courseId)
            if (course != null) {
                // 计算当前课程的所有周次
                val allWeeks = calculateWeeks(course)
                _uiState.update { 
                    it.copy(
                        originalCourse = course,
                        availableWeeks = allWeeks,
                        // 默认选中当前周之后的周次，或者不选
                        selectedWeeks = emptySet(),
                        newDay = course.dayOfWeek,
                        newStartNode = course.startSection,
                        newLocation = course.location
                    )
                }
            }
        }
    }

    /**
     * 重新计算冲突
     *
     * 检查当前选择的目标周次和时间，是否与现有的其他课程冲突。
     */
    private fun recalculateConflicts() {
        val state = _uiState.value
        val original = state.originalCourse ?: return
        
        // 如果已设置目标周次 (Step 2)，则检查目标周次；否则检查选中的原周次 (Step 1)
        val weeksToCheck = if (state.targetWeeks.isNotEmpty()) state.targetWeeks else state.selectedWeeks
        val duration = original.duration
        
        val conflictingCourses = mutableSetOf<Course>()
        val conflictSlots = mutableSetOf<Pair<Int, Int>>() // Day, Node
        
        weeksToCheck.forEach { week ->
            allCourses.forEach { course ->
                // 排除自己
                if (course.id == original.id) return@forEach
                
                // 检查时间是否重叠
                val timeOverlap = course.dayOfWeek == state.newDay &&
                        course.startSection < (state.newStartNode + duration) &&
                        (course.startSection + course.duration) > state.newStartNode
                
                if (!timeOverlap) return@forEach
                
                // 检查周次是否重叠
                val weekMatch = when (course.weekType) {
                    Course.WEEK_TYPE_ALL -> true
                    Course.WEEK_TYPE_ODD -> week % 2 != 0
                    Course.WEEK_TYPE_EVEN -> week % 2 == 0
                    else -> true
                }
                
                val weekOverlap = week >= course.startWeek && week <= course.endWeek && weekMatch
                
                if (weekOverlap) {
                    conflictingCourses.add(course)
                    // 记录冲突课程的所有时间槽位，用于网格高亮
                    for (i in 0 until course.duration) {
                         conflictSlots.add(course.dayOfWeek to (course.startSection + i))
                    }
                }
            }
        }
        
        val hasConflict = conflictingCourses.isNotEmpty()
        val message = if (hasConflict) {
             val names = conflictingCourses.joinToString("、") { 
                 if (it.location.isNotBlank()) "《${it.name}》(${it.location})" else "《${it.name}》" 
             }
             "与 $names 冲突"
        } else ""

        _uiState.update { it.copy(
            conflictInfo = ConflictInfo(
                hasConflict = hasConflict,
                message = message,
                conflictCourses = conflictingCourses.toList(),
                conflictSlots = conflictSlots
            )
        ) }
    }

    /**
     * 计算课程覆盖的所有周次
     *
     * @param course 课程对象
     * @return 包含该课程所有上课周次的 Set 集合
     */
    private fun calculateWeeks(course: Course): Set<Int> {
        val weeks = mutableSetOf<Int>()
        for (i in course.startWeek..course.endWeek) {
            val match = when (course.weekType) {
                Course.WEEK_TYPE_ODD -> i % 2 != 0
                Course.WEEK_TYPE_EVEN -> i % 2 == 0
                else -> true
            }
            if (match) weeks.add(i)
        }
        return weeks
    }

    /**
     * 切换周次选择状态 (Step 1)
     *
     * 用户点击周次选择器时调用，用于选中或取消选中要调整的周次。
     *
     * @param week 点击的周次
     */
    fun toggleWeekSelection(week: Int) {
        _uiState.update { state ->
            if (state.availableWeeks.contains(week)) {
                val newSelection = state.selectedWeeks.toMutableSet()
                if (newSelection.contains(week)) {
                    newSelection.remove(week)
                } else {
                    newSelection.add(week)
                }
                state.copy(selectedWeeks = newSelection)
            } else {
                state
            }
        }
        recalculateConflicts()
    }

    /**
     * 初始化目标周次 (Step 2)
     *
     * 当进入第二步（选择时间地点）时调用。
     * 默认情况下，目标周次等于第一步选中的周次（即平移调课）。
     * 同时也初始化新时间为原课程时间。
     */
    fun initTargetWeeks() {
        val original = _uiState.value.originalCourse
        _uiState.update { 
            it.copy(
                targetWeeks = it.selectedWeeks,
                newDay = original?.dayOfWeek ?: 1,
                newStartNode = original?.startSection ?: 1
            ) 
        }
        recalculateConflicts() // Check conflicts for new target weeks
    }

    /**
     * 切换目标周次选择状态
     *
     * 允许用户在第二步微调目标周次（例如：把第1周的课调到第2周）。
     */
    fun toggleTargetWeek(week: Int) {
        _uiState.update { state ->
            val newTarget = state.targetWeeks.toMutableSet()
            if (newTarget.contains(week)) {
                newTarget.remove(week)
            } else {
                newTarget.add(week)
            }
            state.copy(targetWeeks = newTarget)
        }
        recalculateConflicts()
    }

    /**
     * 切换全选状态
     * 如果已全选，则取消全选；否则全选。
     */
    fun toggleSelectAllWeeks() {
        _uiState.update { state ->
            if (state.selectedWeeks.containsAll(state.availableWeeks)) {
                state.copy(selectedWeeks = emptySet())
            } else {
                state.copy(selectedWeeks = state.availableWeeks)
            }
        }
        recalculateConflicts()
    }
    
    /**
     * 切换单周选择状态
     * 如果已选中所有单周且无其他周次，则取消选择；否则选中所有单周。
     */
    fun toggleSelectOddWeeks() {
         _uiState.update { state ->
             val odd = state.availableWeeks.filter { it % 2 != 0 }.toSet()
             if (state.selectedWeeks == odd) {
                 state.copy(selectedWeeks = emptySet())
             } else {
                 state.copy(selectedWeeks = odd)
             }
         }
         recalculateConflicts()
    }

    /**
     * 切换双周选择状态
     * 如果已选中所有双周且无其他周次，则取消选择；否则选中所有双周。
     */
    fun toggleSelectEvenWeeks() {
        _uiState.update { state ->
            val even = state.availableWeeks.filter { it % 2 == 0 }.toSet()
            if (state.selectedWeeks == even) {
                state.copy(selectedWeeks = emptySet())
            } else {
                state.copy(selectedWeeks = even)
            }
        }
        recalculateConflicts()
    }

    /**
     * 更新新的上课时间
     *
     * @param day 星期几 (1-7)
     * @param startNode 开始节次
     */
    fun updateNewTime(day: Int, startNode: Int) {
        _uiState.update {
            it.copy(newDay = day, newStartNode = startNode)
        }
        recalculateConflicts()
    }

    /**
     * 更新新的上课地点
     */
    fun updateNewLocation(location: String) {
        _uiState.update {
            it.copy(newLocation = location)
        }
    }

    /**
     * 更新调课备注
     */
    fun updateNote(note: String) {
        _uiState.update {
            it.copy(note = note)
        }
    }

    /**
     * 确认调课操作
     *
     * 核心逻辑：课程分裂 (Course Splitting)
     * 1. 将原课程的周次中，被选中的周次扣除，剩余的周次重新组合成若干条 [Course] 记录。
     * 2. 将被选中的周次映射到新的时间/地点，生成新的 [Course] 记录。
     * 3. 所有的记录（包括保留的旧周次和新生成的调课记录）都共享同一个 [originId]。
     *    - [originId] 用于后续的“撤销”操作，能将它们重新合并。
     *
     * @param onComplete 完成回调
     */
    fun confirmReschedule(onComplete: () -> Unit) {
        val state = _uiState.value
        val original = state.originalCourse ?: return
        val selected = state.selectedWeeks
        val target = state.targetWeeks
        
        if (selected.isEmpty() || target.isEmpty()) return
        
        viewModelScope.launch {
            // 1. 计算原课程剩余的周次 (Total - Selected)
            val remainingWeeks = state.availableWeeks - selected
            
            // 2. 将剩余周次转换为连续的 Course 片段
            val remainingSegments = convertToSegments(remainingWeeks)
            
            // 3. 确定原始 ID (Origin ID)
            // 如果原课程已经是分裂过的 (originId != 0)，沿用之；否则使用其自身 ID 作为家族 ID。
            val originId = if (original.originId == 0L) original.id else original.originId
            
            // 4. 生成调课后的新记录
            val newSegments = convertToSegments(target).map { (start, end, type) ->
                 original.copy(
                    id = 0, // 插入新记录
                    location = state.newLocation,
                    dayOfWeek = state.newDay,
                    startSection = state.newStartNode,
                    startWeek = start,
                    endWeek = end,
                    weekType = type,
                    isModified = true, // 标记为已调课
                    note = state.note,
                    originId = originId // 关联到大家族
                )
            }

            // 5. 执行数据库更新事务
            // 删除旧记录
            repository.deleteCourse(original)
            
            // 插入剩余的旧周次片段
            remainingSegments.forEach { (start, end, type) ->
                repository.insertCourse(
                    original.copy(
                        id = 0,
                        startWeek = start,
                        endWeek = end,
                        weekType = type,
                        originId = originId
                    )
                )
            }
            
            // 插入新的调课记录
            newSegments.forEach {
                repository.insertCourse(it)
            }
            
            onComplete()
        }
    }
    
    /**
     * 将周次集合转换为最小的连续片段列表
     *
     * 算法目标：
     * 将离散的周次（如 {1, 2, 3, 5, 7}）合并为更紧凑的 [Course] 记录表示。
     * 优先合并为“全周”，其次尝试合并为“单周”或“双周”。
     *
     * @param weeks 周次集合
     * @return 包含 (开始周, 结束周, 周类型) 的三元组列表
     */
    private fun convertToSegments(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.sorted()
        val segments = mutableListOf<Triple<Int, Int, Int>>()
        
        val pending = sorted.toMutableSet()
        while (pending.isNotEmpty()) {
            val first = pending.minOrNull()!!
            
            // 尝试合并为全周 (1, 2, 3...)
            var endAll = first
            while (pending.contains(endAll + 1)) {
                endAll++
            }
            val countAll = endAll - first + 1
            
            // 尝试合并为单/双周 (1, 3, 5...)
            var endParity = first
            val step = 2
            while (pending.contains(endParity + step)) {
                endParity += step
            }
            val countParity = (endParity - first) / 2 + 1
            
            // 贪心策略：谁覆盖的周数更多选谁
            // 如果全周和单双周覆盖数量相同（例如只有1周），优先选全周
            if (countAll >= countParity) {
                // 使用全周
                segments.add(Triple(first, endAll, Course.WEEK_TYPE_ALL))
                for (i in first..endAll) pending.remove(i)
            } else {
                // 使用单/双周
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

/**
 * 调课界面 UI 状态
 *
 * @property originalCourse 原始课程对象（被调整的课程）
 * @property availableWeeks 该课程所有可用的周次集合
 * @property selectedWeeks 当前选中的要移除（调走）的周次集合
 * @property targetWeeks 调课后的目标周次集合（默认等于 selectedWeeks，除非用户单独修改）
 * @property newDay 新的星期几 (1-7)
 * @property newStartNode 新的开始节次
 * @property newLocation 新的上课地点
 * @property note 备注信息
 * @property conflictWeeks 与其他课程存在时间冲突的周次集合
 */
data class RescheduleUiState(
    val originalCourse: Course? = null,
    val availableWeeks: Set<Int> = emptySet(),
    val selectedWeeks: Set<Int> = emptySet(),
    val targetWeeks: Set<Int> = emptySet(),
    val newDay: Int = 1,
    val newStartNode: Int = 1,
    val newLocation: String = "",
    val note: String = "",
    val conflictInfo: ConflictInfo = ConflictInfo()
)

data class ConflictInfo(
    val hasConflict: Boolean = false,
    val message: String = "",
    val conflictCourses: List<Course> = emptyList(),
    val conflictSlots: Set<Pair<Int, Int>> = emptySet() // (Day, Node)
)
