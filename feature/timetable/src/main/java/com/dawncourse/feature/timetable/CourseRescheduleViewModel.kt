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
        
        val conflicts = weeksToCheck.filter { week ->
            allCourses.any { course ->
                // 排除自己
                if (course.id == original.id) return@any false
                
                // 检查时间是否重叠
                val timeOverlap = course.dayOfWeek == state.newDay &&
                        course.startSection < (state.newStartNode + duration) &&
                        (course.startSection + course.duration) > state.newStartNode
                
                if (!timeOverlap) return@any false
                
                // 检查周次是否重叠
                val weekMatch = when (course.weekType) {
                    Course.WEEK_TYPE_ALL -> true
                    Course.WEEK_TYPE_ODD -> week % 2 != 0
                    Course.WEEK_TYPE_EVEN -> week % 2 == 0
                    else -> true
                }
                
                val weekOverlap = week >= course.startWeek && week <= course.endWeek && weekMatch
                weekOverlap
            }
        }.toSet()
        
        _uiState.update { it.copy(conflictWeeks = conflicts) }
    }

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

    fun initTargetWeeks() {
        _uiState.update { it.copy(targetWeeks = it.selectedWeeks) }
        recalculateConflicts() // Check conflicts for new target weeks
    }

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

    fun selectAllWeeks() {
        _uiState.update { it.copy(selectedWeeks = it.availableWeeks) }
        recalculateConflicts()
    }
    
    fun selectOddWeeks() {
         _uiState.update { state ->
             val odd = state.availableWeeks.filter { it % 2 != 0 }.toSet()
             state.copy(selectedWeeks = odd)
         }
         recalculateConflicts()
    }

    fun selectEvenWeeks() {
        _uiState.update { state ->
            val even = state.availableWeeks.filter { it % 2 == 0 }.toSet()
            state.copy(selectedWeeks = even)
        }
        recalculateConflicts()
    }

    fun updateNewTime(day: Int, startNode: Int) {
        _uiState.update {
            it.copy(newDay = day, newStartNode = startNode)
        }
        recalculateConflicts()
    }

    fun updateNewLocation(location: String) {
        _uiState.update {
            it.copy(newLocation = location)
        }
    }

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
    val conflictWeeks: Set<Int> = emptySet()
)
