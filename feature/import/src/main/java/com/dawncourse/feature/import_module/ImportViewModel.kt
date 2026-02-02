package com.dawncourse.feature.import_module

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.feature.import_module.engine.ScriptEngine
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.feature.import_module.model.convertXiaoaiCoursesToParsedCourses
import com.dawncourse.feature.import_module.model.parseIcsToParsedCourses
import com.dawncourse.feature.import_module.model.parseParsedCoursesFromRaw
import com.dawncourse.feature.import_module.model.parseXiaoaiProviderResult
import com.dawncourse.feature.import_module.model.toDomainCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    val webUrl: String = "",
    val parsedCourses: List<ParsedCourse> = emptyList(),
    // Settings for Semester
    val semesterStartDate: Long = System.currentTimeMillis(), // Timestamp
    val weekCount: Int = 20,
    
    // Time Settings
    val detectedMaxSection: Int = 12,
    val courseDuration: Int = 45,
    val breakDuration: Int = 10,
    val bigBreakDuration: Int = 20, // 大节间隔时长
    val sectionsPerBigSection: Int = 2, // 每个大节包含的小节数
    
    // Time Node Settings
    val amStartTime: LocalTime = LocalTime.of(8, 0),
    val pmStartTime: LocalTime = LocalTime.of(14, 0),
    val pmStartSection: Int = 5,
    val eveStartTime: LocalTime = LocalTime.of(19, 0),
    val eveStartSection: Int = 9,

    val sectionTimes: List<SectionTime> = emptyList(),

    val resultText: String = "",
    val isLoading: Boolean = false
)

sealed interface ImportEvent {
    data object Success : ImportEvent
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val application: Application,
    private val scriptEngine: ScriptEngine,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val settingsRepository: SettingsRepository
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

    /**
     * 设置当前导入步骤
     *
     * 切换步骤时会重置部分临时状态，如解析结果和错误信息。
     */
    fun setStep(step: ImportStep) {
        _uiState.update { 
            it.copy(
                step = step,
                parsedCourses = emptyList(),
                htmlContent = "",
                resultText = "",
                isLoading = false
            )
        }
    }

    fun updateWebUrl(url: String) {
        _uiState.update { it.copy(webUrl = url) }
    }

    fun updateHtmlContent(value: String) {
        _uiState.update { it.copy(htmlContent = value) }
    }

    fun updateResultText(value: String) {
        _uiState.update { it.copy(resultText = value) }
    }
    
    /**
     * 更新学期设置
     *
     * @param startDate 学期开始时间戳
     * @param weeks 学期总周数
     */
    fun updateSemesterSettings(startDate: Long, weeks: Int) {
        _uiState.update { it.copy(semesterStartDate = startDate, weekCount = weeks) }
    }
    
    fun updateTimeSettings(maxSection: Int, duration: Int, breakDuration: Int, bigBreakDuration: Int, sectionsPerBigSection: Int) {
        _uiState.update { 
            it.copy(
                detectedMaxSection = maxSection, 
                courseDuration = duration, 
                breakDuration = breakDuration,
                bigBreakDuration = bigBreakDuration,
                sectionsPerBigSection = sectionsPerBigSection
            ) 
        }
    }

    fun updateTimeNodeSettings(
        amStart: LocalTime,
        pmStart: LocalTime,
        pmStartSec: Int,
        eveStart: LocalTime,
        eveStartSec: Int
    ) {
        _uiState.update {
            it.copy(
                amStartTime = amStart,
                pmStartTime = pmStart,
                pmStartSection = pmStartSec,
                eveStartTime = eveStart,
                eveStartSection = eveStartSec
            )
        }
    }

    fun updateSectionTimes(times: List<SectionTime>) {
        _uiState.update { it.copy(sectionTimes = times) }
    }

    fun updateSectionTime(index: Int, time: SectionTime) {
        _uiState.update { state ->
            if (index < 0) return@update state
            val current = state.sectionTimes.toMutableList()
            if (index >= current.size) {
                return@update state
            }
            current[index] = time
            state.copy(sectionTimes = current)
        }
    }

    fun updateParsedCourse(index: Int, course: ParsedCourse) {
        _uiState.update { state ->
            val newCourses = state.parsedCourses.toMutableList()
            if (index in newCourses.indices) {
                newCourses[index] = course
            }
            state.copy(parsedCourses = newCourses)
        }
    }

    fun deleteParsedCourse(index: Int) {
        _uiState.update { state ->
            val newCourses = state.parsedCourses.toMutableList()
            if (index in newCourses.indices) {
                newCourses.removeAt(index)
            }
            state.copy(parsedCourses = newCourses, resultText = "已删除 1 个课程")
        }
    }

    /**
     * 解析 WebView 返回的 JSON 结果
     */
    fun parseResultFromWebView(raw: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "正在解析...") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    // 1. Try treating as JSON (Xiaoai/Internal format)
                    var courses = parseParsedCoursesFromRaw(raw)
                    if (courses.isEmpty()) {
                        val xiaoai = parseXiaoaiProviderResult(raw)
                        courses = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                    }
                    
