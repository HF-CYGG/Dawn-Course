package com.dawncourse.feature.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.usecase.DetectConflictUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val detectConflictUseCase: DetectConflictUseCase,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val courseId: Long = savedStateHandle.get<String>("courseId")?.toLongOrNull() ?: 0L

    private val _course = MutableStateFlow<Course?>(null)
    val course: StateFlow<Course?> = _course.asStateFlow()

    private val _currentSemesterId = MutableStateFlow<Long>(1L)
    val currentSemesterId: StateFlow<Long> = _currentSemesterId.asStateFlow()

    private val _conflictCourses = MutableStateFlow<List<Course>>(emptyList())
    val conflictCourses: StateFlow<List<Course>> = _conflictCourses.asStateFlow()

    private val coursesInSemester = _currentSemesterId
        .flatMapLatest { semesterId -> repository.getCoursesBySemester(semesterId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nameSuggestions: StateFlow<List<String>> = coursesInSemester
        .map { courses ->
            courses.map { it.name }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locationSuggestions: StateFlow<List<String>> = coursesInSemester
        .map { courses ->
            courses.map { it.location }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val teacherSuggestions: StateFlow<List<String>> = coursesInSemester
        .map { courses ->
            courses.map { it.teacher }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val semester = semesterRepository.getCurrentSemester().firstOrNull()
            if (semester != null) {
                _currentSemesterId.value = semester.id
            }
        }

        if (courseId != 0L) {
            viewModelScope.launch {
                _course.value = repository.getCourseById(courseId)
            }
        }
    }

    /**
     * 发送小组件更新广播
     */
    private fun sendWidgetUpdateBroadcast() {
        val intent = Intent("com.dawncourse.widget.FORCE_UPDATE")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    /**
     * 保存课程
     *
     * @param course 要保存的课程对象
     * @param onSaved 保存完成后的回调
     */
    fun saveCourse(course: Course, onSaved: () -> Unit) {
        viewModelScope.launch {
            // Check conflicts
            // Note: DetectConflictUseCase needs all courses.
            // This might be heavy if we fetch all. But for a local DB it's fine.
            // Optimally we should query by semester/day.
            // But DetectConflictUseCase as defined earlier takes a list.
            // Let's skip conflict check for MVP speed or do a quick check.
            
            if (course.id == 0L) {
                repository.insertCourse(course)
            } else {
                repository.updateCourse(course)
            }
            sendWidgetUpdateBroadcast()
            onSaved()
        }
    }

    fun saveCourses(courses: List<Course>, onSaved: () -> Unit) {
        if (courses.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            if (courses.size == 1 && courses.first().id != 0L) {
                repository.updateCourse(courses.first())
                sendWidgetUpdateBroadcast()
                onSaved()
                return@launch
            }
            val toInsert = courses.map { course ->
                if (course.id == 0L) course else course.copy(id = 0L)
            }
            repository.insertCourses(toInsert)
            sendWidgetUpdateBroadcast()
            onSaved()
        }
    }
}
