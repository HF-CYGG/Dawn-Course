package com.dawncourse.feature.import_module

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.feature.import_module.engine.ScriptEngine
import com.dawncourse.feature.import_module.model.parseIcsToParsedCourses
import com.dawncourse.feature.import_module.model.toDomainCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportUiState(
    val htmlContent: String = "",
    val resultText: String = "",
    val isLoading: Boolean = false
)

sealed interface ImportEvent {
    data object Success : ImportEvent
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val scriptEngine: ScriptEngine,
    private val courseRepository: CourseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>()
    val events = _events.asSharedFlow()

    fun updateHtmlContent(value: String) {
        _uiState.update { it.copy(htmlContent = value) }
    }

    fun runImport(script: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val html = _uiState.value.htmlContent
                val result = scriptEngine.parseHtml(script, html)
                _uiState.update { it.copy(resultText = "解析成功:\n$result", isLoading = false) }
                _events.emit(ImportEvent.Success)
            } catch (e: Exception) {
                _uiState.update { it.copy(resultText = "解析失败: ${e.message}", isLoading = false) }
            }
        }
    }

    fun runIcsImport(icsContent: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val parsedCourses = parseIcsToParsedCourses(icsContent)
                if (parsedCourses.isEmpty()) {
                    _uiState.update { it.copy(resultText = "ICS 解析失败: 未识别到课程", isLoading = false) }
                    return@launch
                }
                parsedCourses.forEach { course ->
                    courseRepository.insertCourse(course.toDomainCourse())
                }
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
