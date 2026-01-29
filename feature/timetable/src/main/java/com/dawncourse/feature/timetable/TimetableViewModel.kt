package com.dawncourse.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TimetableUiState {
    data object Loading : TimetableUiState
    data class Success(val courses: List<Course>) : TimetableUiState
}

/**
 * 课表功能的 ViewModel
 *
 * 负责管理课表界面的 UI 状态，并与 Repository 层交互。
 * 使用 Hilt 进行依赖注入，自动获取 Repository 实例。
 *
 * @property repository 课程数据仓库，用于获取和操作课程数据
 */
@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: CourseRepository
) : ViewModel() {

    /**
     * 课程列表状态流 (StateFlow)
     *
     * 从 Repository 获取课程 Flow，并将其转换为 StateFlow 以供 Compose UI 订阅。
     */
    val uiState: StateFlow<TimetableUiState> = repository.getAllCourses()
        .map { TimetableUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TimetableUiState.Loading
        )

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

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            repository.deleteCourse(course)
        }
    }

    suspend fun getCourse(id: Long): Course? {
        return repository.getCourseById(id)
    }
}
