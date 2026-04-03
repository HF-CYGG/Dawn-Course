package com.dawncourse.feature.import_module

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.ScriptSyncRepository
import com.dawncourse.core.domain.usecase.FetchLlmParseStatusUseCase
import com.dawncourse.core.domain.usecase.SubmitLlmParseTaskUseCase
import com.dawncourse.feature.import_module.engine.QiangZhiApiEngine
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * @property showLlmConsentDialog 是否展示云端解析上传确认弹窗
 * @property llmConsentPreview 将上传内容的预览文本
 * @property llmConsentLength 将上传内容的总长度
 * @property llmConsentChecked 用户是否勾选“已知情并同意”
 * @property llmConsentSourceUrl 本次上传对应的网页来源
 * @property llmConsentSchoolName 用户填写的学校名称（用于归类队列）
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
    val isLoading: Boolean = false,
    val showLlmConsentDialog: Boolean = false,
    val llmConsentPreview: String = "",
    val llmConsentLength: Int = 0,
    val llmConsentChecked: Boolean = false,
    val llmConsentSourceUrl: String = "",
    val llmConsentSchoolName: String = ""
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
    private val qiangZhiApiEngine: QiangZhiApiEngine,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val settingsRepository: SettingsRepository,
    private val scriptSyncRepository: ScriptSyncRepository,
    private val submitLlmParseTaskUseCase: SubmitLlmParseTaskUseCase,
    private val fetchLlmParseStatusUseCase: FetchLlmParseStatusUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>()
    val events = _events.asSharedFlow()

    // 用户确认前暂存云端解析的完整内容，避免在 UI 状态中存放大文本
    private var pendingLlmContent: String = ""
    // 本地脚本降级提示节流时间，避免一次提取触发多次相同提示
    private var lastLocalScriptHintAt: Long = 0L
    // 当前脚本拉取任务 ID（用于“按任务去重统计”）
    private var currentScriptPullTaskId: String = ""
    
    init {
        // 初始化学期开始日期为本周一 (符合大多数导入场景)
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val timestamp = monday.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        _uiState.update { it.copy(semesterStartDate = timestamp) }
        viewModelScope.launch {
            val lastImportUrl = settingsRepository.settings.first().lastImportUrl
            if (!lastImportUrl.isNullOrBlank()) {
                _uiState.update { it.copy(webUrl = lastImportUrl) }
            }
        }
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
                isLoading = false,
                showLlmConsentDialog = false,
                llmConsentPreview = "",
                llmConsentLength = 0,
                llmConsentChecked = false,
                llmConsentSourceUrl = "",
                llmConsentSchoolName = ""
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
     * 开始一次脚本拉取任务
     *
     * 每次用户点击“一键提取”都会生成新的任务 ID，
     * 该 ID 会随脚本拉取上报到服务端，用于按任务去重统计。
     */
    fun beginScriptPullTask() {
        currentScriptPullTaskId = "pull_${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    suspend fun getScriptContent(scriptName: String, category: String = "js"): String {
        val fetchResult = scriptSyncRepository.getScriptWithInfo(
            scriptName = scriptName,
            category = category,
            pullTaskId = currentScriptPullTaskId
        )
        if (!fetchResult.fromCloud && fetchResult.content.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastLocalScriptHintAt > 5000) {
                lastLocalScriptHintAt = now
                _uiState.update {
                    it.copy(
                        resultText = "云端脚本拉取失败，正在使用本地脚本（可能不是最新版本）"
                    )
                }
            }
        }
        return fetchResult.content
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
                    var hasParserCrash = false
                    var hasAnyParserAttempt = false
                    val currentUrl = _uiState.value.webUrl
                    if (qiangZhiApiEngine.isQiangZhiHost(currentUrl)) {
                        val qiangZhiCourses = qiangZhiApiEngine.parseHtmlWithJsoup(raw)
                        if (qiangZhiCourses.isNotEmpty()) {
                            return@withContext qiangZhiCourses
                        }
                    }
                    if (raw.contains("qiangzhi_direct")) {
                        return@withContext qiangZhiApiEngine.parseJson(raw)
                    }
                    var courses = parseParsedCoursesFromRaw(raw)
                    if (courses.isEmpty()) {
                        val xiaoai = parseXiaoaiProviderResult(raw)
                        courses = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                    }
                    if (courses.isEmpty()) {
                        val parsers = listOf("qiangzhi.js", "zhengfang.js", "kingosoft.js")
                        val runParserRound: suspend (Boolean) -> List<ParsedCourse> = runParserRound@{ forceRefresh ->
                            val commonUtils = try {
                                if (forceRefresh) {
                                    scriptSyncRepository.fetchAndCacheScript(
                                        scriptName = "common_parser_utils.js",
                                        category = "parsers",
                                        pullTaskId = currentScriptPullTaskId
                                    )
                                } else {
                                    scriptSyncRepository.getScript(
                                        scriptName = "common_parser_utils.js",
                                        category = "parsers",
                                        pullTaskId = currentScriptPullTaskId
                                    )
                                }
                            } catch (_: Exception) {
                                ""
                            }
                            for (parserName in parsers) {
                                hasAnyParserAttempt = true
                                try {
                                    val script = if (forceRefresh) {
                                        scriptSyncRepository.fetchAndCacheScript(
                                            scriptName = parserName,
                                            category = "parsers",
                                            pullTaskId = currentScriptPullTaskId
                                        )
                                    } else {
                                        scriptSyncRepository.getScript(
                                            scriptName = parserName,
                                            category = "parsers",
                                            pullTaskId = currentScriptPullTaskId
                                        )
                                    }
                                    val fullScript = if (commonUtils.isNotEmpty()) "$commonUtils\n$script" else script
                                    val jsonResult = scriptEngine.parseHtml(fullScript, raw)
                                    val parsedDirect = parseParsedCoursesFromRaw(jsonResult)
                                    if (parsedDirect.isNotEmpty()) {
                                        reportParserParseFeedback(parserName, true, null, currentUrl)
                                        return@runParserRound parsedDirect
                                    }
                                    val xiaoai = parseXiaoaiProviderResult(jsonResult)
                                    val parsedFromXiaoai = convertXiaoaiCoursesToParsedCourses(xiaoai.courses)
                                    if (parsedFromXiaoai.isNotEmpty()) {
                                        reportParserParseFeedback(parserName, true, null, currentUrl)
                                        return@runParserRound parsedFromXiaoai
                                    }
                                    reportParserParseFeedback(parserName, false, "empty_result", currentUrl)
                                } catch (e: ScriptEngine.ScriptExecutionException) {
                                    reportParserParseFeedback(
                                        parserName,
                                        false,
                                        e.message ?: "script_execution_exception",
                                        currentUrl
                                    )
                                    hasParserCrash = true
                                } catch (e: Throwable) {
                                    reportParserParseFeedback(
                                        parserName,
                                        false,
                                        e.message ?: e::class.java.simpleName,
                                        currentUrl
                                    )
                                    hasParserCrash = true
                                }
                            }
                            emptyList()
                        }
                        courses = runParserRound(false)
                        if (courses.isEmpty() && hasAnyParserAttempt) {
                            courses = runParserRound(true)
                        }
                    }
                    if (courses.isEmpty() && hasAnyParserAttempt && hasParserCrash) {
                        throw ScriptEngine.ScriptExecutionException("解析器运行失败")
                    }
                    courses
                }
                
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, resultText = "未识别到课程数据。请确认：\n1. 已登录教务系统\n2. 位于个人课表页面\n3. 页面已完全加载") }
                } else {
                    val lastUrl = _uiState.value.webUrl
                    if (lastUrl.isNotBlank()) {
                        settingsRepository.setLastImportUrl(lastUrl)
                    }
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
                val hasConsent = requestLlmConsent(raw)
                if (!hasConsent) {
                    // 给出明确但不泄露敏感信息的提示，并保持“可重试”：
                    // - 不显示底层异常 message（可能包含页面片段/请求信息）
                    // - 用户可直接重新导入，或切换为“文件导入”等方式
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            resultText = "解析失败：解析器运行异常。\n请重试；若仍失败，可尝试文件导入或更换导入方式。"
                        )
                    }
                }
            } catch (_: Throwable) {
                // 兜底：避免把异常细节直接展示给用户
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "解析失败：数据格式不支持或解析过程发生异常。\n请重试；若仍失败，请确认已登录且位于课表页面。"
                    )
                }
            } finally {
                // 结束本次拉取任务，避免后续无关操作串到同一任务统计中
                currentScriptPullTaskId = ""
            }
        }
    }

    /**
     * LLM 异步兜底解析入口
     *
     * 仅在解析器运行失败时触发，避免影响主链路。
     */
    private suspend fun tryLlmFallback(
        content: String,
        schoolName: String,
        schoolSystemType: String,
        sourceUrl: String
    ): List<ParsedCourse> {
        if (content.isBlank()) return emptyList()
        val submitResult = submitLlmParseTaskUseCase(
            content = content,
            schoolName = schoolName,
            schoolSystemType = schoolSystemType,
            sourceUrl = sourceUrl,
            consent = true,
            consentAt = System.currentTimeMillis()
        )
        val taskId = submitResult.taskId
        if (!submitResult.success || taskId.isNullOrBlank()) return emptyList()
        val maxAttempts = if (submitResult.message == "accepted_pending_model_key") 24 else 12
        var continuousRequestFailures = 0
        repeat(maxAttempts) { attempt ->
            val statusResult = fetchLlmParseStatusUseCase(taskId)
            if (!statusResult.success) {
                continuousRequestFailures += 1
                if (continuousRequestFailures >= 3) return emptyList()
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(2000)
                }
                return@repeat
            }
            continuousRequestFailures = 0
            when (statusResult.status) {
                com.dawncourse.core.domain.model.LlmParseStatus.PENDING -> {
                    if (attempt < maxAttempts - 1) {
                        kotlinx.coroutines.delay(5000)
                    }
                }
                com.dawncourse.core.domain.model.LlmParseStatus.SUCCESS -> {
                    val jsonResult = statusResult.resultText.orEmpty()
                    return parseLlmFallbackResult(jsonResult)
                }
                com.dawncourse.core.domain.model.LlmParseStatus.FAILED -> {
                    return emptyList()
                }
                com.dawncourse.core.domain.model.LlmParseStatus.PROCESSING -> {
                    if (attempt < maxAttempts - 1) {
                        kotlinx.coroutines.delay(2500)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun parseLlmFallbackResult(raw: String): List<ParsedCourse> {
        val parsedDirect = parseParsedCoursesFromRaw(raw)
        if (parsedDirect.isNotEmpty()) return parsedDirect
        val parsedXiaoai = convertXiaoaiCoursesToParsedCourses(parseXiaoaiProviderResult(raw).courses)
        if (parsedXiaoai.isNotEmpty()) return parsedXiaoai
        return emptyList()
    }

    private fun reportParserParseFeedback(
        parserName: String,
        success: Boolean,
        errorMessage: String?,
        sourceUrl: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                scriptSyncRepository.reportScriptParseFeedback(
                    scriptName = parserName,
                    category = "parsers",
                    success = success,
                    errorMessage = errorMessage?.take(500),
                    sourceUrl = sourceUrl
                )
            }
        }
    }

    private fun detectSchoolSystemTypeForLlm(content: String, sourceUrl: String): String {
        val text = "${sourceUrl}\n$content".lowercase()
        return when {
            text.contains("jwglxt") || text.contains("zhengfang") || text.contains("正方") -> "zhengfang"
            text.contains("qiangzhi") || text.contains("强智") -> "qiangzhi"
            text.contains("kingosoft") || text.contains("青果") -> "kingosoft"
            text.contains("chaoxing") || text.contains("学习通") || text.contains("超星") -> "chaoxing"
            else -> ""
        }
    }

    /**
     * 请求用户确认云端解析上传
     *
     * 若内容为空则返回 false，表示无需弹窗。
     */
    private fun requestLlmConsent(raw: String): Boolean {
        val sourceUrl = _uiState.value.webUrl
        val sanitized = sanitizeHtmlForLlm(raw)
        if (sanitized.isBlank()) return false
        pendingLlmContent = sanitized
        val preview = sanitized.take(1200)
        val guessedSchoolName = guessSchoolNameForLlm(raw, sanitized)
        _uiState.update {
            it.copy(
                isLoading = false,
                showLlmConsentDialog = true,
                llmConsentPreview = preview,
                llmConsentLength = sanitized.length,
                llmConsentChecked = false,
                llmConsentSourceUrl = sourceUrl,
                llmConsentSchoolName = it.llmConsentSchoolName.ifBlank { guessedSchoolName }
            )
        }
        return true
    }

    /**
     * 尝试从抓取到的网页内容中自动推断学校名称，用于在“云端解析同意弹窗”中做预填。
     *
     * 设计目标：
     * 1. 用户未填写学校名时尽量自动补全，提升服务端队列归类与统计的准确性
     * 2. 不做联网识别，仅从已抓取到的本地 HTML/文本中提取
     * 3. 失败时返回空字符串，保持 UI 可控
     */
    private fun guessSchoolNameForLlm(raw: String, sanitized: String): String {
        val title = if (raw.contains("<title", ignoreCase = true)) {
            Regex("(?is)<title[^>]*>(.*?)</title>")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("\\s+"), "")
                .orEmpty()
        } else {
            ""
        }
        val fromTitle = extractSchoolNameFromText(title)
        if (fromTitle.isNotBlank()) return fromTitle
        return extractSchoolNameFromText(sanitized.take(2000))
    }

    /**
     * 从文本中提取“学校名称”候选。
     *
     * 说明：
     * - 仅匹配常见中文学校后缀（大学/学院/职业技术学院等），避免误把普通词当成学校名
     * - 不做过度归一化（不裁剪后缀），避免不同学校被合并
     */
    private fun extractSchoolNameFromText(text: String): String {
        if (text.isBlank()) return ""
        val compact = text.replace(Regex("\\s+"), "")
        val pattern = Regex(
            "([\\u4e00-\\u9fa5]{2,40}(?:高等专科学校|职业技术学院|职业学院|技术学院|科技学院|师范大学|师范学院|医学院|中医药大学|外国语大学|外语学院|信息工程学院|信息工程大学|交通大学|工业大学|科技大学|财经大学|农业大学|理工大学|大学|学院))"
        )
        return pattern.find(compact)?.groupValues?.getOrNull(1).orEmpty()
    }

    /**
     * 更新用户是否勾选了“已知情并同意”
     */
    fun updateLlmConsentChecked(checked: Boolean) {
        _uiState.update { it.copy(llmConsentChecked = checked) }
    }

    fun updateLlmConsentSchoolName(value: String) {
        _uiState.update { it.copy(llmConsentSchoolName = value) }
    }

    /**
     * 用户确认云端解析上传后执行解析
     */
    fun confirmLlmConsent() {
        val content = pendingLlmContent
        if (content.isBlank()) {
            _uiState.update { it.copy(showLlmConsentDialog = false, llmConsentChecked = false) }
            return
        }
        if (!_uiState.value.llmConsentChecked) {
            _uiState.update { it.copy(resultText = "请先勾选同意后再上传") }
            return
        }
        val schoolNameInput = _uiState.value.llmConsentSchoolName.trim()
        val sourceUrl = _uiState.value.llmConsentSourceUrl
        val schoolSystemType = detectSchoolSystemTypeForLlm(content, sourceUrl)
        val schoolName = if (schoolNameInput.isNotBlank()) {
            schoolNameInput
        } else {
            extractSchoolNameFromText(content)
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showLlmConsentDialog = false,
                    llmConsentChecked = false,
                    isLoading = true,
                    resultText = "已获得授权，正在进行云端解析..."
                )
            }
            val parsed = withContext(Dispatchers.IO) {
                tryLlmFallback(content, schoolName, schoolSystemType, sourceUrl)
            }
            pendingLlmContent = ""
            if (parsed.isNotEmpty()) {
                val lastUrl = _uiState.value.webUrl
                if (lastUrl.isNotBlank()) {
                    settingsRepository.setLastImportUrl(lastUrl)
                }
                val maxSection = parsed.maxOfOrNull { it.endSection } ?: 12
                val safeMaxSection = maxSection.coerceAtLeast(4)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        parsedCourses = parsed,
                        step = ImportStep.Review,
                        detectedMaxSection = safeMaxSection,
                        sectionTimes = generateDefaultSectionTimes(it, safeMaxSection),
                        resultText = "已启用云端解析兜底，成功解析 ${parsed.size} 个课程段"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        resultText = "云端解析失败：请确认页面数据完整或稍后重试。"
                    )
                }
            }
        }
    }

    /**
     * 用户取消云端解析上传
     */
    fun cancelLlmConsent() {
        pendingLlmContent = ""
        _uiState.update {
            it.copy(
                showLlmConsentDialog = false,
                llmConsentChecked = false,
                resultText = "已取消云端解析上传，可继续尝试其他导入方式。"
            )
        }
    }

    /**
     * 对 HTML/文本进行隐私脱敏
     *
     * 规则：
     * 1. 替换学号、姓名、手机号、身份证、邮箱等字段
     * 2. 优先抽取纯文本以减少隐私暴露范围
     * 3. 不截断内容，确保用户同意后可完整上传
     */
    private fun sanitizeHtmlForLlm(raw: String): String {
        var text = raw
        text = text.replace(Regex("(?is)<script\\b[^>]*>(.*?)</script>"), "<script>***</script>")
        text = text.replace(Regex("(?is)<style\\b[^>]*>(.*?)</style>"), "<style>***</style>")
        text = text.replace(Regex("(?i)(password|passwd|pwd|mm|hidMm|token|csrf|session(id)?)\\s*=\\s*['\"][^'\"]{1,}['\"]"), "$1=\"***\"")
        text = text.replace(Regex("(?i)(token|csrf|session(id)?)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-\\.]{6,}['\"]?"), "$1=***")
        text = text.replace(Regex("(?i)(学号|学籍号|账号|用户名|用户号)\\s*[:：]?\\s*\\w{4,}"), "\$1:***")
        text = text.replace(Regex("(?i)(姓名)\\s*[:：]?\\s*[\\u4e00-\\u9fa5A-Za-z·\\s]{2,}"), "\$1:***")
        text = text.replace(Regex("\\b\\d{17}[0-9Xx]\\b"), "******************")
        text = text.replace(Regex("\\b1[3-9]\\d{9}\\b"), "***********")
        text = text.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "***@***")
        val structuralSummary = buildTimetableStructureSummary(raw)
        return if (structuralSummary.isBlank()) text else "$text\n\n$structuralSummary"
    }

    private fun buildTimetableStructureSummary(raw: String): String {
        val tableMatches = Regex("(?is)<table\\b[^>]*>(.*?)</table>").findAll(raw).toList()
        if (tableMatches.isEmpty()) return ""
        val lines = mutableListOf<String>()
        lines.add("[TIMETABLE_STRUCTURE]")
        tableMatches.take(4).forEachIndexed { index, match ->
            val tableHtml = match.groupValues.getOrNull(1).orEmpty()
            val rowCount = Regex("(?is)<tr\\b").findAll(tableHtml).count()
            val headerCells = Regex("(?is)<th\\b[^>]*>(.*?)</th>")
                .findAll(tableHtml)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { it.replace(Regex("(?is)<[^>]+>"), "").replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .take(12)
                .toList()
            lines.add("table_${index + 1}: rows=$rowCount")
            if (headerCells.isNotEmpty()) {
                lines.add("table_${index + 1}_headers=${headerCells.joinToString("|")}")
            }
        }
        return lines.joinToString("\n")
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
                    val lastUrl = _uiState.value.webUrl
                    if (lastUrl.isNotBlank()) {
                        settingsRepository.setLastImportUrl(lastUrl)
                    }
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
                
                courseRepository.deleteAllCourses()
                semesterRepository.deleteAllSemesters()

                val newSemester = Semester(
                    name = "导入学期 ${LocalDate.now()}",
                    startDate = state.semesterStartDate,
                    weekCount = state.weekCount,
                    isCurrent = true
                )
                val semesterId = semesterRepository.insertSemester(newSemester)
                
                settingsRepository.setCurrentSemesterName(newSemester.name)
                settingsRepository.setStartDateTimestamp(newSemester.startDate)
                settingsRepository.setTotalWeeks(newSemester.weekCount)
                
                val domainCourses = state.parsedCourses.map { parsed ->
                    parsed.toDomainCourse().copy(semesterId = semesterId)
                }
                courseRepository.insertCourses(domainCourses)
                
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
                var importSource = "API"
                val parsedCourses = withContext(Dispatchers.IO) {
                    val normalizedBaseUrl = qiangZhiApiEngine.normalizeBaseUrl(baseUrl)
                    if (normalizedBaseUrl.isBlank()) {
                        throw Exception("教务系统地址不正确")
                    }
                    if (studentId.isBlank() || password.isBlank()) {
                        throw Exception("学号或密码不能为空")
                    }

                    try {
                        // 1. 尝试标准 API 导入
                        val token = qiangZhiApiEngine.authUser(normalizedBaseUrl, studentId, password)
                        val currentInfo = qiangZhiApiEngine.getCurrentTime(normalizedBaseUrl, token)
                        val xnxqh = currentInfo.optString("xnxqh")
                        if (xnxqh.isBlank()) {
                            throw Exception("无法获取学年学期信息")
                        }
                        val targetWeeks = totalWeeks.coerceIn(1, 30)
                        val allCourses = mutableListOf<XiaoaiCourse>()
                        for (week in 1..targetWeeks) {
                            val weekArray = qiangZhiApiEngine.getCourseArray(
                                baseUrl = normalizedBaseUrl,
                                token = token,
                                studentId = studentId,
                                xnxqh = xnxqh,
                                week = week
                            )
                            allCourses.addAll(qiangZhiApiEngine.parseApiCourses(weekArray))
                        }
                        val merged = qiangZhiApiEngine.mergeCourses(allCourses)
                        convertXiaoaiCoursesToParsedCourses(merged)
                    } catch (apiError: Exception) {
                        // 2. API 失败时，尝试 Web 模拟登录兜底
                        val msg = apiError.message ?: ""
                        val shouldFallback = msg.contains("登录状态已过期") ||
                            msg.contains("API 接口返回了网页") ||
                            msg.contains("网页导入") ||
                            msg.contains("未开放移动端") ||
                            msg.contains("防火墙") ||
                            msg.contains("HTTP Error 404") ||
                            msg.contains("HTTP Error 500")
                        if (shouldFallback) {
                             // 仅在明确是 API 不可用或返回 HTML 时尝试 Web 导入
                             val cookie = qiangZhiApiEngine.loginWeb(normalizedBaseUrl, studentId, password)
                             val html = qiangZhiApiEngine.fetchHtmlTimetable(normalizedBaseUrl, cookie)
                             // parseHtmlWithJsoup 直接返回 List<ParsedCourse>，无需转换
                             importSource = "Web"
                             return@withContext qiangZhiApiEngine.parseHtmlWithJsoup(html)
                        }
                        throw apiError // 如果不是特定错误，或者 Web 导入也未执行，抛出原异常
                    }
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
                            resultText = "强智 $importSource 导入成功: ${parsedCourses.size} 个课程段"
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







}
