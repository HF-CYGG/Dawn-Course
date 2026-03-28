@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.with
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.ui.components.AnimatedDropdownMenu
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.feature.import_module.model.toDomainCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import javax.inject.Inject

/**
 * 正方教务“自动更新（实验）”页面
 *
 * 设计目标：
 * - 自动使用已绑定的“入口地址 + 用户名 + 密码”进行登录
 * - 注入现有适配脚本 (qidi_provider.js) 提取课程
 * - 直接覆盖当前学期的课程数据
 *
 * 安全原则：
 * - 不打印用户名/密码/页面敏感内容
 * - 仅在用户主动点击时进行登录与提取
 */

/**
 * 同步日志类型
 */
private enum class SyncLogType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    ACTION
}

/**
 * 同步日志数据
 */
private data class SyncLog(
    val time: String,
    val message: String,
    val type: SyncLogType
)

/**
 * 同步日志颜色映射
 */
private fun logColor(type: SyncLogType): Color {
    return when (type) {
        SyncLogType.INFO -> Color(0xFFB0B0B0)
        SyncLogType.SUCCESS -> Color(0xFF4CAF50)
        SyncLogType.WARNING -> Color(0xFFFFC107)
        SyncLogType.ERROR -> Color(0xFFF44336)
        SyncLogType.ACTION -> Color(0xFF00BCD4)
    }
}

private enum class SyncStep {
    Login,
    SelectTerm,
    Compare,
    Done
}

private enum class StepState {
    IDLE,
    ACTIVE,
    COMPLETED
}

enum class DiffType {
    Added,
    Removed
}

data class CourseFingerprint(
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val duration: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int
)

data class DiffItem(
    val type: DiffType,
    val fingerprint: CourseFingerprint,
    val count: Int
)

data class PartialUpdateResult(
    val added: Int,
    val removed: Int
)

private fun toFingerprint(course: Course): CourseFingerprint {
    return CourseFingerprint(
        name = course.name,
        teacher = course.teacher,
        location = course.location,
        dayOfWeek = course.dayOfWeek,
        startSection = course.startSection,
        duration = course.duration,
        startWeek = course.startWeek,
        endWeek = course.endWeek,
        weekType = course.weekType
    )
}

private fun toFingerprint(course: ParsedCourse): CourseFingerprint {
    return CourseFingerprint(
        name = course.name,
        teacher = course.teacher,
        location = course.location,
        dayOfWeek = course.dayOfWeek,
        startSection = course.startSection,
        duration = course.duration,
        startWeek = course.startWeek,
        endWeek = course.endWeek,
        weekType = course.weekType
    )
}

private fun buildDiffItems(
    parsed: List<ParsedCourse>,
    existing: List<Course>
): List<DiffItem> {
    val newCounts = LinkedHashMap<CourseFingerprint, Int>()
    val oldCounts = LinkedHashMap<CourseFingerprint, Int>()
    parsed.forEach { course ->
        val fp = toFingerprint(course)
        newCounts[fp] = (newCounts[fp] ?: 0) + 1
    }
    existing.forEach { course ->
        val fp = toFingerprint(course)
        oldCounts[fp] = (oldCounts[fp] ?: 0) + 1
    }
    val items = mutableListOf<DiffItem>()
    newCounts.forEach { (fp, count) ->
        val oldCount = oldCounts[fp] ?: 0
        val diff = count - oldCount
        if (diff > 0) {
            items.add(DiffItem(DiffType.Added, fp, diff))
        }
    }
    oldCounts.forEach { (fp, count) ->
        val newCount = newCounts[fp] ?: 0
        val diff = count - newCount
        if (diff > 0) {
            items.add(DiffItem(DiffType.Removed, fp, diff))
        }
    }
    return items
}

