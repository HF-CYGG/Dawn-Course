package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
