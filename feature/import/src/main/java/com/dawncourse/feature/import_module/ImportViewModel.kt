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

/**
 * 导入页面的 UI 状态
 *
 * @property htmlContent 用户输入的 HTML 源码内容
 * @property resultText 导入操作的结果提示文本（成功或错误信息）
 * @property isLoading 是否正在执行导入操作
 */
data class ImportUiState(
    val htmlContent: String = "",
    val resultText: String = "",
    val isLoading: Boolean = false
)

/**
 * 导入相关的单次事件
 */
sealed interface ImportEvent {
    /** 导入成功事件 */
    data object Success : ImportEvent
}

/**
 * 导入页面的 ViewModel
 *
 * 负责处理课程导入的业务逻辑，包括：
 * - 管理 UI 状态 [ImportUiState]
 * - 调用 [ScriptEngine] 执行 JS 脚本解析 HTML
 * - 解析 ICS 文件内容
 * - 将解析后的课程数据存入 [CourseRepository]
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val scriptEngine: ScriptEngine,
    private val courseRepository: CourseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    /** UI 状态流 */
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>()
    /** 单次事件流（如导航跳转） */
    val events = _events.asSharedFlow()

    /**
     * 更新 HTML 输入内容
     *
     * @param value 新的 HTML 内容
     */
    fun updateHtmlContent(value: String) {
        _uiState.update { it.copy(htmlContent = value) }
    }

    /**
     * 执行脚本导入
     *
     * 使用 QuickJS 引擎运行指定的 JS 脚本，解析当前输入的 HTML 内容。
     *
     * @param script 用于解析的 JavaScript 脚本内容
     */
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

    /**
     * 执行 ICS 文件导入
     *
     * 解析 ICS 格式的文本内容，并转换为课程实体存入数据库。
     *
     * @param icsContent ICS 文件的文本内容
     */
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