@Composable
fun QidiAutoSyncScreen(
    onBackClick: () -> Unit,
    onFinish: () -> Unit,
    provider: SyncProviderType = SyncProviderType.ZF,
    viewModel: QidiSyncViewModel = hiltViewModel(),
    importViewModel: ImportViewModel = hiltViewModel()
){
    val providerName = when (provider) {
        SyncProviderType.ZF -> "正方"
        else -> "教务"
    }
    var title by remember { mutableStateOf("$providerName 自动更新（实验）") }
    var subTitle by remember { mutableStateOf("仅支持新正方系统，功能为实验性质，有待进一步测试") }
    var loading by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    var credsForAutoFill by remember { mutableStateOf<com.dawncourse.core.domain.model.SyncCredentials?>(null) }
    var needManualLogin by remember { mutableStateOf(false) }
    var addressBar by remember { mutableStateOf("") }
    // 同步目标学年（用于正方查询参数）
    var targetYear by remember { mutableStateOf(LocalDate.now().year.toString()) }
    // 同步目标学期（用于正方查询参数）
    var targetTerm by remember { mutableStateOf("1") }
    // 已应用学年文本（用于完成态摘要展示）
    var appliedYearLabel by remember { mutableStateOf("") }
    // 已应用学期文本（用于完成态摘要展示）
    var appliedTermLabel by remember { mutableStateOf("") }
    var pendingLoadUrl by remember { mutableStateOf<String?>(null) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("等待开始") }
    val logItems = remember { mutableStateListOf<SyncLog>() }
    val logListState = rememberLazyListState()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    var captchaUrl by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }
    var webUserAgent by remember { mutableStateOf<String?>(null) }
    val sheetScrollState = rememberScrollState()
    val captchaBringRequester = remember { BringIntoViewRequester() }
    var lastAutoFillUrl by remember { mutableStateOf("") }
    var lastZfMenuJumpUrl by remember { mutableStateOf("") }
    var triedJwglxtFallback by remember { mutableStateOf(false) }
    var endpointCheckFailed by remember { mutableStateOf(false) }
    val mainScrollState = rememberScrollState()
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var termMenuExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden) {
                if (loading || needManualLogin) {
                    false
                } else {
                    showProgressDialog = false
                    true
                }
            } else {
                true
            }
        }
    )
    var syncStep by remember { mutableStateOf(SyncStep.Login) }
    // 入口地址编辑弹窗开关
    var showEndpointDialog by remember { mutableStateOf(false) }
    // 入口地址编辑文本
    var endpointInput by remember { mutableStateOf("") }
    var availableYears by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var availableTerms by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedYear by remember { mutableStateOf("") }
    var selectedTerm by remember { mutableStateOf("") }
    var showDiffDialog by remember { mutableStateOf(false) }
    var showDiffSelectDialog by remember { mutableStateOf(false) }
    var showNoDiffDialog by remember { mutableStateOf(false) }
    // 未设置当前学期提示弹窗开关
    var showNoSemesterDialog by remember { mutableStateOf(false) }
    var showNeedInitialImportDialog by remember { mutableStateOf(false) }
    var diffItems by remember { mutableStateOf<List<DiffItem>>(emptyList()) }
    val diffSelections = remember { mutableStateListOf<Boolean>() }
    var pendingParsedCourses by remember { mutableStateOf<List<ParsedCourse>>(emptyList()) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorDialogText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val autoUpdateSupported = provider == SyncProviderType.ZF
    var pageStatePollingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun addLog(message: String, type: SyncLogType = SyncLogType.INFO) {
        logItems.add(SyncLog(LocalTime.now().format(timeFormatter), message, type))
    }

    fun buildErrorPayload(reason: String, throwable: Throwable? = null): String {
        val logs = logItems.takeLast(12).joinToString("\n") { item ->
            "[${item.time}] ${item.type.name} ${item.message}"
        }
        val exceptionText = if (throwable != null) {
            "${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}".trim()
        } else {
            ""
        }
        return listOf(
            "原因：$reason",
            "当前步骤：$currentStep",
            "页面地址：$addressBar",
            "异常信息：$exceptionText".trim(),
            "最近日志：\n$logs"
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun reportError(reason: String, throwable: Throwable? = null) {
        errorDialogText = buildErrorPayload(reason, throwable)
        showErrorDialog = true
    }

    fun applyOptionSnapshot(raw: String) {
        val root = JSONObject(raw)
        val yearArray = root.optJSONArray("years") ?: JSONArray()
        val termArray = root.optJSONArray("terms") ?: JSONArray()
        val years = mutableListOf<Pair<String, String>>()
        val terms = mutableListOf<Pair<String, String>>()
        for (i in 0 until yearArray.length()) {
            val item = yearArray.optJSONObject(i) ?: continue
            val value = item.optString("value")
            val text = item.optString("text")
            if (value.isNotBlank()) {
                years.add(value to if (text.isNotBlank()) text else value)
            }
        }
        for (i in 0 until termArray.length()) {
            val item = termArray.optJSONObject(i) ?: continue
            val value = item.optString("value")
            val text = item.optString("text")
            if (value.isNotBlank()) {
                terms.add(value to if (text.isNotBlank()) text else value)
            }
        }
        availableYears = years
        availableTerms = terms
        val yearValue = root.optString("yearValue")
        val termValue = root.optString("termValue")
        if (years.isNotEmpty()) {
            selectedYear = years.firstOrNull { it.first == yearValue }?.first ?: years.first().first
        }
        if (terms.isNotEmpty()) {
            selectedTerm = terms.firstOrNull { it.first == termValue }?.first ?: terms.first().first
        }
    }

    fun applySelectedTermToPage(onApplied: () -> Unit = {}) {
        val wv = webView
        if (wv == null) {
            addLog("WebView 未就绪", SyncLogType.WARNING)
            return
        }
        if (selectedYear.isBlank() || selectedTerm.isBlank()) {
            addLog("请先选择学年与学期", SyncLogType.WARNING)
            return
        }
        // 学期显示值映射：修复部分教务系统学期值为 3/12 的显示问题
        fun mapTermDisplay(value: String, label: String): String {
            if (label.isNotBlank() && label != value) return label
            return when (value) {
                "3" -> "1"
                "12" -> "2"
                else -> label.ifBlank { value }
            }
        }
        scope.launch {
            val js = """
                (function(){
                  try{
                    // 修复：同时兼容 id 与 name 的学年学期下拉框
                    var y=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                    var t=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                    if(y){ y.value='${escapeJs(selectedYear)}'; }
                    if(t){ t.value='${escapeJs(selectedTerm)}'; }
                    if(y){
                      var e=document.createEvent('HTMLEvents');
                      e.initEvent('change', true, false);
                      y.dispatchEvent(e);
                    }
                    if(t){
                      var e2=document.createEvent('HTMLEvents');
                      e2.initEvent('change', true, false);
                      t.dispatchEvent(e2);
                    }
                    return 'ok';
                  }catch(e){ return 'fail'; }
                })();
            """.trimIndent()
            val res = suspendEvaluateJs(wv, js)
            val txt = parseJsReturn(res)
            if (txt == "ok") {
                targetYear = selectedYear
                targetTerm = selectedTerm
                val yearLabel = availableYears.firstOrNull { it.first == selectedYear }?.second ?: selectedYear
                val termLabel = availableTerms.firstOrNull { it.first == selectedTerm }?.second ?: selectedTerm
                appliedYearLabel = yearLabel
                appliedTermLabel = mapTermDisplay(selectedTerm, termLabel)
                addLog("已应用学年学期选择", SyncLogType.SUCCESS)
                onApplied()
            } else {
                addLog("应用学年学期失败", SyncLogType.WARNING)
            }
        }
    }

    fun formatWeekType(type: Int): String {
        return when (type) {
            1 -> "单周"
            2 -> "双周"
            else -> "全周"
        }
    }

    fun formatDiffItem(item: DiffItem): String {
        val fp = item.fingerprint
        val base = listOf(fp.name, fp.teacher, fp.location).filter { it.isNotBlank() }.joinToString(" ")
        val weeks = "${fp.startWeek}-${fp.endWeek}周${formatWeekType(fp.weekType)}"
        val sections = "周${fp.dayOfWeek} 第${fp.startSection}节 起 ${fp.duration}节"
        val prefix = if (item.type == DiffType.Added) "新增" else "移除"
        return if (item.count > 1) {
            "$prefix×${item.count} $base $weeks $sections"
        } else {
            "$prefix $base $weeks $sections"
        }
    }

    fun startSyncFlow() {
        scope.launch {
            showProgressDialog = true
            currentStep = "读取绑定凭据"
            addLog("开始读取绑定凭据", SyncLogType.INFO)
            val creds = viewModel.loadQidiCredentials()
            val needProvider = provider
            if (creds == null || creds.provider != needProvider || creds.type != SyncCredentialType.PASSWORD) {
                subTitle = "未绑定${providerName}凭据，请在设置中绑定"
                currentStep = "未绑定账号"
                addLog("未绑定${providerName}凭据", SyncLogType.WARNING)
                reportError("未绑定${providerName}凭据")
                return@launch
            }
            val endpoint = creds.endpointUrl
            credsForAutoFill = creds
            addLog("已读取绑定凭据", SyncLogType.INFO)
            val wv = webView ?: return@launch
            if (endpoint.isNullOrBlank()) {
                loading = false
                currentStep = "等待输入入口"
                subTitle = "请设置${providerName}教务入口地址"
                showEndpointDialog = true
                addLog("入口地址为空，等待用户输入", SyncLogType.WARNING)
                reportError("入口地址为空，无法自动更新")
                return@launch
            }
            loading = true
            subTitle = "正在打开${providerName}教务系统..."
            currentStep = "打开教务系统"
            addLog("准备加载入口地址", SyncLogType.INFO)
            val normalized = normalizeEndpointForLoad(endpoint)
            if (normalized.isNullOrBlank()) {
                loading = false
                subTitle = "入口地址无效，请在设置中重新绑定"
                currentStep = "入口地址无效"
                addLog("入口地址无效", SyncLogType.WARNING)
                reportError("入口地址无效")
                return@launch
            }
            triedJwglxtFallback = false
            endpointCheckFailed = false
            addressBar = normalized
            wv.loadUrl(normalized)
            addLog("开始加载入口：$normalized", SyncLogType.INFO)
        }
    }

    fun startExtractFlow() {
        scope.launch {
            showProgressDialog = true
            currentStep = "注入脚本并提取课程"
            addLog("开始提取课程", SyncLogType.ACTION)
            loading = true
            subTitle = "正在提取课程..."
            val wv = webView ?: run {
                loading = false
                subTitle = "WebView 未初始化"
                currentStep = "WebView 未初始化"
                addLog("WebView 未初始化", SyncLogType.ERROR)
                reportError("WebView 未初始化")
                return@launch
            }
            try {
                val context = wv.context
                val assets = context.assets
                val outputConsole = assets.open("js/output_console.js").bufferedReader().use { it.readText() }
                val courseUtils = assets.open("js/course_utils.js").bufferedReader().use { it.readText() }
                val qidiProvider = if (provider == SyncProviderType.QIDI) {
                    // 起迪：尝试使用 provider 脚本
                    runCatching { assets.open("js/qidi_provider.js").bufferedReader().use { it.readText() } }.getOrNull()
                } else null
                addLog("已加载解析脚本", SyncLogType.INFO)
                val js = buildString {
                    append("(function(){try{\n")
                    append("window.__dawnResult=null;window.__dawnReady=false;\n")
                    append(outputConsole).append("\n;\n")
                    append(courseUtils).append("\n;\n")
                    if (qidiProvider != null) append(qidiProvider).append("\n;\n")
                    if (provider == SyncProviderType.ZF) {
                        val y = targetYear.trim()
                        val q = targetTerm.trim()
                        val termCode = when (q) {
                            "1" -> "3"
                            "2" -> "12"
                            "3" -> "16"
                            "12", "16" -> q
                            else -> ""
                        }
                        append(
                            """
                            async function waitForSelector(sel, timeout){
                              return new Promise((resolve,reject)=>{
                                var t=0; var it=setInterval(function(){
                                  var el=document.querySelector(sel);
                                  if(el){ clearInterval(it); resolve(el); }
                                  t+=200; if(t>=timeout){ clearInterval(it); resolve(null); }
                                },200);
                              });
                            }
                            async function waitForCourseData(timeout){
                              return new Promise((resolve,reject)=>{
                                var t=0; var it=setInterval(function(){
                                  var hasItem = !!document.querySelector('.timetable_con')
                                    || !!document.querySelector('#kblist_table .timetable_con')
                                    || !!document.querySelector('#kbgrid_table_0 td[id] .timetable_con')
                                    || !!document.querySelector('td[id^="1-"] .timetable_con');
                                  if(hasItem){ clearInterval(it); resolve(true); }
                                  t+=200; if(t>=timeout){ clearInterval(it); resolve(false); }
                                },200);
                              });
                            }
                            async function openZfTimetableAndQuery(){
                              if(document.querySelector('#ylkbTable')
                                || document.querySelector('#ajaxForm')
                                || document.querySelector('#kbtable')
                                || document.querySelector('#kblist')
                                || document.querySelector('#kblist_table')
                                || document.querySelector('#kbgrid_table_0')){
                              }else{
                                var navLink = (function(){
                                  try{
                                    var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                                    if(r && r.singleNodeValue){ return r.singleNodeValue; }
                                  }catch(e){}
                                  return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
                                })();
                                if(navLink){ navLink.click(); }
                                if(!navLink){
                                  var link = Array.from(document.querySelectorAll('a')).find(a=>{
                                    var href=(a.getAttribute('href')||'').toLowerCase();
                                    var txt=(a.textContent||'').trim();
                                  return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
                                    || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
                                  });
                                  if(link){ link.click(); }
                                }
                                await waitForSelector('#ajaxForm, #ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
                              }
                              
                              try{
                                // 修复：同时兼容 id 与 name 的学年学期下拉框
                                var xnm=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                                var xqm=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                                var fixedYear='${y}';
                                var fixedTerm='${termCode}';
                                if(xnm){
                                  if(fixedYear && fixedYear.length===4){ xnm.value=fixedYear; }
                                  if(!xnm.value){ for(var i=xnm.options.length-1;i>=0;i--){ if(xnm.options[i].value){ xnm.value=xnm.options[i].value; break; } } }
                                  xnm.dispatchEvent(new Event('change',{bubbles:true}));
                                }
                                if(xqm){
                                  if(fixedTerm){ xqm.value=fixedTerm; }
                                  if(!xqm.value){ for(var j=xqm.options.length-1;j>=0;j--){ if(xqm.options[j].value){ xqm.value=xqm.options[j].value; break; } } }
                                  xqm.dispatchEvent(new Event('change',{bubbles:true}));
                                }
                              }catch(e){}
                              
                              // 4) 点击查询按钮
                              var btn=document.querySelector('#search_go') || Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').indexOf('查询')>=0);
                              if(btn){ btn.click(); }
                              
                              // 5) 等待表格渲染
                              await waitForSelector('#ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
                              await waitForCourseData(12000);
                            }
                            """.trimIndent()
                        )
                    }
                    append(
                        """
                        (async function(){
                          try{
                            if(typeof scheduleHtmlProvider==='function' && ${if (provider == SyncProviderType.ZF) "false" else "true"}){
                              var result=await scheduleHtmlProvider();
                              if(result!=="do not continue"){
                                window.__dawnResult=result;window.__dawnReady=true;return;
                              }
                            }
                            ${if (provider == SyncProviderType.ZF) "await openZfTimetableAndQuery();" else ""}
                          }catch(e){}
                          window.__dawnResult=document.documentElement.outerHTML;window.__dawnReady=true;
                        })();
                        """.trimIndent()
                    )
                    append("\n}catch(e){window.__dawnResult=null;window.__dawnReady=true;}})();")
                }
                wv.evaluateJavascript(js, null)
                addLog("已注入提取脚本", SyncLogType.ACTION)
                var attempts = 0
                var raw = ""
                while (attempts < 40) {
                    val r = suspendEvaluateJs(wv, "window.__dawnReady?window.__dawnResult:null")
                    val parsed = parseJsReturn(r)
                    if (parsed.isNotBlank()) {
                        raw = parsed
                        break
                    }
                    if (attempts % 10 == 0) {
                        addLog("等待页面返回数据：${attempts}/40", SyncLogType.INFO)
                    }
                    attempts++
                    kotlinx.coroutines.delay(300)
                }
                if (raw.isBlank()) {
                    subTitle = "未能提取到有效内容，请确认已登录并处于课表页"
                    loading = false
                    currentStep = "未提取到有效内容"
                    addLog("未提取到有效内容", SyncLogType.ERROR)
                    reportError("未提取到有效内容")
                    return@launch
                }
                addLog("页面数据已获取", SyncLogType.SUCCESS)
                importViewModel.parseResultFromWebView(raw)
                addLog("开始解析课程数据", SyncLogType.INFO)
                var wait = 0
                var parsed: List<ParsedCourse> = emptyList()
                while (wait < 40) {
                    val ui = importViewModel.uiState.value
                    parsed = ui.parsedCourses
                    if (parsed.isNotEmpty()) break
                    if (wait % 10 == 0) {
                        addLog("等待解析结果：${wait}/40", SyncLogType.INFO)
                    }
                    wait++
                    kotlinx.coroutines.delay(200)
                }
                if (parsed.isEmpty()) {
                    subTitle = "解析失败或未发现课程"
                    loading = false
                    currentStep = "解析失败"
                    addLog("解析失败或未发现课程", SyncLogType.ERROR)
                    reportError("解析失败或未发现课程")
                    return@launch
                }
                currentStep = "对比课表"
                addLog("解析完成，课程数：${parsed.size}", SyncLogType.SUCCESS)
                addLog("开始对比当前课表", SyncLogType.INFO)
                pendingParsedCourses = parsed
                var semesterId = viewModel.getCurrentSemesterId()
                if (semesterId == null) {
                    // 自动兜底：存在学期但未标记当前时，自动设定第一个学期为当前
                    val allSemesters = viewModel.getAllSemesters()
                    if (allSemesters.isNotEmpty()) {
                        val firstSemester = allSemesters.first()
                        viewModel.setCurrentSemester(firstSemester.id)
                        semesterId = viewModel.getCurrentSemesterId()
                    }
                }
                if (semesterId == null) {
                    loading = false
                    currentStep = "操作中止：未设置本地学期"
                    subTitle = "请先在设置中配置当前学期"
                    addLog("检测到本地未设置当前学期，无法保存课表", SyncLogType.ERROR)
                    // 丝滑收起底部面板，避免用户误判为闪退
                    runCatching { sheetState.hide() }
                    showProgressDialog = false
                    showNoSemesterDialog = true
                    return@launch
                }
                val existing = viewModel.getCoursesBySemester(semesterId)
                if (existing.isEmpty()) {
                    loading = false
                    currentStep = "需要先导入课表"
                    subTitle = "请先通过教务系统导入一次课表"
                    addLog("当前学期尚无课表数据，需先完成首次导入", SyncLogType.WARNING)
                    runCatching { sheetState.hide() }
                    showProgressDialog = false
                    showNeedInitialImportDialog = true
                    return@launch
                }
                val diff = buildDiffItems(parsed, existing)
                diffItems = diff
                diffSelections.clear()
                diffSelections.addAll(List(diff.size) { true })
                loading = false
                if (diff.isEmpty()) {
                    currentStep = "课表无变动"
                    subTitle = "课表无变动"
                    addLog("课表无变动", SyncLogType.SUCCESS)
                    sheetState.hide()
                    showProgressDialog = false
                    showNoDiffDialog = true
                    syncStep = SyncStep.Done
                } else {
                    currentStep = "等待选择同步方式"
                    subTitle = "已获取课表，请选择同步方式"
                    addLog("发现差异：${diff.size} 项", SyncLogType.WARNING)
                    sheetState.hide()
                    showProgressDialog = false
                    showDiffSelectDialog = true
                    syncStep = SyncStep.Done
                }
            } catch (_: Throwable) {
                loading = false
                subTitle = "提取失败：请重试或更换入口"
                currentStep = "提取失败"
                addLog("提取失败", SyncLogType.ERROR)
                reportError("提取失败")
            }
        }
    }

    fun fetchSelectableOptions() {
        val wv = webView
        if (wv == null) {
            addLog("WebView 未就绪", SyncLogType.WARNING)
            return
        }
        scope.launch {
            val res = suspendEvaluateJs(
                wv,
                """
                (function(){
                  try{
                    // 修复：同时兼容 id 与 name 的学年学期下拉框
                    var y=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                    var t=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                    var years=[];
                    var terms=[];
                    if(y && y.options){
                      for(var i=0;i<y.options.length;i++){
                        var o=y.options[i];
                        if(o && o.value){ years.push({value:o.value,text:o.text}); }
                      }
                    }
                    if(t && t.options){
                      for(var j=0;j<t.options.length;j++){
                        var o2=t.options[j];
                        if(o2 && o2.value){ terms.push({value:o2.value,text:o2.text}); }
                      }
                    }
                    var yv = y ? y.value : '';
                    var tv = t ? t.value : '';
                    return JSON.stringify({years:years,terms:terms,yearValue:yv,termValue:tv});
                  }catch(e){ return JSON.stringify({years:[],terms:[],yearValue:'',termValue:''}); }
                })();
                """.trimIndent()
            )
            val txt = parseJsReturn(res)
            if (txt.isNotBlank()) {
                applyOptionSnapshot(txt)
                addLog("已读取学年与学期选项", SyncLogType.SUCCESS)
                if (showProgressDialog) {
                    runCatching { sheetState.hide() }
                    showProgressDialog = false
                }
            } else {
                addLog("未读取到学年学期选项", SyncLogType.WARNING)
            }
        }
    }

    fun pollPageStateIfNeeded() {
        if (syncStep != SyncStep.Login || needManualLogin) return
        val wv = webView ?: return
        pageStatePollingJob?.cancel()
        pageStatePollingJob = scope.launch {
            repeat(30) {
                if (syncStep != SyncStep.Login || needManualLogin) return@launch
                val res = suspendEvaluateJs(
                    wv,
                    """
                    (function(){
                      try{
                        var y=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                        var t=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                        var hasSelect = !!(y || t);
                        var hasTable = !!document.querySelector('#ylkbTable')
                          || !!document.querySelector('#ajaxForm')
                          || !!document.querySelector('#kbtable')
                          || !!document.querySelector('#kblist')
                          || !!document.querySelector('#kblist_table')
                          || !!document.querySelector('#kbgrid_table_0');
                          
                        var tips=document.querySelector('#tips');
                        var tipTxt=tips? (tips.innerText||'') : '';
                        var hasWrong = tipTxt.indexOf('用户名或密码不正确')>=0 || tipTxt.indexOf('错误')>=0 || tipTxt.indexOf('不存在')>=0;
                        if(tips && tips.style.display !== 'none' && tipTxt.length > 0) {
                            hasWrong = true;
                        }
                        var bootbox = document.querySelector('.bootbox-body');
                        if(bootbox && bootbox.innerText.length > 0) {
                            hasWrong = true;
                            tipTxt = bootbox.innerText;
                        }
                        
                        var yzmDiv=document.querySelector('#yzmDiv');
                        var yzmVis=false;
                        if(yzmDiv){
                          var cs=getComputedStyle(yzmDiv);
                          yzmVis = !(cs.display==='none' || cs.visibility==='hidden');
                        }
                        var yzmInput = document.querySelector('#yzm') || document.querySelector('input[name="yzm"]') || document.querySelector('input[id*="yzm"]');
                        if(yzmInput){ 
                           var cs=getComputedStyle(yzmInput);
                           if(cs.display!=='none' && cs.visibility!=='hidden') yzmVis = true; 
                        }
                        var yzmImg = document.querySelector('#yzmPic') || document.querySelector('img[src*="yzm"]') || document.querySelector('img[src*="captcha"]') || document.querySelector('img[src*="validate"]');
                        var yzmSrc = '';
                        if(yzmImg && yzmVis){
                          var s = yzmImg.getAttribute('src') || '';
                          try{ yzmSrc = new URL(s, location.href).href; }catch(e){ yzmSrc = s; }
                        }
                        
                        var navLink = (function(){
                          try{
                            var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                            if(r && r.singleNodeValue){ return r.singleNodeValue; }
                          }catch(e){}
                          return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
                        })();
                        var menuLink = Array.from(document.querySelectorAll('a')).find(a=>{
                          var href=(a.getAttribute('href')||'').toLowerCase();
                          var txt=(a.textContent||'').trim();
                          return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
                            || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
                        });
                        var hasMenu = !!(navLink || menuLink);

                        return JSON.stringify({
                            hasSelect:hasSelect, 
                            hasTable:hasTable,
                            wrong:hasWrong,
                            errorMsg:tipTxt,
                            yzm:yzmVis,
                            yzmSrc:yzmSrc,
                            hasMenu:hasMenu
                        });
                      }catch(e){ return JSON.stringify({hasSelect:false, hasTable:false, wrong:false, yzm:false, hasMenu:false}); }
                    })();
                    """.trimIndent()
                )
                val txt = parseJsReturn(res)
                val hasSelect = txt.contains("\"hasSelect\":true")
                val hasTable = txt.contains("\"hasTable\":true")
                val wrong = txt.contains("\"wrong\":true")
                val yzm = txt.contains("\"yzm\":true")
                val hasMenu = txt.contains("\"hasMenu\":true")
                
                if (wrong || yzm) {
                    needManualLogin = true
                    val yzmSrcMatch = Regex("\"yzmSrc\":\"(.*?)\"").find(txt)
                    val yzmSrc = yzmSrcMatch?.groups?.get(1)?.value?.replace("\\/", "/").orEmpty()
                    if (yzm && yzmSrc.isNotBlank()) {
                        captchaUrl = yzmSrc
                    }
                    val errorMsgMatch = Regex("\"errorMsg\":\"(.*?)\"").find(txt)
                    val errorMsg = errorMsgMatch?.groups?.get(1)?.value.orEmpty()
                    
                    subTitle = "登录异常，请手动处理${if(errorMsg.isNotBlank()) " ($errorMsg)" else ""}"
                    if (showProgressDialog) {
                        currentStep = "需要验证码或手动登录"
                        addLog("检测到动态错误或验证码", SyncLogType.WARNING)
                    }
                    return@launch
                }

                if (hasSelect || hasTable) {
                    syncStep = SyncStep.SelectTerm
                    subTitle = "已进入课表页面，请选择学年与学期"
                    fetchSelectableOptions()
                    return@launch
                }
                
                if (hasMenu) {
                    val openMenuJs = """
                        (function(){
                          try{
                            var navLink = (function(){
                              try{
                                var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                                if(r && r.singleNodeValue){ return r.singleNodeValue; }
                              }catch(e){}
                              return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
                            })();
                            if(navLink){ navLink.click(); return 'ok'; }
                            var link = Array.from(document.querySelectorAll('a')).find(a=>{
                              var href=(a.getAttribute('href')||'').toLowerCase();
                              var txt=(a.textContent||'').trim();
                              return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
                                || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
                            });
                            if(link){ link.click(); return 'ok'; }
                            return 'none';
                          }catch(e){ return 'err'; }
                        })();
                    """.trimIndent()
                    wv.evaluateJavascript(openMenuJs, null)
                }

                kotlinx.coroutines.delay(1000)
            }
        }
    }

    LaunchedEffect(logItems.size) {
        if (logItems.isNotEmpty()) {
            logListState.animateScrollToItem(logItems.size - 1)
        }
    }

    LaunchedEffect(provider) {
        addLog("初始化同步页面：$providerName", SyncLogType.INFO)
        if (!autoUpdateSupported) {
            subTitle = "仅支持新正方系统自动更新"
            return@LaunchedEffect
        }
        val creds = viewModel.loadQidiCredentials()
        val needProvider = provider
        if (creds == null || creds.provider != needProvider || creds.type != SyncCredentialType.PASSWORD) {
            subTitle = "未绑定${providerName}凭据，请在设置中绑定"
            addLog("未找到可用凭据或提供者不一致", SyncLogType.WARNING)
            reportError("未绑定${providerName}凭据")
            return@LaunchedEffect
        }
        credsForAutoFill = creds
        val endpoint = creds.endpointUrl
        if (!endpoint.isNullOrBlank()) {
            val normalized = normalizeEndpointForLoad(endpoint)
            if (normalized.isNullOrBlank()) {
                subTitle = "入口地址无效，请在设置中重新绑定"
                addLog("入口地址无效：$endpoint", SyncLogType.WARNING)
                reportError("入口地址无效")
                return@LaunchedEffect
            }
            addressBar = normalized
            pendingLoadUrl = normalized
            subTitle = "点击“开始连接”后进入${providerName}教务系统"
            addLog("已准备入口地址，等待用户点击开始连接", SyncLogType.INFO)
        } else {
            subTitle = "请在上方地址栏手动输入或通过门户进入${providerName}教务系统，系统将自动记录入口"
            addLog("入口地址为空，等待手动输入", SyncLogType.WARNING)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (showErrorDialog) {
            AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { Text("自动更新出现错误") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "已自动汇总错误信息，可点击复制发送给开发者。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = errorDialogText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(errorDialogText))
                            showErrorDialog = false
                        }
                    ) { Text("复制") }
                },
                dismissButton = {
                    TextButton(onClick = { showErrorDialog = false }) { Text("关闭") }
                }
            )
        }
        if (showEndpointDialog) {
            AlertDialog(
                onDismissRequest = { showEndpointDialog = false },
                title = { Text("设置入口地址") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "用于打开${providerName}教务系统首页，后续会自动记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = endpointInput,
                            onValueChange = { endpointInput = it },
                            label = { Text("教务入口地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val wv = webView
                            if (wv == null) {
                                addLog("WebView 未初始化", SyncLogType.ERROR)
                                showEndpointDialog = false
                                return@TextButton
                            }
                            val url = normalizeEndpointForLoad(endpointInput)
                            if (url.isNullOrBlank()) {
                                subTitle = "请输入有效网址"
                                addLog("输入的地址无效：$endpointInput", SyncLogType.WARNING)
                                return@TextButton
                            }
                            triedJwglxtFallback = false
                            endpointCheckFailed = false
                            addressBar = url
                            wv.loadUrl(url)
                            addLog("手动前往地址：$url", SyncLogType.ACTION)
                            showEndpointDialog = false
                        }
                    ) { Text("保存并打开") }
                },
                dismissButton = {
                    TextButton(onClick = { showEndpointDialog = false }) { Text("取消") }
                }
            )
        }
        if (showDiffSelectDialog) {
            val addedCount = diffItems.filter { it.type == DiffType.Added }.sumOf { it.count }
            val removedCount = diffItems.filter { it.type == DiffType.Removed }.sumOf { it.count }
            AlertDialog(
                onDismissRequest = { showDiffSelectDialog = false },
                title = { Text("选择同步方式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "检测到课表差异：新增 ${addedCount} 项，移除 ${removedCount} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "请选择完全覆盖或部分变更。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDiffSelectDialog = false
                            scope.launch {
                                loading = true
                                showProgressDialog = true
                                currentStep = "覆盖写入当前学期"
                                addLog("开始覆盖写入当前学期", SyncLogType.ACTION)
                                val count = viewModel.applyToCurrentSemester(pendingParsedCourses)
                                subTitle = "同步成功：更新 ${count} 门课程"
                                currentStep = "同步完成"
                                addLog("同步成功：更新 ${count} 门课程", SyncLogType.SUCCESS)
                                loading = false
                                showProgressDialog = false
                                onFinish()
                            }
                        }
                    ) { Text("完全覆盖") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showDiffSelectDialog = false }) { Text("取消") }
                        TextButton(
                            onClick = {
                                showDiffSelectDialog = false
                                showDiffDialog = true
                            }
                        ) { Text("部分变更") }
                    }
                }
            )
        }
        if (showDiffDialog) {
            AlertDialog(
                onDismissRequest = { showDiffDialog = false },
                title = { Text("确认变更项") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "请选择需要应用的差异项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 360.dp)
                        ) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(diffItems.indices.toList()) { index ->
                                    val item = diffItems[index]
                                    val checked = diffSelections.getOrNull(index) == true
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { value ->
                                                if (index < diffSelections.size) {
                                                    diffSelections[index] = value
                                                }
                                            }
                                        )
                                        Text(
                                            text = formatDiffItem(item),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    diffSelections.clear()
                                    diffSelections.addAll(List(diffItems.size) { true })
                                }
                            ) { Text("全选") }
                            TextButton(
                                onClick = {
                                    diffSelections.clear()
                                    diffSelections.addAll(List(diffItems.size) { false })
                                }
                            ) { Text("全不选") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val selected = diffItems.filterIndexed { index, _ ->
                                diffSelections.getOrNull(index) == true
                            }
                            showDiffDialog = false
                            if (selected.isEmpty()) {
                                showNoDiffDialog = true
                                subTitle = "课表无变动"
                                addLog("未选择任何变更项", SyncLogType.INFO)
                                return@TextButton
                            }
                            scope.launch {
                                loading = true
                                showProgressDialog = true
                                currentStep = "应用部分变更"
                                addLog("开始应用部分变更", SyncLogType.ACTION)
                                val result = viewModel.applyPartialUpdate(
                                    pendingParsedCourses,
                                    selected
                                )
                                subTitle = "同步完成：新增 ${result.added} 项，移除 ${result.removed} 项"
                                currentStep = "同步完成"
                                addLog(
                                    "同步完成：新增 ${result.added} 项，移除 ${result.removed} 项",
                                    SyncLogType.SUCCESS
                                )
                                loading = false
                                showProgressDialog = false
                                onFinish()
                            }
                        }
                    ) { Text("确认变更") }
                },
                dismissButton = {
                    TextButton(onClick = { showDiffDialog = false }) { Text("取消") }
                }
            )
        }
        if (showNoDiffDialog) {
            AlertDialog(
                onDismissRequest = { showNoDiffDialog = false },
                title = { Text("课表无变动") },
                text = {
                    Text(
                        text = "当前学期课表与获取结果一致，无需更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showNoDiffDialog = false }) { Text("知道了") }
                }
            )
        }
        if (showNoSemesterDialog) {
            AlertDialog(
                onDismissRequest = { showNoSemesterDialog = false },
                title = { Text("缺少本地学期配置") },
                text = {
                    Text(
                        text = "已经成功抓取到 ${pendingParsedCourses.size} 门课程！\n\n但您的本地数据库中尚未设置“当前学期”，系统不知道该把这些课存到哪里。\n请先返回设置页面配置当前学期。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNoSemesterDialog = false
                            onBackClick()
                        }
                    ) { Text("去设置", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showNoSemesterDialog = false }) { Text("稍后再说") }
                }
            )
        }
        if (showNeedInitialImportDialog) {
            AlertDialog(
                onDismissRequest = { showNeedInitialImportDialog = false },
                title = { Text("需要先导入课表") },
                text = {
                    Text(
                        text = "检测到当前学期还没有任何课程记录。\n请先使用教务系统导入一次课表，然后再使用更新脚本。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNeedInitialImportDialog = false
                            onBackClick()
                        }
                    ) { Text("去导入", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showNeedInitialImportDialog = false }) { Text("稍后再说") }
                }
            )
        }
        if (showProgressDialog) {
            ModalBottomSheet(
                onDismissRequest = { if (!loading && !needManualLogin) showProgressDialog = false },
                sheetState = sheetState,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .imePadding()
                        .verticalScroll(sheetScrollState)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) {
                            LinearProgressIndicator(modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                text = "同步状态",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(text = currentStep, style = MaterialTheme.typography.titleMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp)
                            .background(Color(0xFF1E1E1E))
                            .padding(12.dp)
                    ) {
                        LazyColumn(
                            state = logListState,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(logItems) { item ->
                                Text(
                                    text = "[${item.time}] ${item.message}",
                                    color = logColor(item.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = needManualLogin) {
                        Column(
                            modifier = Modifier.bringIntoViewRequester(captchaBringRequester),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider()
                            Text("需要安全验证", style = MaterialTheme.typography.titleSmall)
                            if (captchaUrl.isNotBlank()) {
                                CaptchaImage(
                                    url = captchaUrl,
                                    referer = addressBar,
                                    userAgent = webUserAgent
                                )
                            }
                            OutlinedTextField(
                                value = captchaCode,
                                onValueChange = { captchaCode = it },
                                label = { Text("请输入验证码") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusEvent { event ->
                                        if (event.isFocused) {
                                            scope.launch { captchaBringRequester.bringIntoView() }
                                        }
                                    }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val wv = webView ?: return@OutlinedButton
                                        scope.launch {
                                            wv.evaluateJavascript(
                                                "var i=document.querySelector('#yzmPic'); if(i){ i.click(); } void 0;",
                                                null
                                            )
                                            val res = suspendEvaluateJs(
                                                wv,
                                                "(function(){var i=document.querySelector('#yzmPic'); if(!i) return ''; var s=i.getAttribute('src')||''; try{ return new URL(s, location.href).href }catch(e){ return s; }})()"
                                            )
                                            val src = parseJsReturn(res).replace("\\/", "/")
                                            if (src.isNotBlank()) {
                                                captchaUrl = src
                                                addLog("已刷新验证码", SyncLogType.ACTION)
                                            }
                                        }
                                    }
                                ) { Text("刷新验证码") }
                                Button(
                                    onClick = {
                                        val code = captchaCode.trim()
                                        if (code.isBlank()) {
                                            addLog("验证码为空", SyncLogType.WARNING)
                                            return@Button
                                        }
                                        val wv = webView ?: return@Button
                                        val safe = escapeJs(code)
                                        wv.evaluateJavascript(
                                            "(function(){var input=document.querySelector('#yzm')||document.querySelector('input[name=\"yzm\"]')||document.querySelector('input[id*=\"yzm\"]'); if(input){input.value='$safe'; input.dispatchEvent(new Event('input',{bubbles:true})); input.dispatchEvent(new Event('change',{bubbles:true}));} var b=document.querySelector('#dl')||document.querySelector('button[type=\"submit\"]')||document.querySelector('input[type=\"submit\"]'); if(b){b.click(); return;} var f=(input&&input.form)||document.querySelector('form'); if(f){f.submit();}})();",
                                            null
                                        )
                                        addLog("已提交验证码并触发登录", SyncLogType.ACTION)
                                    }
                                ) { Text("继续登录") }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showProgressDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("关闭") }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(mainScrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val addressDesc = if (addressBar.isBlank()) "未记录入口地址" else addressBar
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, tint = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${providerName} 教务同步",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = addressDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { endpointInput = addressBar; showEndpointDialog = true }) {
                                Text("修改入口")
                            }
                        }
                    }
                }

                Text(
                    text = "同步向导",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
                Text(
                    text = "仅支持新正方系统，功能为实验性质，有待进一步测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                AnimatedStepCard(
                    stepNumber = 1,
                    title = "连接教务系统",
                    state = when {
                        syncStep == SyncStep.Login -> StepState.ACTIVE
                        syncStep > SyncStep.Login -> StepState.COMPLETED
                        else -> StepState.IDLE
                    },
                    summaryText = "已成功加载并登录"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "请点击按钮开始连接教务系统。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { startSyncFlow() },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("开始连接") }
                        if (loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                AnimatedStepCard(
                    stepNumber = 2,
                    title = "选择学年与学期",
                    state = when {
                        syncStep < SyncStep.SelectTerm -> StepState.IDLE
                        syncStep == SyncStep.SelectTerm -> StepState.ACTIVE
                        else -> StepState.COMPLETED
                    },
                    summaryText = run {
                        val yearSource = appliedYearLabel.ifBlank { targetYear }
                        val termSource = appliedTermLabel.ifBlank { targetTerm }
                        val yearText = if (yearSource.contains("学年")) yearSource else "${yearSource} 学年"
                        val termText = if (termSource.contains("学期") || termSource.contains("第")) termSource else "第 ${termSource} 学期"
                        "已应用: $yearText $termText"
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (availableYears.isEmpty() || availableTerms.isEmpty()) {
                            Text("尚未读取到系统选项，请刷新。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val yearLabel = availableYears.firstOrNull { it.first == selectedYear }?.second ?: selectedYear
                            val termLabel = availableTerms.firstOrNull { it.first == selectedTerm }?.second ?: selectedTerm

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { yearMenuExpanded = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(yearLabel.ifBlank { "选学年" }, maxLines = 1)
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                    AnimatedDropdownMenu(
                                        expanded = yearMenuExpanded,
                                        onDismissRequest = { yearMenuExpanded = false },
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                    ) {
                                        availableYears.forEach { (value, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { selectedYear = value; yearMenuExpanded = false })
                                        }
                                    }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { termMenuExpanded = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(termLabel.ifBlank { "选学期" }, maxLines = 1)
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                    AnimatedDropdownMenu(
                                        expanded = termMenuExpanded,
                                        onDismissRequest = { termMenuExpanded = false },
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                    ) {
                                        availableTerms.forEach { (value, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { selectedTerm = value; termMenuExpanded = false })
                                        }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(
                                onClick = { fetchSelectableOptions() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("刷新选项") }
                            Button(
                                onClick = {
                                    applySelectedTermToPage {
                                        syncStep = SyncStep.Compare
                                        subTitle = "已选择学年学期，请进行课表获取与对比"
                                    }
                                },
                                enabled = availableYears.isNotEmpty() && availableTerms.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("确认并继续") }
                        }
                    }
                }

                AnimatedStepCard(
                    stepNumber = 3,
                    title = "提取并对比课表",
                    state = when {
                        syncStep < SyncStep.Compare -> StepState.IDLE
                        syncStep == SyncStep.Compare -> StepState.ACTIVE
                        else -> StepState.COMPLETED
                    },
                    summaryText = "提取与对比已完成！"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "点击按钮开始提取数据并与本地当前学期课表对比。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { startExtractFlow() },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("开始提取") }
                        if (loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
            WebViewBox(
                provider = provider,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f),
                onWebViewReady = { wv ->
                    webView = wv
                    webUserAgent = wv.settings.userAgentString
                    addLog("WebView 初始化完成", SyncLogType.INFO)
                },
                onPageStarted = { url ->
                    loading = true
                    if (showProgressDialog) {
                        currentStep = "页面加载中"
                        addLog("开始加载：$url", SyncLogType.INFO)
                    }
                    if (url.isNotBlank()) {
                        addressBar = url
                    }
                },
                onPageTitle = { t -> if (t.isNotBlank()) title = t },
                onPageFinished = { wv, _ ->
                    loading = false
                    if (showProgressDialog) {
                        currentStep = "页面加载完成"
                        addLog("页面加载完成")
                    }
                    // 检测正方登录页的错误与验证码可见性，提示用户手动输入；并在首次进入登录页/课表页时自动记录入口
                    if (provider == SyncProviderType.ZF) {
                        scope.launch {
                            try {
                                val res = suspendEvaluateJs(
                                    wv,
                                    """
                                    (function(){
                                      try{
                                        var tips=document.querySelector('#tips');
                                        var tipTxt=tips? (tips.innerText||'') : '';
                                        var hasWrong = tipTxt.indexOf('用户名或密码不正确')>=0;
                                        var yzmDiv=document.querySelector('#yzmDiv');
                                        var yzmVis=false;
                                        if(yzmDiv){
                                          var cs=getComputedStyle(yzmDiv);
                                          yzmVis = !(cs.display==='none' || cs.visibility==='hidden');
                                        }
                                        var yzmInput = document.querySelector('#yzm') || document.querySelector('input[name="yzm"]') || document.querySelector('input[id*="yzm"]');
                                        var yzmImg = document.querySelector('#yzmPic') || document.querySelector('img[src*="yzm"]') || document.querySelector('img[src*="captcha"]') || document.querySelector('img[src*="validate"]');
                                        if(yzmInput){ yzmVis = true; }
                                        var yzmSrc = '';
                                        if(yzmImg){
                                          var s = yzmImg.getAttribute('src') || '';
                                          try{ yzmSrc = new URL(s, location.href).href; }catch(e){ yzmSrc = s; }
                                        }
                                        var userEl = document.querySelector('#yhm')
                                          || document.querySelector('input[name="yhm"]')
                                          || document.querySelector('input[name="username"]')
                                          || document.querySelector('input[name="user"]')
                                          || document.querySelector('input[name*="account"]')
                                          || document.querySelector('input[id*="user"]')
                                          || document.querySelector('input[id*="account"]')
                                          || document.querySelector('input[name="xh"]')
                                          || document.querySelector('input[id*="xh"]');
                                        var passEl = document.querySelector('#mm')
                                          || document.querySelector('input[name="mm"]')
                                          || document.querySelector('input[name*="pwd"]')
                                          || document.querySelector('input[id*="pwd"]')
                                          || document.querySelector('input[type="password"]');
                                        var loginBtn = document.querySelector('#dl')
                                          || document.querySelector('#login')
                                          || document.querySelector('button[type="submit"]')
                                          || document.querySelector('input[type="submit"]')
                                          || document.querySelector('button[id*="login"]')
                                          || document.querySelector('button[name*="login"]')
                                          || document.querySelector('input[id*="login"]')
                                          || document.querySelector('input[name*="login"]');
                                        var isLogin = !!document.querySelector('#yhm') || !!document.querySelector('#dl') || (!!passEl && (!!userEl || !!loginBtn));
                                        var isKebiao = !!document.querySelector('#ylkbTable')
                                          || !!document.querySelector('#ajaxForm')
                                          || !!document.querySelector('#kbtable')
                                          || !!document.querySelector('#kblist')
                                          || !!document.querySelector('#kblist_table')
                                          || !!document.querySelector('#kbgrid_table_0');
                                        var y=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                                        var t=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                                        var hasSelect = !!(y || t);
                                        var navLink = (function(){
                                          try{
                                            var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                                            if(r && r.singleNodeValue){ return r.singleNodeValue; }
                                          }catch(e){}
                                          return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
                                        })();
                                        var menuLink = Array.from(document.querySelectorAll('a')).find(a=>{
                                          var href=(a.getAttribute('href')||'').toLowerCase();
                                          var txt=(a.textContent||'').trim();
                                          return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
                                            || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
                                        });
                                        var hasMenu = !!(navLink || menuLink);
                                        var logoutLink = Array.from(document.querySelectorAll('a,button')).find(function(el){
                                          var txt=(el.textContent||'').trim();
                                          var href=(el.getAttribute && el.getAttribute('href')||'').toLowerCase();
                                          return txt.indexOf('退出')>=0 || txt.indexOf('注销')>=0 || href.indexOf('logout')>=0 || href.indexOf('logoff')>=0 || href.indexOf('exit')>=0;
                                        });
                                        var loggedIn = !!logoutLink;
                                        var href = location.href;
                                        return JSON.stringify({wrong:!!hasWrong, yzm:!!yzmVis, yzmSrc:yzmSrc, isLogin:isLogin, isKebiao:isKebiao, hasSelect:hasSelect, hasMenu:hasMenu, loggedIn:loggedIn, href:href});
                                      }catch(e){ return JSON.stringify({wrong:false,yzm:false,yzmSrc:'',isLogin:false,isKebiao:false,href:''}); }
                                    })();
                                    """.trimIndent()
                                )
                                val txt = parseJsReturn(res)
                                val wrong = txt.contains("\"wrong\":true")
                                val yzm = txt.contains("\"yzm\":true")
                                val isLogin = txt.contains("\"isLogin\":true")
                                val isKebiao = txt.contains("\"isKebiao\":true")
                                val hasSelect = txt.contains("\"hasSelect\":true")
                                val hasMenu = txt.contains("\"hasMenu\":true")
                                val loggedIn = txt.contains("\"loggedIn\":true")
                                val hrefMatch = Regex("\"href\":\"(.*?)\"").find(txt)
                                val pageHref = hrefMatch?.groups?.get(1)?.value?.replace("\\/", "/").orEmpty()
                                needManualLogin = wrong || yzm
                                val yzmSrcMatch = Regex("\"yzmSrc\":\"(.*?)\"").find(txt)
                                val yzmSrc = yzmSrcMatch?.groups?.get(1)?.value?.replace("\\/", "/").orEmpty()
                                captchaUrl = if (yzm && yzmSrc.isNotBlank()) yzmSrc else ""
                                val shouldTryFallback = !needManualLogin && !isLogin && !isKebiao && !hasSelect && !hasMenu
                                if (shouldTryFallback && !triedJwglxtFallback) {
                                    val sourceUrl = if (pageHref.isNotBlank()) pageHref else addressBar
                                    val fallbackUrl = buildJwglxtFallbackUrl(sourceUrl)
                                    if (!fallbackUrl.isNullOrBlank() && fallbackUrl != sourceUrl) {
                                        triedJwglxtFallback = true
                                        endpointCheckFailed = false
                                        addressBar = fallbackUrl
                                        wv.loadUrl(fallbackUrl)
                                        if (showProgressDialog) {
                                            currentStep = "尝试 /jwglxt 入口"
                                            addLog("未识别到登录入口，尝试补全 /jwglxt", SyncLogType.ACTION)
                                        }
                                        return@launch
                                    }
                                }
                                if (shouldTryFallback && triedJwglxtFallback && !endpointCheckFailed) {
                                    endpointCheckFailed = true
                                    subTitle = "未识别到登录入口，请检查网址是否正确"
                                    if (showProgressDialog) {
                                        currentStep = "入口地址可能不正确"
                                        addLog("未识别到登录入口，请检查网址是否正确", SyncLogType.WARNING)
                                    }
                                }
                                val creds = credsForAutoFill
                                val autoFillKey = if (pageHref.isNotBlank()) pageHref else addressBar
                                if (isLogin && creds != null && creds.type == SyncCredentialType.PASSWORD) {
                                    if (autoFillKey.isNotBlank() && autoFillKey != lastAutoFillUrl) {
                                        val js = buildAutoFillScript(
                                            username = creds.username ?: "",
                                            password = creds.secret,
                                            clickLogin = !needManualLogin
                                        )
                                        wv.evaluateJavascript(js, null)
                                        lastAutoFillUrl = autoFillKey
                                        if (showProgressDialog) {
                                            addLog("已执行自动填充脚本", SyncLogType.ACTION)
                                        }
                                    }
                                }
                                if (needManualLogin) {
                                    subTitle = "检测到登录失败或需要验证码，请在页面中手动输入后点击“继续登录/提取”。"
                                    if (showProgressDialog) {
                                        currentStep = "需要验证码或手动登录"
                                        addLog("检测到验证码或登录失败", SyncLogType.WARNING)
                                        if (yzm) addLog("验证码已可见", SyncLogType.ACTION)
                                        if (wrong) addLog("检测到账号或密码错误提示", SyncLogType.WARNING)
                                        if (captchaUrl.isNotBlank()) addLog("验证码地址已获取", SyncLogType.ACTION)
                                    }
                                }
                                if (!needManualLogin && !isKebiao && !hasSelect && (hasMenu || loggedIn)) {
                                    if (pageHref.isNotBlank() && pageHref != lastZfMenuJumpUrl) {
                                        val openMenuJs = """
                                            (function(){
                                              try{
                                                var navLink = (function(){
                                                  try{
                                                    var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                                                    if(r && r.singleNodeValue){ return r.singleNodeValue; }
                                                  }catch(e){}
                                                  return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
                                                })();
                                                if(navLink){ navLink.click(); return 'ok'; }
                                                var link = Array.from(document.querySelectorAll('a')).find(a=>{
                                                  var href=(a.getAttribute('href')||'').toLowerCase();
                                                  var txt=(a.textContent||'').trim();
                                                  return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
                                                    || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
                                                });
                                                if(link){ link.click(); return 'ok'; }
                                                return 'none';
                                              }catch(e){ return 'err'; }
                                            })();
                                        """.trimIndent()
                                        wv.evaluateJavascript(openMenuJs, null)
                                        lastZfMenuJumpUrl = pageHref
                                        addLog("尝试打开课表页面", SyncLogType.ACTION)
                                    }
                                }
                                if (pageHref.isNotBlank()) {
                                    val updated = viewModel.updateEndpointIfNeeded(pageHref, provider)
                                    if (updated) {
                                        subTitle = "已自动记录登录入口，后续可直接“开始同步（$providerName）”。"
                                        if (showProgressDialog) {
                                            addLog("已更新入口地址", SyncLogType.INFO)
                                        }
                                    }
                                    if (showProgressDialog) {
                                        addLog("当前地址：$pageHref", SyncLogType.INFO)
                                    }
                                }
                                if (isKebiao) {
                                    needManualLogin = false
                                    syncStep = SyncStep.SelectTerm
                                    subTitle = "已进入课表页面，请选择学年与学期"
                                    fetchSelectableOptions()
                                } else if (!isKebiao && hasSelect) {
                                    needManualLogin = false
                                    syncStep = SyncStep.SelectTerm
                                    subTitle = "已进入课表页面，请选择学年与学期"
                                    fetchSelectableOptions()
                                } else if (!needManualLogin && !isKebiao && !hasSelect) {
                                    pollPageStateIfNeeded()
                                }
                            } catch (e: Throwable) {
                                reportError("页面状态解析失败", e)
                            }
                        }
                    }
                    if (provider != SyncProviderType.ZF) {
                        scope.launch {
                            try {
                                val creds = credsForAutoFill
                                if (creds != null && creds.type == SyncCredentialType.PASSWORD) {
                                    if (addressBar.isNotBlank() && addressBar != lastAutoFillUrl) {
                                        val js = buildAutoFillScript(
                                            username = creds.username ?: "",
                                            password = creds.secret,
                                            clickLogin = !needManualLogin
                                        )
                                        wv.evaluateJavascript(js, null)
                                        lastAutoFillUrl = addressBar
                                        if (showProgressDialog) {
                                            addLog("已执行自动填充脚本", SyncLogType.ACTION)
                                        }
                                    }
                                }
                                val res = suspendEvaluateJs(
                                    wv,
                                    """
                                    (function(){
                                      try{
                                        var y=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
                                        var t=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
                                        var hasTable = !!document.querySelector('#kbtable') || !!document.querySelector('#kblist') || !!document.querySelector('#ylkbTable') || !!document.querySelector('#ajaxForm');
                                        return JSON.stringify({hasSelect:!!(y||t), hasTable:!!hasTable});
                                      }catch(e){ return JSON.stringify({hasSelect:false, hasTable:false}); }
                                    })();
                                    """.trimIndent()
                                )
                                val txt = parseJsReturn(res)
                                val hasSelect = txt.contains("\"hasSelect\":true")
                                val hasTable = txt.contains("\"hasTable\":true")
                                if (hasSelect || hasTable) {
                                    syncStep = SyncStep.SelectTerm
                                    subTitle = "已进入课表页面，请选择学年与学期"
                                    fetchSelectableOptions()
                                } else {
                                    pollPageStateIfNeeded()
                                }
                            } catch (e: Throwable) {
                                reportError("页面状态解析失败", e)
                            }
                        }
                    }
                },
                onPageError = { desc ->
                    loading = false
                    if (desc.isNotBlank()) {
                        subTitle = "页面加载失败：$desc"
                        if (showProgressDialog) {
                            currentStep = "页面加载失败"
                            addLog("页面加载失败：$desc", SyncLogType.ERROR)
                        }
                        reportError("页面加载失败：$desc")
                    }
                }
            )
        }
    }
}
@Composable
private fun CaptchaImage(
    url: String,
    referer: String,
    userAgent: String?
) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 160.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.Transparent.toArgb())
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                if (!userAgent.isNullOrBlank()) {
                    settings.userAgentString = userAgent
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
            }
        },
        update = { view ->
            if (url.isNotBlank()) {
                if (referer.isNotBlank()) {
                    view.loadUrl(url, mapOf("Referer" to referer))
                } else {
                    view.loadUrl(url)
                }
            }
        }
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AnimatedStepCard(
    stepNumber: Int,
    title: String,
    state: StepState,
    summaryText: String,
    content: @Composable () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (state == StepState.ACTIVE) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "containerColor"
    )
    val cardElevation by animateDpAsState(
        targetValue = if (state == StepState.ACTIVE) 6.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "elevation"
    )
    val titleColor by animateColorAsState(
        targetValue = if (state == StepState.ACTIVE) MaterialTheme.colorScheme.onSurface else Color.Gray,
        label = "titleColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (state == StepState.ACTIVE) 1f else 0f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = state == StepState.COMPLETED,
                    transitionSpec = {
                        (scaleIn() + fadeIn()) with (scaleOut() + fadeOut())
                    },
                    label = "icon"
                ) { isCompleted ->
                    if (isCompleted) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF34C759), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(
                                    width = 2.dp,
                                    color = if (state == StepState.ACTIVE) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stepNumber.toString(),
                                fontWeight = FontWeight.Bold,
                                color = if (state == StepState.ACTIVE) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
            }

            AnimatedVisibility(
                visible = state != StepState.IDLE,
                enter = expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 16.dp, start = 40.dp)) {
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220, delayMillis = 90)) with fadeOut(animationSpec = tween(90)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "content"
                    ) { currentState ->
                        if (currentState == StepState.COMPLETED) {
                            Text(
                                text = summaryText,
                                color = Color(0xFF34C759),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            content()
                        }
                    }
                }
            }
        }
    }
}

private fun escapeJs(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private fun normalizeEndpointForLoad(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val candidate = when {
        URLUtil.isNetworkUrl(withScheme) -> withScheme
        else -> URLUtil.guessUrl(withScheme)
    }
    if (!URLUtil.isNetworkUrl(candidate)) return null
    return candidate
}

private fun buildJwglxtFallbackUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    return try {
        val uri = Uri.parse(trimmed)
        val host = uri.host.orEmpty()
        if (host.isBlank()) return null
        val scheme = uri.scheme ?: "https"
        val port = if (uri.port in 1..65535) ":${uri.port}" else ""
        "$scheme://$host$port/jwglxt"
    } catch (_: Exception) {
        null
    }
}

/**
 * WebView 容器
 *
 * 开启 JS、允许存储、启用 Cookie，注入基础登录辅助逻辑：
 * - 当检测到常见登录表单时，尝试自动填充用户名与密码并提交
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBox(
    provider: SyncProviderType,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView) -> Unit,
    onPageStarted: (String) -> Unit = {},
    onPageTitle: (String) -> Unit,
    onPageFinished: (WebView, String) -> Unit = {_, _ -> },
    onPageError: (String) -> Unit = {}
) {
    AndroidView(
        modifier = modifier.padding(top = 8.dp),
        factory = { context ->
            val wv = WebView(context)
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            if (provider == SyncProviderType.ZF) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = settings.userAgentString.replace("wv", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }
            wv.webChromeClient = WebChromeClient()
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onPageStarted(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    onPageTitle(view.title ?: "")
                    onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        onPageError(error.description?.toString().orEmpty())
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame) {
                        onPageError("HTTP ${errorResponse.statusCode}")
                    }
                }
            }
            onWebViewReady(wv)
            wv
        }
    )
}

/**
 * 挂起执行 JS 并返回字符串
 */
private suspend fun suspendEvaluateJs(webView: WebView, script: String): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { value ->
            if (cont.isActive) cont.resume(value) {}
        }
    }

/**
 * 解析 evaluateJavascript 返回的字符串（去除引号与转义）
 */
private fun parseJsReturn(value: String?): String {
    return try {
        if (value == null || value == "null") ""
        else JSONTokener(value).nextValue().toString()
    } catch (_: Exception) {
        if (value == null) return ""
        var v = value
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
            v = v.replace("\\n", "\n").replace("\\\"", "\"")
        }
        v
    }
}

