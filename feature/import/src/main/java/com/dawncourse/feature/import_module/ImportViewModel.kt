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
import com.dawncourse.feature.import_module.model.SectionRange
import com.dawncourse.feature.import_module.model.XiaoaiCourse
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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
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
                    // 解析流程的设计目标：
                    // - 尽可能“自适应”不同教务系统/不同解析器输出格式
                    // - 任意单个解析器失败不影响后续尝试（可恢复、可重试）
                    // - 不打印堆栈，避免把页面内容/账号信息等潜在敏感数据暴露到日志中
                    //
                    // 这里用两个标志位区分两类“最终失败”：
                    // 1) 所有解析器都正常执行但结果为空：更像是“页面不对/未登录/未加载完成”
                    // 2) 解析器执行过程中发生异常：更像是“解析器不兼容/脚本运行失败”
                    var hasParserCrash = false
                    var hasAnyParserAttempt = false

                    // 当前 WebView URL，用于判断是否为强智教务系统
                    val currentUrl = _uiState.value.webUrl

                    // 0.1 优先尝试：若为强智教务系统，使用 Kotlin + Jsoup 解析 HTML
                    if (isQiangZhiHostForViewModel(currentUrl)) {
                        val qiangZhiCourses = parseQiangZhiHtmlWithJsoup(raw)
                        if (qiangZhiCourses.isNotEmpty()) {
                            return@withContext qiangZhiCourses
                        }
                    }

                    // 0.2 特殊处理：检测是否为强智直连 JSON 数据
                    if (raw.contains("qiangzhi_direct")) {
                        return@withContext parseQiangZhiJson(raw)
                    }

                    // 1. 尝试作为 JSON 解析
                    var courses = parseParsedCoursesFromRaw(raw)
                    if (courses.isEmpty()) {
                        val xiaoai = parseXiaoaiProviderResult(raw)
                        courses = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                    }
                    
                    // 2. 如果不是 JSON，尝试作为 HTML 解析 (依次尝试强智、正方、青果等脚本)
                    if (courses.isEmpty()) {
                        val parsers = listOf("parsers/qiangzhi.js", "parsers/zhengfang.js", "parsers/kingosoft.js")
                        for (parserPath in parsers) {
                            hasAnyParserAttempt = true
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
                            } catch (_: ScriptEngine.ScriptExecutionException) {
                                // JS 解析器运行失败属于“可预期异常”：
                                // - 可能是脚本与当前页面不匹配
                                // - 可能是教务系统页面结构变更
                                // - 可能是脚本语法/运行时错误
                                //
                                // 对用户而言，这些错误可通过“重试/换导入方式/换解析器”恢复，因此不打印堆栈。
                                hasParserCrash = true
                            } catch (_: Throwable) {
                                // 兜底：任何解析器异常都不应中断后续解析器的尝试
                                hasParserCrash = true
                            }
                        }
                    }
                    
                    // 通过“空结果 + 是否发生过解析器异常/是否尝试过解析器”返回更准确的失败语义，
                    // 由上层决定如何向用户提示。
                    if (courses.isEmpty() && hasAnyParserAttempt && hasParserCrash) {
                        throw ScriptEngine.ScriptExecutionException("解析器运行失败")
                    }
                    courses
                }
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "未识别到课程数据。请确认：\n1. 已登录教务系统\n2. 位于个人课表页面\n3. 页面已完全加载") }
                } else {
                    val maxSection = parsed.maxOfOrNull { it.endSection } ?: 12
                    val safeMaxSection = maxSection.coerceAtLeast(4)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            detectedMaxSection = safeMaxSection,
                            sectionTimes = generateDefaultSectionTimes(it, safeMaxSection),
                            resultText = "成功解析 ${parsed.size} 个课程段"
                        )
                    }
                }
            } catch (_: ScriptEngine.ScriptExecutionException) {
                // 给出明确但不泄露敏感信息的提示，并保持“可重试”：
                // - 不显示底层异常 message（可能包含页面片段/请求信息）
                // - 用户可直接重新导入，或切换为“文件导入”等方式
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "解析失败：解析器运行异常。\n请重试；若仍失败，可尝试文件导入或更换导入方式。"
                    )
                }
            } catch (_: Throwable) {
                // 兜底：避免把异常细节直接展示给用户
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "解析失败：数据格式不支持或解析过程发生异常。\n请重试；若仍失败，请确认已登录且位于课表页面。"
                    )
                }
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
                    val safeMaxSection = maxSection.coerceAtLeast(4)
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            detectedMaxSection = safeMaxSection,
                            weekCount = maxWeek, // 自动设置学期总周数
                            sectionTimes = generateDefaultSectionTimes(it, safeMaxSection),
                            resultText = "成功解析 ${parsed.size} 个课程段"
                        ) 
                    }
                }
            } catch (_: ScriptEngine.ScriptExecutionException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "解析失败：解析器运行异常。\n请重试；若仍失败，建议更换导入方式。"
                    )
                }
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "解析失败：解析过程发生异常。\n请重试。"
                    )
                }
            }
        }
    }

    /**
     * 根据当前设置生成默认作息时间表
     */
    private fun generateDefaultSectionTimes(state: ImportUiState, maxSection: Int): List<SectionTime> {
        val generatedTimes = mutableListOf<SectionTime>()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")

        for (i in 1..maxSection) {
            val startTime = when (i) {
                1 -> state.amStartTime
                state.pmStartSection -> state.pmStartTime
                state.eveStartSection -> state.eveStartTime
                else -> {
                    // 计算当前节次的开始时间：上一节结束时间 + 课间休息
                    val prevEnd = if (generatedTimes.isNotEmpty()) {
                        LocalTime.parse(generatedTimes.last().endTime, formatter)
                    } else {
                        // 防御性代码，理论上不应发生（除非 maxSection < 1 但循环进来了，或者 i=1 没匹配到）
                        // 如果 i != 1 但 generatedTimes 为空，说明 pmStartSection/eveStartSection 设置有问题导致跳过了前面的节次？
                        // 简单起见，如果为空则回退到 amStartTime
                        state.amStartTime
                    }
                    
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
        return generatedTimes
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
                
                // 1.1 使用当前状态中的作息时间表（如果为空则重新生成默认值作为兜底）
                val finalTimes = if (state.sectionTimes.isNotEmpty()) {
                    state.sectionTimes
                } else {
                    generateDefaultSectionTimes(state, state.detectedMaxSection)
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

    /**
     * 运行强智 API 导入
     *
     * 使用学号与密码获取 token，再根据学年与周次拉取课表。
     *
     * @param baseUrl 教务系统基础地址，例如 https://jwxt.xxx.edu.cn
     * @param studentId 学号
     * @param password 密码
     * @param totalWeeks 学期总周数
     */
    fun runQiangZhiApiImport(
        baseUrl: String,
        studentId: String,
        password: String,
        totalWeeks: Int
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "正在连接强智教务系统...") }
            try {
                val parsedCourses = withContext(Dispatchers.IO) {
                    val normalizedBaseUrl = normalizeQiangZhiBaseUrl(baseUrl)
                    if (normalizedBaseUrl.isBlank()) {
                        throw Exception("教务系统地址不正确")
                    }
                    if (studentId.isBlank() || password.isBlank()) {
                        throw Exception("学号或密码不能为空")
                    }
                    val token = qiangZhiAuthUser(normalizedBaseUrl, studentId, password)
                    val currentInfo = qiangZhiGetCurrentTime(normalizedBaseUrl, token)
                    val xnxqh = currentInfo.optString("xnxqh")
                    if (xnxqh.isBlank()) {
                        throw Exception("无法获取学年学期信息")
                    }
                    val targetWeeks = totalWeeks.coerceIn(1, 30)
                    val allCourses = mutableListOf<XiaoaiCourse>()
                    for (week in 1..targetWeeks) {
                        val weekArray = qiangZhiGetCourseArray(
                            baseUrl = normalizedBaseUrl,
                            token = token,
                            studentId = studentId,
                            xnxqh = xnxqh,
                            week = week
                        )
                        allCourses.addAll(parseQiangZhiApiCourses(weekArray))
                    }
                    val merged = mergeQiangZhiCourses(allCourses)
                    convertXiaoaiCoursesToParsedCourses(merged)
                }
                if (parsedCourses.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "未找到课程数据") }
                } else {
                    val maxSection = parsedCourses.maxOfOrNull { it.endSection } ?: 12
                    val maxWeek = parsedCourses.maxOfOrNull { it.endWeek } ?: 20
                    val safeMaxSection = maxSection.coerceAtLeast(4)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            parsedCourses = parsedCourses,
                            step = ImportStep.Review,
                            detectedMaxSection = safeMaxSection,
                            weekCount = maxWeek,
                            sectionTimes = generateDefaultSectionTimes(it, safeMaxSection),
                            resultText = "强智 API 导入成功: ${parsedCourses.size} 个课程段"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, resultText = "强智 API 导入失败: ${e.message}") }
            }
        }
    }

    /**
     * 运行 WakeUp 课程表口令导入
     *
     * 通过 WakeUp 官方 API 获取分享的课程数据。
     * API 地址: https://i.wakeup.fun/share_schedule/get?key={token}
     *
     * @param token 用户输入的分享口令
     */
    fun runWakeUpImport(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, resultText = "正在获取 WakeUp 课表...") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    // 1. 发送 GET 请求
                    // 必须伪装 User-Agent 和 version 才能通过 API 校验
                    val urlStr = "https://i.wakeup.fun/share_schedule/get?key=$token"
                    val url = java.net.URL(urlStr)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "okhttp/3.14.9")
                    conn.setRequestProperty("version", "243")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    
                    if (conn.responseCode != 200) {
                        throw Exception("HTTP Error ${conn.responseCode}")
                    }
                    
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    
                    // 2. 解析响应结构
                    // 响应是一个 JSON: {"code": 200, "data": "...\n...\n...", "msg": ""}
                    // 其中 data 字段是一个包含换行符的字符串，通过换行符分隔不同部分的数据
                    val rootJson = org.json.JSONObject(responseText)
                    if (rootJson.optInt("code") != 200) {
                         throw Exception("API Error: ${rootJson.optString("msg")}")
                    }
                    
                    val dataStr = rootJson.optString("data")
                    val parts = dataStr.split("\n")
                    // 校验数据完整性，通常至少包含 5 部分
                    if (parts.size < 5) {
                        throw Exception("数据格式不符合预期 (parts < 5)")
                    }
                    
                    // 3. 解析课程元数据 (parts[3])
                    // 格式为 JSON 数组，包含课程 ID 和课程名称的映射
                    val rawCourseMaps = org.json.JSONArray(parts[3])
                    val courseNameMap = mutableMapOf<Int, String>()
                    for (i in 0 until rawCourseMaps.length()) {
                        val item = rawCourseMaps.getJSONObject(i)
                        val id = item.optInt("id")
                        val name = item.optString("courseName")
                        courseNameMap[id] = name
                    }
                    
                    // 4. 解析具体课程时间表 (parts[4])
                    // 格式为 JSON 数组，包含具体的上课时间、地点、老师等信息
                    val rawCourses = org.json.JSONArray(parts[4])
                    val xiaoaiCourses = mutableListOf<com.dawncourse.feature.import_module.model.XiaoaiCourse>()
                    
                    for (i in 0 until rawCourses.length()) {
                        val item = rawCourses.getJSONObject(i)
                        val id = item.optInt("id")
                        // 通过 ID 关联获取课程名称
                        val name = courseNameMap[id] ?: "-"
                        val room = item.optString("room", "-")
                        val teacher = item.optString("teacher", "-")
                        val startWeek = item.optInt("startWeek")
                        val endWeek = item.optInt("endWeek")
                        val day = item.optInt("day")
                        val startNode = item.optInt("startNode")
                        val step = item.optInt("step")
                        
                        // 构建连续的周次列表
                        val weeks = (startWeek..endWeek).toList()
                        // 构建节次列表 (例如: 1, 2)
                        val sections = (startNode until (startNode + step)).toList()
                        
                        xiaoaiCourses.add(
                            com.dawncourse.feature.import_module.model.XiaoaiCourse(
                                name = name,
                                teacher = teacher,
                                position = room,
                                day = day,
                                weeks = weeks,
                                sections = sections
                            )
                        )
                    }
                    
                    // 转换为应用内部通用的 ParsedCourse 格式
                    convertXiaoaiCoursesToParsedCourses(xiaoaiCourses)
                }
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "未找到课程数据") }
                } else {
                     val maxSection = parsed.maxOfOrNull { it.endSection } ?: 12
                     val maxWeek = parsed.maxOfOrNull { it.endWeek } ?: 20
                     val safeMaxSection = maxSection.coerceAtLeast(4)
                     _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            parsedCourses = parsed,
                            step = ImportStep.Review,
                            detectedMaxSection = safeMaxSection,
                            weekCount = maxWeek,
                            sectionTimes = generateDefaultSectionTimes(it, safeMaxSection),
                            resultText = "WakeUp 导入成功: ${parsed.size} 个课程段"
                        ) 
                    }
                }
                
            } catch (e: Exception) {
                 _uiState.update { it.copy(isLoading = false, resultText = "WakeUp 导入失败: ${e.message}") }
            }
        }
    }

    /**
     * 强智 API 登录并获取 token
     */
    private fun qiangZhiAuthUser(baseUrl: String, studentId: String, password: String): String {
        val url = buildQiangZhiUrl(
            baseUrl,
            mapOf(
                "method" to "authUser",
                "xh" to studentId,
                "pwd" to password
            )
        )
        val responseText = requestQiangZhiJson(url, null)
        val json = org.json.JSONObject(responseText)
        val token = json.optString("token")
        if (token.isBlank() || token == "-1") {
            val msg = json.optString("msg").ifBlank { "登录失败" }
            throw Exception(msg)
        }
        return token
    }

    /**
     * 强智 API 获取当前学年学期信息
     */
    private fun qiangZhiGetCurrentTime(baseUrl: String, token: String): org.json.JSONObject {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val url = buildQiangZhiUrl(
            baseUrl,
            mapOf(
                "method" to "getCurrentTime",
                "currDate" to today
            )
        )
        val responseText = requestQiangZhiJson(url, token)
        return org.json.JSONObject(responseText)
    }

    /**
     * 强智 API 获取指定周课程表
     */
    private fun qiangZhiGetCourseArray(
        baseUrl: String,
        token: String,
        studentId: String,
        xnxqh: String,
        week: Int
    ): org.json.JSONArray {
        val url = buildQiangZhiUrl(
            baseUrl,
            mapOf(
                "method" to "getKbcxAzc",
                "xh" to studentId,
                "xnxqid" to xnxqh,
                "zc" to week.toString()
            )
        )
        val responseText = requestQiangZhiJson(url, token)
        return org.json.JSONArray(responseText)
    }

    /**
     * 解析强智 API 返回的课程数组
     */
    private fun parseQiangZhiApiCourses(array: org.json.JSONArray): List<XiaoaiCourse> {
        val courses = mutableListOf<XiaoaiCourse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("kcmc").trim()
            if (name.isBlank()) continue
            val teacher = item.optString("jsxm").trim()
            val location = item.optString("jsmc").trim()
            val weekText = item.optString("kkzc").trim()
            val weeks = parseWeekString(weekText)
            val filteredWeeks = when {
                weekText.contains("单") -> weeks.filter { it % 2 == 1 }
                weekText.contains("双") -> weeks.filter { it % 2 == 0 }
                else -> weeks
            }
            val sectionText = item.optString("jcs")
                .ifBlank { item.optString("jc") }
                .ifBlank { item.optString("jcsj") }
            val sections = parseSectionListFromText(sectionText)
            val day = parseQiangZhiDay(item)
            val resolvedSections = if (sections.isNotEmpty()) {
                sections
            } else {
                val range = calculateSectionRangeByTime(
                    startTime = item.optString("kssj"),
                    endTime = item.optString("jssj")
                )
                if (range == null) {
                    emptyList()
                } else {
                    val endSection = range.startSection + range.duration - 1
                    (range.startSection..endSection).toList()
                }
            }
            if (day <= 0 || filteredWeeks.isEmpty() || resolvedSections.isEmpty()) continue
            courses.add(
                XiaoaiCourse(
                    name = name,
                    teacher = teacher,
                    position = location,
                    day = day,
                    weeks = filteredWeeks,
                    sections = resolvedSections
                )
            )
        }
        return courses
    }

    /**
     * 合并强智 API 课程中的周次信息，避免重复课程段
     */
    private fun mergeQiangZhiCourses(courses: List<XiaoaiCourse>): List<XiaoaiCourse> {
        val merged = LinkedHashMap<String, XiaoaiCourse>()
        courses.forEach { course ->
            val key = buildString {
                append(course.name)
                append("|")
                append(course.teacher)
                append("|")
                append(course.position)
                append("|")
                append(course.day)
                append("|")
                append(course.sections.joinToString(","))
            }
            val existing = merged[key]
            if (existing == null) {
                merged[key] = course
            } else {
                val mergedWeeks = (existing.weeks + course.weeks).distinct().sorted()
                merged[key] = existing.copy(weeks = mergedWeeks)
            }
        }
        return merged.values.toList()
    }

    /**
     * 解析强智 API 返回的星期字段
     */
    private fun parseQiangZhiDay(item: org.json.JSONObject): Int {
        val day = item.optInt("xqj", item.optInt("day", 0))
        if (day in 1..7) return day
        val dayText = item.optString("xqjmc").ifBlank { item.optString("xq") }
        return parseDayFromText(dayText)
    }

    /**
     * 将文本解析为节次列表
     */
    private fun parseSectionListFromText(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("第", "")
            .replace("节", "")
            .replace("—", "-")
            .replace("－", "-")
            .replace("～", "-")
            .replace("至", "-")
        val parts = normalized.split(",", "，", "、")
        val sections = mutableListOf<Int>()
        parts.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@forEach
            if (trimmed.contains("-")) {
                val range = trimmed.split("-")
                    .mapNotNull { it.trim().toIntOrNull() }
                if (range.size >= 2) {
                    val start = range.first()
                    val end = range.last()
                    if (start > 0 && end >= start) {
                        for (s in start..end) sections.add(s)
                    }
                }
            } else if (trimmed.length >= 4 && trimmed.all { it.isDigit() }) {
                val chunked = trimmed.chunked(2)
                chunked.forEach { chunk ->
                    val value = chunk.toIntOrNull()
                    if (value != null && value > 0) sections.add(value)
                }
            } else {
                val value = trimmed.toIntOrNull()
                if (value != null && value > 0) sections.add(value)
            }
        }
        return sections.distinct().sorted()
    }

    /**
     * 通过时间段推断节次范围
     */
    private fun calculateSectionRangeByTime(
        startTime: String,
        endTime: String?
    ): SectionRange? {
        if (startTime.isBlank()) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val start = LocalTime.parse(startTime, formatter)
            val end = if (!endTime.isNullOrBlank()) LocalTime.parse(endTime, formatter) else null
            val startSection = when {
                start >= LocalTime.of(19, 0) -> 9
                start >= LocalTime.of(14, 0) -> 5
                else -> 1
            }
            val durationMinutes = if (end != null && end.isAfter(start)) {
                java.time.Duration.between(start, end).toMinutes().toInt()
            } else {
                45
            }
            val sectionCount = kotlin.math.ceil(durationMinutes / 45.0).toInt().coerceAtLeast(1)
            val endSection = (startSection + sectionCount - 1).coerceAtLeast(startSection)
            SectionRange(startSection, endSection)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将星期文本转换为数字
     */
    private fun parseDayFromText(text: String): Int {
        if (text.isBlank()) return 0
        return when {
            text.contains("一") -> 1
            text.contains("二") -> 2
            text.contains("三") -> 3
            text.contains("四") -> 4
            text.contains("五") -> 5
            text.contains("六") -> 6
            text.contains("日") || text.contains("天") || text.contains("七") -> 7
            else -> 0
        }
    }

    /**
     * 构建强智 API 请求地址
     */
    private fun buildQiangZhiUrl(baseUrl: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            val encoded = URLEncoder.encode(value, "UTF-8")
            "$key=$encoded"
        }
        return "$baseUrl?$query"
    }

    /**
     * 发送强智 API 请求
     */
    private fun requestQiangZhiJson(url: String, token: String?): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("token", token)
        }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val responseText = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            throw Exception("HTTP Error $responseCode")
        }
        return responseText
    }

    /**
     * ViewModel 内部使用的强智教务系统域名判断工具
     *
     * 仅根据 host / 路径简单判断，避免依赖 UI 层工具方法。
     */
    private fun isQiangZhiHostForViewModel(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url)
            val host = (uri.host ?: "").lowercase()
            host.contains("qzdatasoft") ||
                url.contains("/jsxsd/xskb/", ignoreCase = true) ||
                url.contains("xskb_list.do", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 Jsoup 解析强智教务系统课表 HTML
     *
     * 对应参考实现中的 scheduleHtmlParser，但在 Kotlin 端完成 DOM 解析：
     * - 定位 class="kbcontent" 的 div 元素
     * - 通过 id 解析星期几 (td12 -> 周一第 2 节)
     * - 按 “---------------------” 分割同一单元格内的多门课程
     * - 提取课程名、教师、教室、周次与节次
     */
    private fun parseQiangZhiHtmlWithJsoup(html: String): List<ParsedCourse> {
        if (html.isBlank()) return emptyList()
        return try {
            val doc = Jsoup.parse(html)
            val kbContents = doc.select(".kbcontent")
            if (kbContents.isEmpty()) return emptyList()

            val xiaoaiCourses = mutableListOf<XiaoaiCourse>()

            for (element in kbContents) {
                val id = element.id()
                if (id.isBlank()) continue
                // id 一般形如 td12，取第一个数字作为星期几
                val digits = id.filter { it.isDigit() }
                if (digits.isEmpty()) continue
                val day = digits.first().digitToIntOrNull() ?: continue
                if (day !in 1..7) continue

                // 按分隔线拆分同一单元格中的多门课程
                val blocks = element.html().split("---------------------")
                for (block in blocks) {
                    val blockHtml = block.trim()
                    if (blockHtml.isEmpty()) continue

                    val tempDoc = Jsoup.parseBodyFragment(blockHtml)
                    val body = tempDoc.body()

                    // 课程名称：取第一个非空文本节点
                    var name = ""
                    val childNodes = body.childNodes()
                    for (node in childNodes) {
                        val text = node.outerHtml()
                        val plain = Jsoup.parse(text).text().trim()
                        if (plain.isNotEmpty()) {
                            name = plain
                            break
                        }
                    }
                    if (name.isBlank() || name == "&nbsp;") continue

                    // 教师、教室、周次信息
                    val teacher = body.selectFirst("[title=老师]")?.text()?.trim().orEmpty()
                    val room = body.selectFirst("[title=教室]")?.text()?.trim().orEmpty()
                    val weekNode = body.selectFirst("[title=周次(节次)]") ?: continue
                    val weekRaw = weekNode.text().trim()
                    if (weekRaw.isBlank()) continue

                    // 解析节次 [03-04节]
                    val sections = mutableListOf<Int>()
                    val sectionRegex = "\\[(\\d+)-(\\d+)节]".toRegex()
                    val sectionMatch = sectionRegex.find(weekRaw)
                    if (sectionMatch != null) {
                        val start = sectionMatch.groupValues[1].toIntOrNull() ?: 0
                        val end = sectionMatch.groupValues[2].toIntOrNull() ?: 0
                        if (start > 0 && end >= start) {
                            for (s in start..end) {
                                sections.add(s)
                            }
                        }
                    }

                    // 解析周次 1-16 或 1-8,10-16
                    val weeks = mutableListOf<Int>()
                    val weekStr = weekRaw.substringBefore("(").trim()
                    if (weekStr.isNotEmpty()) {
                        val parts = weekStr.split(",", "，")
                        for (part in parts) {
                            val token = part.trim()
                            if (token.isEmpty()) continue
                            if (token.contains("-")) {
                                val rangeParts = token.split("-")
                                val startWeek = rangeParts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                                val endWeek = rangeParts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                                if (startWeek > 0 && endWeek >= startWeek) {
                                    for (w in startWeek..endWeek) {
                                        weeks.add(w)
                                    }
                                }
                            } else {
                                val week = token.toIntOrNull()
                                if (week != null && week > 0) weeks.add(week)
                            }
                        }
                    }

                    if (weeks.isEmpty() || sections.isEmpty()) continue

                    xiaoaiCourses.add(
                        XiaoaiCourse(
                            name = name,
                            teacher = if (teacher.isNotBlank()) teacher else "未知教师",
                            position = if (room.isNotBlank()) room else "未知教室",
                            day = day,
                            weeks = weeks.distinct().sorted(),
                            sections = sections.distinct().sorted()
                        )
                    )
                }
            }

            if (xiaoaiCourses.isEmpty()) {
                emptyList()
            } else {
                convertXiaoaiCoursesToParsedCourses(xiaoaiCourses)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 标准化强智 API 基础地址
     */
    private fun normalizeQiangZhiBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return try {
            val uri = URI(withScheme)
            val host = uri.host ?: return ""
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port/app.do"
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 解析强智教务系统直连 JSON 数据
     *
     * 该数据由 WebView 中的 JS 脚本直接从 DOM 提取，格式为：
     * {
     *   "type": "qiangzhi_direct",
     *   "data": [
     *     { "row": 1, "col": 1, "text": "课程名..." },
     *     ...
     *   ]
     * }
     *
     * @param json 包含强智教务系统课程数据的 JSON 字符串
     * @return 解析后的通用课程列表 [ParsedCourse]
     */
    private fun parseQiangZhiJson(json: String): List<ParsedCourse> {
        val root = org.json.JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        val xiaoaiCourses = mutableListOf<com.dawncourse.feature.import_module.model.XiaoaiCourse>()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            // 强智系统的节次映射：row 1 -> 第1大节 (1-2节)
            val row = item.optInt("row") // 1..5
            // 强智系统的周次映射：col 1 -> 周一
            val col = item.optInt("col") // 1..7 (Week)
            val text = item.optString("text")
            
            if (text.isBlank()) continue

            // 解析文本内容 (格式通常为: 课程名 教师 周次 地点)
            val rawCourses = parseQiangZhiText(text)
            
            rawCourses.forEach { c ->
                // 计算起始节次：(大节 - 1) * 2 + 1
                // 例如：第1大节 -> 1节, 第2大节 -> 3节
                val startSection = (row - 1) * 2 + 1
                val sections = listOf(startSection, startSection + 1)
                
                // 解析周次字符串 (例如 "1-16周", "1-8,10-16周")
                val weekList = parseWeekString(c.week)
                
                if (weekList.isNotEmpty()) {
                    xiaoaiCourses.add(
                        com.dawncourse.feature.import_module.model.XiaoaiCourse(
                            name = c.name,
                            teacher = c.teacher,
                            position = c.place,
                            day = col,
                            weeks = weekList,
                            sections = sections
                        )
                    )
                }
            }
        }
        return convertXiaoaiCoursesToParsedCourses(xiaoaiCourses)
    }

    /**
     * 强智课程原始数据模型
     * 用于暂存从单元格文本中解析出的单一课程信息
     */
    private data class QiangZhiCourseRaw(
        var name: String = "",
        var teacher: String = "",
        var week: String = "",
        var place: String = ""
    )

    /**
     * 解析强智教务系统单元格文本
     *
     * 单元格文本通常包含多门课程，每门课程由 4 部分组成：
     * 1. 课程名称
     * 2. 教师姓名
     * 3. 周次信息 (如 "1-16(周)")
     * 4. 上课地点
     *
     * 该算法通过遍历字符并识别空白分隔符，循环提取每门课程的这 4 个字段。
     *
     * @param info 单元格内的完整文本内容
     * @return 解析出的 [QiangZhiCourseRaw] 列表
     */
    private fun parseQiangZhiText(info: String): List<QiangZhiCourseRaw> {
        val list = mutableListOf<QiangZhiCourseRaw>()
        var index = 0
        val length = info.length
        
        while (index < length) {
            var segNum = 1 // 当前解析字段序号 (1:Name, 2:Teacher, 3:Week, 4:Place)
            val infoSeg = StringBuilder()
            val course = QiangZhiCourseRaw()
            
            // 循环解析一门课程的 4 个字段
            while (segNum <= 4 && index <= length) {
                // 读取当前字符，如果已到末尾则模拟为空格以触发结束逻辑
                val ch = if (index == length) ' ' else info[index]
                index++
                
                if (!ch.isWhitespace()) {
                    infoSeg.append(ch)
                } else {
                    // 遇到空白字符，且缓冲区有内容，说明一个字段解析完成
                    if (infoSeg.isNotEmpty()) {
                         val strSeg = infoSeg.toString()
                         when (segNum) {
                             1 -> course.name = strSeg
                             2 -> course.teacher = strSeg
                             3 -> course.week = strSeg
                             4 -> course.place = strSeg
                         }
                         segNum++
                         infoSeg.clear()
                    }
                }
            }
            // 只有解析出课程名才视为有效课程
            if (course.name.isNotEmpty()) {
                list.add(course)
            }
        }
        return list
    }
    
    /**
     * 解析周次字符串
     *
     * 支持格式：
     * - "1-16" -> [1, 2, ..., 16]
     * - "1-5,7-9" -> [1, 2, 3, 4, 5, 7, 8, 9]
     * - "3" -> [3]
     * - "1-16(周)" -> 自动忽略非数字字符
     *
     * @param weekStr 包含周次信息的字符串
     * @return 周次整数列表
     */
    private fun parseWeekString(weekStr: String): List<Int> {
        val weeks = mutableListOf<Int>()
        var index = 0
        val len = weekStr.length
        while (index < len) {
            // 跳过非数字字符 (如 "周", "(", "," 等)
            while (index < len && !weekStr[index].isDigit()) {
                index++
            }
            if (index >= len) break

            // 提取第一个数字 (起始周或单周)
            var l = 0
            while (index < len && weekStr[index].isDigit()) {
                l = l * 10 + (weekStr[index] - '0')
                index++
            }
            
            // 检查是否为区间格式 (例如 "1-16")
            if (index < len && weekStr[index] == '-') {
                index++
                // 提取第二个数字 (结束周)
                var r = 0
                while (index < len && weekStr[index].isDigit()) {
                    r = r * 10 + (weekStr[index] - '0')
                    index++
                }
                if (r > 0) {
                    for (i in l..r) weeks.add(i)
                }
            } else {
                // 单周格式
                if (l > 0) weeks.add(l)
            }
        }
        return weeks
    }
}
