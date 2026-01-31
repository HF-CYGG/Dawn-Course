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

    private fun recalculateConflicts() {
        val state = _uiState.value
        val original = state.originalCourse ?: return
        
        // Use targetWeeks if set (Step 2), otherwise use selectedWeeks (Step 1 assumption)
        val weeksToCheck = if (state.targetWeeks.isNotEmpty()) state.targetWeeks else state.selectedWeeks
        val duration = original.duration
        
        val conflicts = weeksToCheck.filter { week ->
            allCourses.any { course ->
                if (course.id == original.id) return@any false
                
                val timeOverlap = course.dayOfWeek == state.newDay &&
                        course.startSection < (state.newStartNode + duration) &&
                        (course.startSection + course.duration) > state.newStartNode
                
                if (!timeOverlap) return@any false
                
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

    fun confirmReschedule(onComplete: () -> Unit) {
        val state = _uiState.value
        val original = state.originalCourse ?: return
        val selected = state.selectedWeeks
        val target = state.targetWeeks
        
        if (selected.isEmpty() || target.isEmpty()) return

        viewModelScope.launch {
            // 1. Calculate remaining weeks for the original course
            val remainingWeeks = state.availableWeeks - selected
            
            // 2. Split remaining weeks into continuous segments
            val remainingSegments = convertToSegments(remainingWeeks)
            
            // 3. Create new reschedule segment
            // Use original.originId if available, otherwise use original.id (first split)
            val originId = if (original.originId == 0L) original.id else original.originId
            
            val newSegments = convertToSegments(target).map { (start, end, type) ->
                 original.copy(
                    id = 0, // New ID
                    location = state.newLocation,
                    dayOfWeek = state.newDay,
                    startSection = state.newStartNode,
                    startWeek = start,
                    endWeek = end,
                    weekType = type,
                    isModified = true,
                    note = state.note,
                    originId = originId
                )
            }

            // 4. Update DB
            // Delete original
            repository.deleteCourse(original)
            
            // Insert remaining segments (Original logic)
            remainingSegments.forEach { (start, end, type) ->
                repository.insertCourse(
                    original.copy(
                        id = 0, // New ID
                        startWeek = start,
                        endWeek = end,
                        weekType = type,
                        originId = originId
                    )
                )
            }
            
            // Insert new segments (Rescheduled logic)
            newSegments.forEach {
                repository.insertCourse(it)
            }
            
            onComplete()
        }
    }
    
    // Helper to convert a set of weeks into minimal list of (start, end, type)
    private fun convertToSegments(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.sorted()
        val segments = mutableListOf<Triple<Int, Int, Int>>()
        
        val pending = sorted.toMutableSet()
        while (pending.isNotEmpty()) {
            val first = pending.minOrNull()!!
            
            // Check All
            var endAll = first
            while (pending.contains(endAll + 1)) {
                endAll++
            }
            val countAll = endAll - first + 1
            
            // Check Parity
            var endParity = first
            val step = 2
            while (pending.contains(endParity + step)) {
                endParity += step
            }
            val countParity = (endParity - first) / 2 + 1
            
            if (countAll >= countParity) {
                // Use All
                segments.add(Triple(first, endAll, Course.WEEK_TYPE_ALL))
                for (i in first..endAll) pending.remove(i)
            } else {
                // Use Parity
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
