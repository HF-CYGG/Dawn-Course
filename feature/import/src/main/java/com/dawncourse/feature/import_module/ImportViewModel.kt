package com.dawncourse.feature.import_module

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.feature.import_module.engine.ScriptEngine
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.feature.import_module.model.convertXiaoaiCoursesToParsedCourses
import com.dawncourse.feature.import_module.model.parseIcsToParsedCourses
import com.dawncourse.feature.import_module.model.parseXiaoaiProviderResult
import com.dawncourse.feature.import_module.model.toDomainCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ImportStep {
    Input,
    WebView,
    Review
}

/**
 * 导入页面的 UI 状态
 */
data class ImportUiState(
    val step: ImportStep = ImportStep.Input,
    val htmlContent: String = "",
    val webUrl: String = "https://www.baidu.com/s?wd=教务系统",
    val parsedCourses: List<ParsedCourse> = emptyList(),
    // Settings for Semester
    val semesterStartDate: Long = System.currentTimeMillis(), // Timestamp
    val weekCount: Int = 20,
    
    val resultText: String = "",
    val isLoading: Boolean = false
)

sealed interface ImportEvent {
    data object Success : ImportEvent
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val scriptEngine: ScriptEngine,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>()
    val events = _events.asSharedFlow()
    
    init {
        // Init start date to this week's Monday
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val timestamp = monday.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        _uiState.update { it.copy(semesterStartDate = timestamp) }
    }

    fun setStep(step: ImportStep) {
        _uiState.update { it.copy(step = step) }
    }

    fun updateWebUrl(url: String) {
        _uiState.update { it.copy(webUrl = url) }
    }

    fun updateHtmlContent(value: String) {
        _uiState.update { it.copy(htmlContent = value) }
    }
    
    fun updateSemesterSettings(startDate: Long, weeks: Int) {
        _uiState.update { it.copy(semesterStartDate = startDate, weekCount = weeks) }
    }

    /**
     * 从 WebView 提取 HTML 并解析
     */
    fun parseHtmlFromWebView(html: String, script: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                // 1. Run Script
                val jsonResult = scriptEngine.parseHtml(script, html)
                
                // 2. Parse JSON to Xiaoai Model
                val xiaoaiResult = parseXiaoaiProviderResult(jsonResult)
                
                // 3. Convert to ParsedCourse
                val parsed = convertXiaoaiCoursesToParsedCourses(xiaoaiResult.courses)
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "解析完成，但未发现课程。请确认页面是否正确。") }
                } else {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            resultText = "成功解析 ${parsed.size} 个课程段"
                        ) 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, resultText = "解析失败: ${e.message}") }
            }
        }
    }

    /**
     * 确认导入
     */
    fun confirmImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val state = _uiState.value
                
                // 1. Create Semester
                val newSemester = Semester(
                    name = "导入学期 ${LocalDate.now()}",
                    startDate = state.semesterStartDate,
                    weekCount = state.weekCount,
                    isCurrent = true
                )
                val semesterId = semesterRepository.insertSemester(newSemester)
                
                // 2. Insert Courses
                val domainCourses = state.parsedCourses.map { parsed ->
                    parsed.toDomainCourse().copy(semesterId = semesterId)
                }
                courseRepository.insertCourses(domainCourses)
                
                _uiState.update { it.copy(isLoading = false, resultText = "导入成功！") }
                _events.emit(ImportEvent.Success)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, resultText = "导入失败: ${e.message}") }
            }
        }
    }

    // Keep legacy runImport for manual text input testing if needed, or redirect it to use the new flow
    fun runImport(script: String) {
        parseHtmlFromWebView(_uiState.value.htmlContent, script)
    }

    fun runIcsImport(icsContent: String) {
        // ... (Keep ICS logic but maybe adapt to use confirm flow? For now keep separate)
        viewModelScope.launch {
             _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val parsedCourses = parseIcsToParsedCourses(icsContent)
                if (parsedCourses.isEmpty()) {
                    _uiState.update { it.copy(resultText = "ICS 解析失败: 未识别到课程", isLoading = false) }
                    return@launch
                }
                
                // For ICS, we might also want to show Review screen? 
                // But to minimize changes, let's just insert with default semester (or create one).
                // Let's create a semester for ICS too.
                
                 val newSemester = Semester(
                    name = "ICS 导入 ${LocalDate.now()}",
                    startDate = System.currentTimeMillis(), // Default today
                    weekCount = 20,
                    isCurrent = true
                )
                val semesterId = semesterRepository.insertSemester(newSemester)

                val domainCourses = parsedCourses.map { course ->
                    course.toDomainCourse().copy(semesterId = semesterId)
                }
                courseRepository.insertCourses(domainCourses)
                
                _uiState.update {
                    it.copy(
                        resultText = "ICS 导入成功: ${parsedCourses.size} 门课程",
                        isLoading = false
                    )
                }
                _events.emit(ImportEvent.Success)
            } catch (e: Exception) {
                _uiState.update { it.copy(resultText = "ICS 导入失败: ${e.message}", isLoading = false) }
            }
        }
    }
}