/**
 * 生成更健壮的自动填充脚本：
 * - 针对常见字段名：yhm/xh/username/account 等，密码：mm/pwd/password
 * - 触发 input/change/blur 事件
 * - 尝试在同域 iframe 中执行相同逻辑
 */
private fun buildAutoFillScript(username: String, password: String, clickLogin: Boolean = true): String {
    val userEsc = username.replace("'", "\\'")
    val passEsc = password.replace("'", "\\'")
    return """
      (function(){
        try {
          var U = '$userEsc';
          var P = '$passEsc';
          var userEl = document.getElementById('yhm') || document.querySelector('input[name="yhm"]');
          var passEl = document.getElementById('mm') || document.querySelector('input[name="mm"]');
          var hidMm = document.getElementById('hidMm') || document.querySelector('input[id*="hidMm"]');
          var loginBtn = document.getElementById('dl') || document.querySelector('#dl');
          if (userEl) userEl.value = U;
          if (passEl) passEl.value = P;
          if (hidMm) hidMm.value = P;
          function triggerEvents(el){
            if (!el) return;
            try { el.dispatchEvent(new Event('input', {bubbles:true})); } catch(e){}
            try { el.dispatchEvent(new Event('change', {bubbles:true})); } catch(e){}
            try { el.dispatchEvent(new Event('blur', {bubbles:true})); } catch(e){}
          }
          triggerEvents(userEl);
          triggerEvents(passEl);
          triggerEvents(hidMm);
          var needCaptcha = false;
          var yzmEl = document.getElementById('yzm') || document.querySelector('input[name="yzm"]');
          var yzmDiv = document.getElementById('yzmDiv') || document.querySelector('#yzmDiv');
          if (yzmEl) {
            var style1 = window.getComputedStyle(yzmEl);
            if (style1.display !== 'none' && style1.visibility !== 'hidden') needCaptcha = true;
          }
          if (yzmDiv) {
            var style2 = window.getComputedStyle(yzmDiv);
            if (style2.display !== 'none' && style2.visibility !== 'hidden') needCaptcha = true;
          }
          if (needCaptcha || !${clickLogin}) return "filled_no_click";
          if (loginBtn) {
            setTimeout(function(){ loginBtn.click(); }, 500);
            return "clicked";
          } else {
            var form = document.querySelector('form');
            if (form) { form.submit(); return "submitted"; }
          }
          return "no_button";
        } catch(e) {
          return "error: " + e.message;
        }
      })();
    """.trimIndent()
}

