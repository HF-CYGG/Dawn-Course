package com.dawncourse.feature.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import com.dawncourse.core.domain.usecase.DetectConflictUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val widgetUpdateRepository: WidgetUpdateRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val courseId: Long = savedStateHandle.get<String>("courseId")?.toLongOrNull() ?: 0L

    private val _course = MutableStateFlow<Course?>(null)
    val course: StateFlow<Course?> = _course.asStateFlow()

    private val _currentSemesterId = MutableStateFlow<Long>(1L)
    val currentSemesterId: StateFlow<Long> = _currentSemesterId.asStateFlow()

    private val _conflictCourses = MutableStateFlow<List<Course>>(emptyList())
    val conflictCourses: StateFlow<List<Course>> = _conflictCourses.asStateFlow()

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
            val existingCourses = repository.getCoursesBySemester(semesterId).first()
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
                repository.getCourseById(editingId)
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

            if (editingId != 0L && toInsert.size == 1) {
                repository.updateCourse(toInsert.first().copy(id = editingId))
                sendWidgetUpdateBroadcast()
                onSaved()
                return@launch
            }

            if (editingId != 0L) {
                repository.deleteCourseById(editingId)
            }
            val insertList = toInsert.map { it.copy(id = 0L) }
            repository.insertCourses(insertList)
            sendWidgetUpdateBroadcast()
            onSaved()
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
}
