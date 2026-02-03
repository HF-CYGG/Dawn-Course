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
    Input,      // 步骤一：输入源选择
    WebView,    // 步骤二：网页抓取
    Review      // 步骤三：预览与确认
}

/**
 * 导入页面的 UI 状态
 *
 * @property step 当前所处的步骤
 * @property htmlContent 解析的 HTML 原始内容
 * @property webUrl 当前 WebView 加载的 URL
 * @property parsedCourses 已解析出的课程列表 (临时数据)
 * @property semesterStartDate 学期开始日期 (时间戳)
 * @property weekCount 学期总周数
 * @property detectedMaxSection 检测到的最大节次 (用于自动设置作息表)
 * @property courseDuration 单节课时长 (分钟)
 * @property breakDuration 小课间时长 (分钟)
 * @property bigBreakDuration 大课间时长 (分钟)
 * @property sectionsPerBigSection 每个大节包含的小节数
 * @property amStartTime 上午第一节开始时间
 * @property pmStartTime 下午第一节开始时间
 * @property pmStartSection 下午第一节的节次号
 * @property eveStartTime 晚上第一节开始时间
 * @property eveStartSection 晚上第一节的节次号
 * @property sectionTimes 生成的或用户修改的具体作息时间表
 * @property resultText 解析结果或错误提示文本
 * @property isLoading 是否正在进行耗时操作 (加载网页、执行解析)
 */