/**
 * 起迪同步 ViewModel
 *
 * 负责：
 * - 读取已绑定的起迪凭据
 * - 将解析结果覆盖写入当前学期
 */
@HiltViewModel
class QidiSyncViewModel @Inject constructor(
    private val credentialsRepository: CredentialsRepository,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository
) : androidx.lifecycle.ViewModel() {

    /**
     * 读取起迪凭据
     */
    suspend fun loadQidiCredentials() = credentialsRepository.getCredentials()

    /**
     * 若当前绑定与 provider 一致，且尚未记录入口或入口不同，则更新入口。
     *
     * @return 是否发生更新
     */
    suspend fun updateEndpointIfNeeded(href: String, provider: SyncProviderType): Boolean {
        val creds = credentialsRepository.getCredentials() ?: return false
        if (creds.provider != provider) return false
        val normalized = normalizeEndpoint(href)
        if (normalized.isBlank()) return false
        if (creds.endpointUrl == normalized) return false
        credentialsRepository.saveCredentials(
            creds.copy(endpointUrl = normalized)
        )
        return true
    }

    /**
     * 规范化入口地址：
     * - 优先截断至 /jwglxt 根（若存在）
     * - 否则保留 scheme://host[:port]
     */
    private fun normalizeEndpoint(href: String): String {
        return try {
            val uri = java.net.URI(href)
            val base = "${uri.scheme}://${uri.host}" + (if (uri.port in 1..65535) ":${uri.port}" else "")
            val path = uri.path ?: ""
            if (path.contains("/jwglxt")) {
                val idx = path.indexOf("/jwglxt")
                base + path.substring(0, idx + "/jwglxt".length)
            } else {
                base
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 覆盖写入当前学期课程
     *
     * @return 写入课程数量
     */
    suspend fun applyToCurrentSemester(parsed: List<ParsedCourse>): Int {
        val current = semesterRepository.getCurrentSemester().first() ?: return 0
        courseRepository.deleteCoursesBySemester(current.id)
        val domainCourses = parsed.map { it.toDomainCourse().copy(semesterId = current.id) }
        courseRepository.insertCourses(domainCourses)
        return domainCourses.size
    }

    suspend fun getCurrentSemesterId(): Long? {
        return withContext(Dispatchers.IO) {
            val current = semesterRepository.getCurrentSemester().first()
            current?.id ?: semesterRepository.getAllSemesters().first().firstOrNull()?.id
        }
    }

    /**
     * 获取全部学期列表（用于自动兜底）
     */
    suspend fun getAllSemesters(): List<Semester> {
        return withContext(Dispatchers.IO) {
            semesterRepository.getAllSemesters().first()
        }
    }

    /**
     * 设置当前学期
     */
    suspend fun setCurrentSemester(semesterId: Long) {
        withContext(Dispatchers.IO) {
            semesterRepository.setCurrentSemester(semesterId)
        }
    }

    suspend fun getCoursesBySemester(semesterId: Long): List<Course> {
        return courseRepository.getCoursesBySemester(semesterId).first()
    }

    suspend fun applyPartialUpdate(
        parsed: List<ParsedCourse>,
        selectedItems: List<DiffItem>
    ): PartialUpdateResult {
        val current = semesterRepository.getCurrentSemester().first() ?: return PartialUpdateResult(0, 0)
        val existing = courseRepository.getCoursesBySemester(current.id).first()
        var removedCount = 0
        selectedItems.filter { it.type == DiffType.Removed }.forEach { item ->
            val matches = existing.filter { toFingerprint(it) == item.fingerprint }.take(item.count)
            matches.forEach { course ->
                courseRepository.deleteCourse(course)
                removedCount++
            }
        }
        var addedCount = 0
        selectedItems.filter { it.type == DiffType.Added }.forEach { item ->
            val matches = parsed.filter { toFingerprint(it) == item.fingerprint }.take(item.count)
            val domainCourses = matches.map { it.toDomainCourse().copy(semesterId = current.id) }
            courseRepository.insertCourses(domainCourses)
            addedCount += domainCourses.size
        }
        return PartialUpdateResult(addedCount, removedCount)
    }
}
