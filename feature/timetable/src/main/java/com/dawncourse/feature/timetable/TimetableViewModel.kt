package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

sealed interface TimetableUiState {
    data object Loading : TimetableUiState
    data class Success(
        val courses: List<Course>,
        val currentWeek: Int,
        val totalWeeks: Int = 20,
        val semesterStartDate: LocalDate? = null
    ) : TimetableUiState
}

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val calculateWeekUseCase: CalculateWeekUseCase
) : ViewModel() {

    private val _currentWeek = MutableStateFlow(1)
    
    init {
        viewModelScope.launch {
            semesterRepository.getCurrentSemester().collect { semester ->
                if (semester != null) {
                    val week = calculateWeekUseCase(semester.startDate)
                    val validWeek = week.coerceIn(1, semester.weekCount)
                    _currentWeek.value = validWeek
                }
            }
        }
    }

    val uiState: StateFlow<TimetableUiState> = combine(
        repository.getAllCourses(),
        _currentWeek,
        semesterRepository.getCurrentSemester()
    ) { courses, currentWeek, semester ->
        val startDate = semester?.startDate?.let { 
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() 
        }
        TimetableUiState.Success(
            courses = courses, 
            currentWeek = currentWeek,
            totalWeeks = semester?.weekCount ?: 20,
            semesterStartDate = startDate
        )
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

    /**
     * 删除课程
     *
     * @param course 要删除的课程对象
     */
    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            repository.deleteCourse(course)
        }
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
}