data class ImportUiState(
    val step: ImportStep = ImportStep.Input,
    val htmlContent: String = "",
    val webUrl: String = "",
    val parsedCourses: List<ParsedCourse> = emptyList(),
    
    // 学期设置
    val semesterStartDate: Long = System.currentTimeMillis(),
    val weekCount: Int = 20,
    
    // 时间与节次设置
    val detectedMaxSection: Int = 12,
    val courseDuration: Int = 45,
    val breakDuration: Int = 10,
    val bigBreakDuration: Int = 20, // 大课间 (如第2-3节之间) 的时长
    val sectionsPerBigSection: Int = 2, // 多少节课算一个大节 (通常为2)
    
    // 关键时间节点设置
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

/**
 * 导入功能 ViewModel
 *
 * 负责处理课程导入的全流程业务逻辑：
 * 1. 网页源码获取与多策略解析 (支持 JSON/HTML/JS 脚本)
 * 2. 课程数据预览与编辑 (增删改)
 * 3. 学期与作息时间配置 (智能推断与手动调整)
 * 4. 最终数据入库 (同步更新 Room 数据库和 DataStore 设置)
 */
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
        // 初始化学期开始日期为本周一 (符合大多数导入场景)
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val timestamp = monday.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        _uiState.update { it.copy(semesterStartDate = timestamp) }
    }

    /**
     * 设置当前导入步骤
     *
     * 切换步骤时会重置部分临时状态，如解析结果和错误信息，确保流程清晰。
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
     * @param startDate 学期开始时间戳 (UTC)
     * @param weeks 学期总周数
     */
    fun updateSemesterSettings(startDate: Long, weeks: Int) {
        _uiState.update { it.copy(semesterStartDate = startDate, weekCount = weeks) }
    }
    
    /**
     * 更新全局时间设置
     *
     * @param maxSection 最大节次
     * @param duration 单节时长
     * @param breakDuration 小课间
     * @param bigBreakDuration 大课间
     * @param sectionsPerBigSection 大节包含小节数
     */
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

    /**
     * 更新作息时间节点设置 (上下午、晚上开始时间)
     */
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

    /**
     * 更新单节作息时间 (用户手动微调)
     */
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

    /**
     * 更新解析后的课程信息 (预览阶段编辑功能)
     */
    fun updateParsedCourse(index: Int, course: ParsedCourse) {
        _uiState.update { state ->
            val newCourses = state.parsedCourses.toMutableList()
            if (index in newCourses.indices) {
                newCourses[index] = course
            }
            state.copy(parsedCourses = newCourses)
        }
    }

    /**
     * 删除解析后的课程 (预览阶段删除功能)
     */
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
     * 解析 WebView 返回的 JSON/HTML 结果
     *
     * 智能识别策略：
     * 1. 优先尝试作为 JSON 直接解析 (适配小爱课程表/内部格式)
     * 2. 如果失败，尝试作为 HTML 使用内置脚本逐个解析 (适配正方、青果等教务)
     */
    fun parseResultFromWebView(raw: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "正在解析...") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    // 1. 尝试作为 JSON 解析
                    var courses = parseParsedCoursesFromRaw(raw)
                    if (courses.isEmpty()) {
                        val xiaoai = parseXiaoaiProviderResult(raw)
                        courses = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                    }
                    
                    // 2. 如果不是 JSON，尝试作为 HTML 解析 (依次尝试正方、青果等脚本)
                    if (courses.isEmpty()) {
                        val parsers = listOf("parsers/zhengfang.js", "parsers/kingosoft.js")
                        for (parserPath in parsers) {
                            try {
                                // 加载解析脚本
                                val script = application.assets.open(parserPath).bufferedReader().use { it.readText() }
                                val jsonResult = scriptEngine.parseHtml(script, raw)
                                val xiaoai = parseXiaoaiProviderResult(jsonResult)
                                val result = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                                
                                if (result.isNotEmpty()) {
                                    courses = result
                                    break // 解析成功，停止尝试其他脚本
                                }
                            } catch (e: Exception) {
                                // 当前解析器失败，继续尝试下一个
                                e.printStackTrace()
                            }
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
     * 从 WebView 提取 HTML 并使用指定脚本解析 (旧版手动测试入口)
     */
    fun parseHtmlFromWebView(html: String, script: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    // 1. 运行 JS 脚本提取数据
                    val jsonResult = scriptEngine.parseHtml(script, html)
                    
                    // 2. 解析 JSON 结果
                    val xiaoaiResult = parseXiaoaiProviderResult(jsonResult)
                    
                    // 3. 转换为领域模型
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
                    val maxWeek = parsed.maxOfOrNull { it.endWeek } ?: 20
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
     *
     * 将解析后的课程数据、学期设置和时间表设置保存到数据库。
     * 核心逻辑：
     * 1. 根据设置更新全局时间配置 (MaxDailySections, SectionTimes)
     * 2. 创建并保存新学期 (Room DB)
     * 3. 同步更新 AppSettings (DataStore)，确保设置页面数据一致性
     * 4. 保存所有课程数据
     * 5. 发送广播通知 Widget 更新
     */
    fun confirmImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val state = _uiState.value
                
                // 1. 更新全局时间设置
                settingsRepository.setMaxDailySections(state.detectedMaxSection)
                
                // 1.1 自动生成作息时间表
                val generatedTimes = mutableListOf<SectionTime>()
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                
                for (i in 1..state.detectedMaxSection) {
                    val startTime = when (i) {
                        1 -> state.amStartTime
                        state.pmStartSection -> state.pmStartTime
                        state.eveStartSection -> state.eveStartTime
                        else -> {
                            // 计算当前节次的开始时间：上一节结束时间 + 课间休息
                            val prevEnd = LocalTime.parse(generatedTimes.last().endTime, formatter)
                            val prevSectionIndex = i - 1
                            // 判断是否为大课间 (例如第2节后、第4节后)
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

                // 合并用户手动修改的时间和自动生成的时间
                val finalTimes = if (state.sectionTimes.isNotEmpty()) {
                    (1..state.detectedMaxSection).map { index ->
                        state.sectionTimes.getOrNull(index - 1) ?: generatedTimes[index - 1]
                    }
                } else {
                    generatedTimes
                }
                settingsRepository.setSectionTimes(finalTimes)
                
                // 1.2 设置默认课程时长 (取解析结果中的众数，增强智能性)
                val mostFrequentDuration = state.parsedCourses
                    .groupingBy { it.duration }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: 2
                val safeDefaultDuration = mostFrequentDuration.coerceIn(1, 4)
                settingsRepository.setDefaultCourseDuration(safeDefaultDuration)
                
                // 2. 创建新学期并插入数据库
                val newSemester = Semester(
                    name = "导入学期 ${LocalDate.now()}",
                    startDate = state.semesterStartDate,
                    weekCount = state.weekCount,
                    isCurrent = true
                )
                val semesterId = semesterRepository.insertSemester(newSemester)
                
                // 2.1 关键修正：同步更新 AppSettings (DataStore)
                // 解决 "导入时设置了开学日期，但在设置页面不生效" 的问题
                settingsRepository.setCurrentSemesterName(newSemester.name)
                settingsRepository.setStartDateTimestamp(newSemester.startDate)
                settingsRepository.setTotalWeeks(newSemester.weekCount)
                
                // 3. 插入课程数据 (关联新学期 ID)
                val domainCourses = state.parsedCourses.map { parsed ->
                    parsed.toDomainCourse().copy(semesterId = semesterId)
                }
                courseRepository.insertCourses(domainCourses)
                
                // 4. 触发小组件更新广播
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

    // 保留旧版入口以供手动文本测试
    fun runImport(script: String) {
        parseHtmlFromWebView(_uiState.value.htmlContent, script)
    }

    /**
     * 运行 ICS 文件导入
     */
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
                    val maxWeek = parsedCourses.maxOfOrNull { it.endWeek } ?: 20
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