                    // 2. If not JSON, treat as HTML and run default parser (Zhengfang)
                    if (courses.isEmpty()) {
                        try {
                            // Try Zhengfang parser by default as it's the most common one
                            // Ideally we should select parser based on URL or user selection
                            val script = application.assets.open("parsers/zhengfang.js").bufferedReader().use { it.readText() }
                            val jsonResult = scriptEngine.parseHtml(script, raw)
                            val xiaoai = parseXiaoaiProviderResult(jsonResult)
                            courses = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                        } catch (e: Exception) {
                            // Ignore script errors, maybe it's not a supported page or parser failed
                            e.printStackTrace()
                        }
                    }
                    
                    courses
                }
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "未识别到课程数据。请确认：\n1. 已登录教务系统\n2. 位于个人课表页面\n3. 页面已完全加载") }
                } else {
                    val maxSection = parsed.maxOfOrNull { it.endSection } ?: 12
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            detectedMaxSection = maxSection.coerceAtLeast(4),
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
     * 从 WebView 提取 HTML 并解析
     */
    fun parseHtmlFromWebView(html: String, script: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    // 1. Run Script
                    val jsonResult = scriptEngine.parseHtml(script, html)
                    
                    // 2. Parse JSON to Xiaoai Model
                    val xiaoaiResult = parseXiaoaiProviderResult(jsonResult)
                    
                    // 3. Convert to ParsedCourse
                    val parsedFromXiaoai = convertXiaoaiCoursesToParsedCourses(xiaoaiResult.courses)
                    if (parsedFromXiaoai.isNotEmpty()) {
                        parsedFromXiaoai
                    } else {
                        parseParsedCoursesFromRaw(jsonResult)
                    }
                }
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "解析完成，但未发现课程。请确认页面是否正确。") }
                } else {
                    val maxSection = parsed.maxOfOrNull { it.endSection } ?: 12
                    val maxWeek = parsed.flatMap { it.weeks }.maxOrNull() ?: 20
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            detectedMaxSection = maxSection.coerceAtLeast(4),
                            weekCount = maxWeek, // 自动设置学期总周数
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
    /**
     * 确认导入
     *
     * 将解析后的课程数据、学期设置和时间表设置保存到数据库。
     * 1. 更新全局时间设置 (MaxDailySections)
     * 2. 创建并保存新学期
     * 3. 保存所有课程数据
     */
    fun confirmImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val state = _uiState.value
                
                // 1. Update Time Settings
                settingsRepository.setMaxDailySections(state.detectedMaxSection)
                
                val generatedTimes = mutableListOf<SectionTime>()
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                
                for (i in 1..state.detectedMaxSection) {
                    val startTime = when (i) {
                        1 -> state.amStartTime
                        state.pmStartSection -> state.pmStartTime
                        state.eveStartSection -> state.eveStartTime
                        else -> {
                            val prevEnd = LocalTime.parse(generatedTimes.last().endTime, formatter)
                            val prevSectionIndex = i - 1
                            val isBigBreak = (prevSectionIndex % state.sectionsPerBigSection == 0)
                            val gap = if (isBigBreak) state.bigBreakDuration else state.breakDuration
                            prevEnd.plusMinutes(gap.toLong())
                        }
                    }
                    val endTime = startTime.plusMinutes(state.courseDuration.toLong())
                    generatedTimes.add(
                        SectionTime(
                            startTime = startTime.format(formatter),
                            endTime = endTime.format(formatter)
                        )
                    )
                }

                val finalTimes = if (state.sectionTimes.isNotEmpty()) {
                    (1..state.detectedMaxSection).map { index ->
                        state.sectionTimes.getOrNull(index - 1) ?: generatedTimes[index - 1]
                    }
                } else {
                    generatedTimes
                }
                settingsRepository.setSectionTimes(finalTimes)
                
                val mostFrequentDuration = state.parsedCourses
                    .groupingBy { it.duration }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: 2
                val safeDefaultDuration = mostFrequentDuration.coerceIn(1, 4)
                settingsRepository.setDefaultCourseDuration(safeDefaultDuration)
                
                // 2. Create Semester
                val newSemester = Semester(
                    name = "导入学期 ${LocalDate.now()}",
                    startDate = state.semesterStartDate,
                    weekCount = state.weekCount,
                    isCurrent = true
                )
                val semesterId = semesterRepository.insertSemester(newSemester)
                
                // 3. Insert Courses
                val domainCourses = state.parsedCourses.map { parsed ->
                    parsed.toDomainCourse().copy(semesterId = semesterId)
                }
                courseRepository.insertCourses(domainCourses)
                
                // Trigger Widget Update
                val intent = android.content.Intent("com.dawncourse.widget.FORCE_UPDATE")
                intent.setPackage(application.packageName)
                application.sendBroadcast(intent)
                
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
        viewModelScope.launch {
             _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val parsedCourses = withContext(Dispatchers.IO) {
                    parseIcsToParsedCourses(icsContent)
                }
                if (parsedCourses.isEmpty()) {
                    _uiState.update { it.copy(resultText = "ICS 解析失败: 未识别到课程", isLoading = false) }
                } else {
                    val maxSection = parsedCourses.maxOfOrNull { it.endSection } ?: 12
                    val maxWeek = parsedCourses.flatMap { it.weeks }.maxOrNull() ?: 20
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            parsedCourses = parsedCourses,
                            step = ImportStep.Review,
                            detectedMaxSection = maxSection.coerceAtLeast(4),
                            weekCount = maxWeek, // 自动设置学期总周数
                            resultText = "ICS 解析成功: ${parsedCourses.size} 门课程"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(resultText = "ICS 解析失败: ${e.message}", isLoading = false) }
            }
        }
    }
}
