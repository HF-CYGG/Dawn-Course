 package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.ui.components.BatchGenerateTimeDialog
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import com.dawncourse.core.ui.util.CourseColorUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * 导入功能主屏幕
 *
 * 管理导入流程的三个核心步骤：
 * 1. [ImportStep.Input]: 首页选择导入方式（支持网页抓取或 ICS 文件导入）
 * 2. [ImportStep.WebView]: 内置浏览器步骤，用于登录教务系统并执行抓取脚本
 * 3. [ImportStep.Review]: 数据预览与确认步骤，允许用户在入库前修改学期设置和课程信息
 *
 * @param onImportSuccess 导入成功后的回调函数，通常用于导航回主页
 * @param viewModel [ImportViewModel] 实例，由 Hilt 注入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImportSuccess: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showQiangZhiDialog by remember { mutableStateOf(false) }
    var qiangZhiBaseUrl by remember { mutableStateOf("") }
    var qiangZhiStudentId by remember { mutableStateOf("") }
    var qiangZhiPassword by remember { mutableStateOf("") }

    // 云端解析上传确认弹窗（必须用户明确同意）
    if (uiState.showLlmConsentDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelLlmConsent() },
            title = { Text("确认上传到云端解析") },
            text = {
                val previewScroll = rememberScrollState()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "将把当前页面脱敏后的完整内容上传至云端解析服务。" +
                            "\n内容长度：${uiState.llmConsentLength} 字符" +
                            (if (uiState.llmConsentSourceUrl.isNotBlank()) "\n来源：${uiState.llmConsentSourceUrl}" else "") +
                            "\n请确认已知情并同意后再继续。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp)
                            .verticalScroll(previewScroll)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (uiState.llmConsentPreview.isNotBlank()) uiState.llmConsentPreview else "（无预览内容）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = uiState.llmConsentSchoolName,
                        onValueChange = { viewModel.updateLlmConsentSchoolName(it) },
                        label = { Text("学校名称（可选）") },
                        placeholder = { Text("例如：华中科技大学") },
                        supportingText = { Text("请尽量填写完整学校名称，便于归类处理") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateLlmConsentChecked(!uiState.llmConsentChecked) }
                    ) {
                        Checkbox(
                            checked = uiState.llmConsentChecked,
                            onCheckedChange = { viewModel.updateLlmConsentChecked(it) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "我已知情并同意上传脱敏后的完整内容用于解析",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmLlmConsent() },
                    enabled = uiState.llmConsentChecked
                ) {
                    Text("同意并上传")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLlmConsent() }) {
                    Text("取消")
                }
            }
        )
    }

    // 监听 ViewModel 的一次性事件 (如导入成功)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ImportEvent.Success) {
                onImportSuccess()
            }

        }
    }

    if (showQiangZhiDialog) {
        QiangZhiApiImportDialog(
            baseUrl = qiangZhiBaseUrl,
            studentId = qiangZhiStudentId,
            password = qiangZhiPassword,
            onBaseUrlChange = { qiangZhiBaseUrl = it },
            onStudentIdChange = { qiangZhiStudentId = it },
            onPasswordChange = { qiangZhiPassword = it },
            onDismiss = { showQiangZhiDialog = false },
            onConfirm = {
                val fallbackBaseUrl = deriveQiangZhiBaseUrl(uiState.webUrl)
                val baseUrl = if (qiangZhiBaseUrl.isBlank()) fallbackBaseUrl else qiangZhiBaseUrl
                showQiangZhiDialog = false
                if (baseUrl.isBlank() || qiangZhiStudentId.isBlank() || qiangZhiPassword.isBlank()) {
                    viewModel.updateResultText("强智 API 导入失败：请填写教务系统地址、学号和密码")
                    return@QiangZhiApiImportDialog
                }
                viewModel.runQiangZhiApiImport(
                    baseUrl = baseUrl,
                    studentId = qiangZhiStudentId,
                    password = qiangZhiPassword,
                    totalWeeks = uiState.weekCount
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        when (uiState.step) {
                            ImportStep.Input -> "导入课程"
                            ImportStep.WebView -> "网页提取"
                            ImportStep.Review -> "确认导入"
                        },
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    // 非首页步骤显示返回按钮
                    if (uiState.step != ImportStep.Input) {
                        IconButton(onClick = { viewModel.setStep(ImportStep.Input) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    } else {
                         IconButton(onClick = { onImportSuccess() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 根据当前步骤显示对应的子屏幕
            when (uiState.step) {
                ImportStep.Input -> SelectionStep(
                    viewModel = viewModel,
                    uiState = uiState,
                    onOpenQiangZhiDialog = { defaultUrl ->
                        if (defaultUrl.isNotBlank()) {
                            qiangZhiBaseUrl = defaultUrl
                        }
                        showQiangZhiDialog = true
                    }
                )
                ImportStep.WebView -> WebViewStep(
                    viewModel = viewModel,
                    uiState = uiState,
                    onOpenQiangZhiDialog = { defaultUrl ->
                        if (defaultUrl.isNotBlank()) {
                            qiangZhiBaseUrl = defaultUrl
                        }
                        showQiangZhiDialog = true
                    }
                )
                ImportStep.Review -> ReviewStep(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ParsedCourseList(
    courses: List<ParsedCourse>,
    onDelete: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "课程详情",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = if (expanded) "收起" else "展开全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // 始终显示前 3 项
                courses.take(3).forEachIndexed { index, course ->
                    ParsedCourseItem(course = course, onDelete = { onDelete(index) })
                    if (index < courses.size - 1 && (index < 2 || expanded)) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }

                // 展开后显示剩余项
                if (courses.size > 3) {
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            courses.drop(3).forEachIndexed { index, course ->
                                // index 从 0 开始，所以实际索引是 index + 3
                                ParsedCourseItem(course = course, onDelete = { onDelete(index + 3) })
                                if (index < courses.size - 3 - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParsedCourseItem(
    course: ParsedCourse,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            val locationText = if (course.location.isNotBlank() && course.teacher.isNotBlank()) {
                "${course.location} | ${course.teacher}"
            } else {
                "${course.location}${course.teacher}"
            }
            if (locationText.isNotBlank()) {
                Text(
                    text = locationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${course.startWeek}-${course.endWeek}周 ${course.startSection}-${course.endSection}节",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 强智 API 导入弹窗
 *
 * 提供教务系统地址、学号、密码输入，用于通过强智 API 拉取课表。
 */
@Composable
private fun QiangZhiApiImportDialog(
    baseUrl: String,
    studentId: String,
    password: String,
    onBaseUrlChange: (String) -> Unit,
    onStudentIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("强智 API 导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("教务系统地址") },
                    placeholder = { Text("例如：https://jwxt.xxx.edu.cn") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = studentId,
                    onValueChange = onStudentIdChange,
                    label = { Text("学号") },
                    placeholder = { Text("请输入学号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    placeholder = { Text("请输入密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "仅用于本次导入，不会保存账号与密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("开始导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 步骤一：选择导入方式
 *
 * 提供三种入口：
 * 1. 网页导入：跳转到 WebViewStep
 * 2. 强智 API 导入：使用学号密码拉取课表
 * 3. 文件导入：调用系统文件选择器读取 .ics 文件
 */
@Composable
private fun SelectionStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState,
    onOpenQiangZhiDialog: (String) -> Unit
) {
    val context = LocalContext.current
    // 注册文件选择器 ActivityResultLauncher
    val icsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 读取文件流并传递给 ViewModel 处理
            context.contentResolver.openInputStream(it)?.use { stream ->
                val content = BufferedReader(InputStreamReader(stream)).readText()
                viewModel.runIcsImport(content)
            }
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "选择导入方式",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Text(
            text = "支持从学校教务系统网页自动抓取，\n或使用标准 ICS 日历文件导入",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 选项卡：教务系统网页导入
        ImportOptionCard(
            title = "教务系统网页导入",
            description = "内置浏览器访问教务系统，自动解析课程表（支持新旧正方、青果、强智教务系统）",
            icon = Icons.Default.Search,
            onClick = { viewModel.setStep(ImportStep.WebView) }
        )

        // 选项卡：强智 API 导入
        ImportOptionCard(
            title = "强智 API 导入（学号/密码）",
            description = "适用于强智教务系统，账号密码换取 token 后拉取课表",
            icon = Icons.Default.Security,
            onClick = { onOpenQiangZhiDialog(deriveQiangZhiBaseUrl(uiState.webUrl)) }
        )
        // 选项卡：ICS 文件导入
        ImportOptionCard(
            title = "ICS 日历文件导入",
            description = "选择设备上的 .ics 文件进行解析",
            icon = Icons.Default.DateRange,
            onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/plain", "*/*")) }
        )

        // 加载状态显示
        if (uiState.isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            Text("正在解析数据...", color = MaterialTheme.colorScheme.primary)
        }

        // 结果/错误信息显示
        if (uiState.resultText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.resultText.contains("失败")) 
                        MaterialTheme.colorScheme.errorContainer 
                    else 
                        MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (uiState.resultText.contains("失败")) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (uiState.resultText.contains("失败")) 
                            MaterialTheme.colorScheme.onErrorContainer 
                        else 
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = uiState.resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.resultText.contains("失败")) 
                            MaterialTheme.colorScheme.onErrorContainer 
                        else 
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * 导入选项卡片组件
 * 统一的 UI 风格封装
 */
@Composable
private fun ImportOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标容器
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 中间文本区域
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
                )
            }
            
            // 右侧箭头
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 步骤二：内置浏览器抓取
 *
 * 包含：
 * 1. URL 输入栏
 * 2. WebView 容器
 * 3. 底部操作栏（刷新、一键提取）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState,
    onOpenQiangZhiDialog: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var webView: WebView? by remember { mutableStateOf(null) }
    var inputUrl by remember { mutableStateOf(uiState.webUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pollJob: Job? by remember { mutableStateOf(null) }
    var showNoDataDialog by remember { mutableStateOf(false) }
    var pendingHtmlForExport by remember { mutableStateOf("") }
    val noDataMessage = "未识别到课程数据。请确认：\n1. 已登录教务系统\n2. 位于个人课表页面\n3. 页面已完全加载"
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) {
            pendingHtmlForExport = ""
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(pendingHtmlForExport.toByteArray())
            }
        }.onSuccess {
            Toast.makeText(context, "已保存页面源码文件", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
        pendingHtmlForExport = ""
    }

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val cleaned = trimmed.trimStart { it == ';' || it == ' ' || it == '\u3000' }
        val guessed = URLUtil.guessUrl(cleaned)
        return if (URLUtil.isNetworkUrl(guessed)) guessed else null
    }

    LaunchedEffect(uiState.webUrl) {
        if (uiState.webUrl.isNotBlank()) {
            val normalized = normalizeUrl(uiState.webUrl) ?: uiState.webUrl
            if (normalized != inputUrl) {
                inputUrl = normalized
            }
        }
    }

    LaunchedEffect(uiState.resultText) {
        if (uiState.resultText == noDataMessage) {
            showNoDataDialog = true
        }
    }

    fun normalizeHttpUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val parsed = Uri.parse(withScheme)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = parsed.host.orEmpty()
        return if ((scheme == "http" || scheme == "https") && host.isNotBlank()) withScheme else null
    }

    fun isSafeHttpUrl(url: String): Boolean {
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = parsed.host.orEmpty()
        return (scheme == "http" || scheme == "https") && host.isNotBlank()
    }

    fun loadUrlIfValid(raw: String) {
        val safeUrl = normalizeHttpUrl(raw)
        if (safeUrl == null) {
            viewModel.updateResultText("请输入有效网址")
            return
        }
        inputUrl = safeUrl
        viewModel.updateWebUrl(safeUrl)
        webView?.loadUrl(safeUrl)
    }

    fun parseJavascriptResult(raw: String?): String {
        return try {
            if (raw == null || raw == "null") ""
            else JSONTokener(raw).nextValue().toString()
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun evaluateJs(script: String): String {
        val currentWebView = webView ?: return "null"
        return suspendCancellableCoroutine { cont ->
            currentWebView.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    cont.resume(result ?: "null")
                }
            }
        }
    }

    if (showNoDataDialog) {
        AlertDialog(
            onDismissRequest = { showNoDataDialog = false },
            title = { Text("需要帮助解析吗？") },
            text = {
                Text(
                    "可以一键导出当前页面源码为 txt 文件，便于你脱敏后提交给开发者定位问题。" +
                        "\n导出的文件可能包含个人信息，请先自行或让 AI 脱敏，仅保留页面结构。" +
                        "\n不想提交可以直接关闭。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            val html = parseJavascriptResult(
                                evaluateJs(
                                    """
                                    (function() {
                                        function collectFrames(doc) {
                                            var frames = [];
                                            try {
                                                var f1 = doc.querySelectorAll('iframe, frame');
                                                for (var i = 0; i < f1.length; i++) frames.push(f1[i]);
                                            } catch (e) {}
                                            
                                            return frames.map(function(f) {
                                                var content = "Cross-domain blocked";
                                                try {
                                                    var innerDoc = f.contentDocument || f.contentWindow.document;
                                                    if (innerDoc) {
                                                        content = innerDoc.documentElement.outerHTML;
                                                    }
                                                } catch (e) { content += ": " + e.message; }
                                                
                                                return {
                                                    id: f.id,
                                                    name: f.name,
                                                    src: f.src,
                                                    content: content
                                                };
                                            });
                                        }

                                        var info = {
                                            url: window.location.href,
                                            userAgent: navigator.userAgent,
                                            windowSize: {
                                                width: window.innerWidth,
                                                height: window.innerHeight
                                            },
                                            cookieLength: document.cookie ? document.cookie.length : 0,
                                            topHtml: document.documentElement.outerHTML,
                                            frames: collectFrames(document)
                                        };
                                        return JSON.stringify(info, null, 2);
                                    })();
                                    """.trimIndent()
                                )
                            )
                            if (html.isBlank()) {
                                Toast.makeText(context, "未获取到页面源码", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            pendingHtmlForExport = html
                            exportLauncher.launch("debug_snapshot.json")
                        }
                        showNoDataDialog = false
                    }
                ) {
                    Text("导出并保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoDataDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
    val qiangZhiBaseUrl = deriveQiangZhiBaseUrl(uiState.webUrl)
    val showQiangZhiHint = remember(uiState.webUrl) { isQiangZhiHost(uiState.webUrl) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部地址栏
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.zIndex(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("请输入教务系统网址") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { loadUrlIfValid(inputUrl) }
                    ),
                    trailingIcon = {
                        if (inputUrl.isNotEmpty()) {
                            IconButton(onClick = { inputUrl = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除")
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { loadUrlIfValid(inputUrl) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text("前往")
                }
            }
        }

        // 网页加载进度条
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (showQiangZhiHint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "检测到可能为强智教务系统，抓取失败可改用 API 导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = { onOpenQiangZhiDialog(qiangZhiBaseUrl) },
                    label = { Text("改用强智 API") },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
            }
        }
        
        // WebView 容器
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 开启安全浏览（API 26+）
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = true
                    }
                    settings.setAllowFileAccess(false)
                    settings.setAllowContentAccess(false)
                    settings.setAllowFileAccessFromFileURLs(false)
                    settings.setAllowUniversalAccessFromFileURLs(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // 允许 Cookie，确保登录态与跳转正常
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                    // 修正部分教务系统对 WebView UA 的屏蔽
                    settings.userAgentString = settings.userAgentString.replace("wv", "")
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            isLoading = newProgress < 100
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url == "about:blank") {
                                return false
                            }
                            return !isSafeHttpUrl(url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val targetUrl = url.orEmpty()
                            if (targetUrl == "about:blank") {
                                return false
                            }
                            return !isSafeHttpUrl(targetUrl)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                viewModel.updateResultText("页面加载失败，请检查网络或网址后重试。")
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            url?.let {
                                inputUrl = it
                                viewModel.updateWebUrl(it)
                            }
                        }
                        
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            url?.let {
                                inputUrl = it
                                viewModel.updateWebUrl(it)
                            }
                        }
                    }
                    if (uiState.webUrl.isNotBlank()) {
                        val safeUrl = normalizeHttpUrl(uiState.webUrl)
                        if (safeUrl != null) {
                            inputUrl = safeUrl
                            loadUrl(safeUrl)
                        } else {
                            viewModel.updateResultText("请输入有效网址")
                        }
                    }
                    webView = this
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // 状态提示栏 (显示解析中或错误信息)
        if (uiState.isLoading || uiState.resultText.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "正在解析...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        val isError = uiState.resultText.contains("失败") || uiState.resultText.contains("未识别")
                        val icon = if (isError) Icons.Default.Warning else Icons.Default.Info
                        val tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                        Text(
                            text = uiState.resultText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        
        // 底部操作栏
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = { webView?.reload() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("刷新")
                }
                
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val currentWebView = webView
                                if (currentWebView == null) {
                                    viewModel.updateResultText("未能提取到有效 HTML 内容")
                                    return@launch
                                }

                                // 如果检测到为强智教务系统，使用专用 Provider 直接请求课表页面 HTML
                                val js: String = if (isQiangZhiHost(uiState.webUrl)) {
                                    viewModel.getScriptContent("qz_provider.js", "js")
                                } else {
                                    // 非强智教务系统，继续使用通用 Provider + 降级逻辑
                                    val outputConsole = viewModel.getScriptContent("output_console.js", "js")
                                    val courseUtils = viewModel.getScriptContent("course_utils.js", "js")
                                    val qidiProvider = viewModel.getScriptContent("qidi_provider.js", "js")
                                    val genericProvider = viewModel.getScriptContent("generic_provider.js", "js")

                                    buildString {
                                        append("(function(){\n")
                                        append("try {\n")
                                        append("window.__dawnResult = null;\n")
                                        append("window.__dawnReady = false;\n")
                                        append(outputConsole).append("\n;\n")
                                        append(courseUtils).append("\n;\n")
                                        append(qidiProvider).append("\n;\n")
                                        append(genericProvider)
                                        append("\n} catch(e) { window.__dawnReady = true; }\n")
                                        append("})();")
                                    }
                                }

                                currentWebView.evaluateJavascript(js, null)
                                viewModel.updateResultText("正在提取...")
                                pollJob?.cancel()
                                pollJob = coroutineScope.launch {
                                    repeat(40) {
                                        val raw = evaluateJs("window.__dawnReady ? window.__dawnResult : null")
                                        val rawHtml = parseJavascriptResult(raw)
                                        if (rawHtml.isNotEmpty()) {
                                            viewModel.parseResultFromWebView(rawHtml)
                                            return@launch
                                        }
                                        delay(300)
                                    }
                                    viewModel.updateResultText("未能提取到有效 HTML 内容")
                                }
                            } catch (e: Exception) {
                                viewModel.updateResultText("脚本加载失败: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键提取")
                }
            }
        }
    }
}

/**
 * 步骤三：确认导入与配置
 *
 * 此界面展示解析成功的课程概览，并允许用户自定义学期和作息时间配置。
 * 包含以下功能模块：
 * 1. 解析结果概览：显示成功解析的课程数量。
 * 2. 学期设置：配置开学日期和学期总周数。
 * 3. 作息时间设置：配置单节课时长、课间时长以及大课间规则。
 * 4. 预览与确认：最后确认并执行入库操作。
 *
 * @param viewModel 导入功能的 ViewModel，用于获取状态和更新配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimeSettingDialog by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = uiState.semesterStartDate
    )

    if (showDatePicker) {
        DawnDatePickerDialog(
            state = datePickerState,
            onDismissRequest = { showDatePicker = false },
            onConfirm = {
                datePickerState.selectedDateMillis?.let { timestamp ->
                    viewModel.updateSemesterSettings(timestamp, uiState.weekCount)
                }
                showDatePicker = false
            }
        )
    }

    if (showTimeSettingDialog) {
        BatchGenerateTimeDialog(
            maxDailySections = uiState.detectedMaxSection,
            initialDuration = uiState.courseDuration,
            initialBreakDuration = uiState.breakDuration,
            initialAmStart = uiState.amStartTime,
            initialPmStartSec = uiState.pmStartSection,
            initialPmStart = uiState.pmStartTime,
            initialEveStartSec = uiState.eveStartSection,
            initialEveStart = uiState.eveStartTime,
            onDismissRequest = { showTimeSettingDialog = false },
            onConfirm = { times ->
                viewModel.updateSectionTimes(times)
                // 同时更新最大节次，保持一致
                viewModel.updateTimeSettings(
                    maxSection = times.size,
                    duration = uiState.courseDuration,
                    breakDuration = uiState.breakDuration,
                    bigBreakDuration = uiState.bigBreakDuration,
                    sectionsPerBigSection = uiState.sectionsPerBigSection
                )
                showTimeSettingDialog = false
            }
        )
    }

    RefinedReviewScreen(
        uiState = uiState,
        onDeleteCourse = { index -> viewModel.deleteParsedCourse(index) },
        onConfirm = { viewModel.confirmImport() },
        onUpdateSemester = { start, weeks -> viewModel.updateSemesterSettings(start, weeks) },
        onOpenDatePicker = { showDatePicker = true },
        onOpenTimeSettings = { showTimeSettingDialog = true }
    )
}

@Composable
fun RefinedReviewScreen(
    uiState: ImportUiState,
    onDeleteCourse: (Int) -> Unit,
    onConfirm: () -> Unit,
    onUpdateSemester: (Long, Int) -> Unit,
    onOpenDatePicker: () -> Unit,
    onOpenTimeSettings: () -> Unit
) {
    var isListExpanded by remember { mutableStateOf(false) }
    var isConfigExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ImportHeader(courseCount = uiState.parsedCourses.size)
            CourseSection(
                courses = uiState.parsedCourses,
                isExpanded = isListExpanded,
                onToggle = { isListExpanded = !isListExpanded },
                onDelete = onDeleteCourse
            )
            ConfigSection(
                uiState = uiState,
                isExpanded = isConfigExpanded,
                onToggle = { isConfigExpanded = !isConfigExpanded },
                onOpenDatePicker = onOpenDatePicker,
                onOpenTimeSettings = onOpenTimeSettings,
                onUpdateSemester = onUpdateSemester
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = MaterialTheme.colorScheme.primary
                    ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("确认导入并存入课表", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ImportHeader(courseCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "解析成功",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "共在当前页面找到 $courseCount 门课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun CourseSection(
    courses: List<ParsedCourse>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "课程列表",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            if (courses.size > 3) {
                Text(
                    text = if (isExpanded) "收起" else "查看全部",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onToggle() }.padding(4.dp)
                )
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
                val displayList = if (isExpanded) courses else courses.take(3)
                displayList.forEachIndexed { index, course ->
                    ReviewCourseItem(course = course, onDelete = { onDelete(index) })
                    if (index < displayList.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                        )
                    }
                }
                
                if (!isExpanded && courses.size > 3) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle() }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "剩余 ${courses.size - 3} 门课程已折叠",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewCourseItem(course: ParsedCourse, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                course.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val locationTeacher = listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
            if (locationTeacher.isNotBlank()) {
                Text(
                    locationTeacher,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPill("${course.startWeek}-${course.endWeek}周")
                InfoPill("第 ${course.startSection} 节起")
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun InfoPill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp
        )
    }
}

@Composable
fun ConfigSection(
    uiState: ImportUiState,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onOpenTimeSettings: () -> Unit,
    onUpdateSemester: (Long, Int) -> Unit
) {
    Column {
        Text(
            "导入配置确认",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val date = java.time.Instant.ofEpochMilli(uiState.semesterStartDate)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        val dateStr = date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
                        val maxSection = if (uiState.sectionTimes.isNotEmpty()) uiState.sectionTimes.size else uiState.detectedMaxSection
                        Text(
                            "当前配置：$dateStr 开学",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "共 ${uiState.weekCount} 周 · 每天 $maxSection 节课",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isExpanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = onOpenDatePicker,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DateRange, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("修改开学日期")
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("学期周数", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            FilledIconButton(
                                onClick = { 
                                    if (uiState.weekCount > 1) 
                                        onUpdateSemester(uiState.semesterStartDate, uiState.weekCount - 1) 
                                },
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.Remove, null) }
                            
                            Text(
                                text = "${uiState.weekCount}周",
                                modifier = Modifier.widthIn(min = 64.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            
                            FilledIconButton(
                                onClick = { 
                                    if (uiState.weekCount < 30) 
                                        onUpdateSemester(uiState.semesterStartDate, uiState.weekCount + 1) 
                                },
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.Add, null) }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text("作息设置", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenTimeSettings() }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("作息时间表", style = MaterialTheme.typography.titleSmall)
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                
                                val previewCount = 4
                                val displayTimes = uiState.sectionTimes.take(previewCount)
                                if (displayTimes.isNotEmpty()) {
                                    displayTimes.forEachIndexed { index, time ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "第 ${index + 1} 节",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                "${time.startTime} - ${time.endTime}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    if (uiState.sectionTimes.size > previewCount) {
                                        Text(
                                            "... 等共 ${uiState.sectionTimes.size} 节",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        "暂无时间数据，点击设置",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 推导强智 API 使用的基础地址
 *
 * 只保留协议、域名、端口三部分，避免把路径误当成接口地址。
 */
private fun deriveQiangZhiBaseUrl(url: String): String {
    return try {
        if (url.isBlank()) return ""
        val parsed = Uri.parse(url)
        val host = parsed.host.orEmpty()
        if (host.isBlank()) return ""
        val scheme = parsed.scheme ?: "https"
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        "$scheme://$host$port"
    } catch (_: Exception) {
        ""
    }
}

/**
 * 判断当前地址是否可能为强智教务系统
 */
private fun isQiangZhiHost(url: String): Boolean {
    return try {
        val parsed = Uri.parse(url)
        val host = parsed.host?.lowercase().orEmpty()
        host.contains("qzdatasoft") || url.contains("app.do?method=", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}
