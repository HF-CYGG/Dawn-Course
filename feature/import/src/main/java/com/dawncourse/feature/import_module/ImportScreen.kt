package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CloudDownload
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.core.domain.model.SectionTime
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

private fun configureImportWebViewSecurity(webView: WebView) {
    val settings = webView.settings
    // Disable access to content:// URLs to avoid exposing protected content
    settings.allowContentAccess = false
}

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

    // 监听 ViewModel 的一次性事件 (如导入成功)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ImportEvent.Success) {
                onImportSuccess()
            }

        }
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
                    uiState = uiState
                )
                ImportStep.WebView -> WebViewStep(
                    viewModel = viewModel,
                    uiState = uiState
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
 * 步骤一：选择导入方式
 *
 * 提供两种入口：
 * 1. 网页导入：跳转到 WebViewStep
 * 2. 文件导入：调用系统文件选择器读取 .ics 文件
 */
@Composable
private fun SelectionStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
            description = "内置浏览器访问教务系统，自动解析课程表（支持新旧正方、青果、强智、起迪教务系统）",
            icon = Icons.Default.Search,
            onClick = { viewModel.setStep(ImportStep.WebView) }
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
    uiState: ImportUiState
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var webView: WebView? by remember { mutableStateOf(null) }
    var inputUrl by remember { mutableStateOf(uiState.webUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var pollJob: Job? by remember { mutableStateOf(null) }

    LaunchedEffect(uiState.webUrl) {
        if (uiState.webUrl.isNotBlank() && uiState.webUrl != inputUrl) {
            inputUrl = uiState.webUrl
        }
    }

    fun configureWebViewSecurity(targetWebView: WebView) {
        // Explicitly disallow access to content:// URLs to avoid exposing sensitive data
        val settings = targetWebView.settings
        settings.allowContentAccess = false
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
                        onGo = {
                            if (inputUrl.isNotBlank()) {
                                var url = inputUrl.trim()
                                // 自动补全 http 协议头
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    url = "http://$url"
                                }
                                viewModel.updateWebUrl(url)
                                webView?.loadUrl(url)
                            }
                        }
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
                    onClick = {
                        if (inputUrl.isNotBlank()) {
                            var url = inputUrl.trim()
                            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                url = "http://$url"
                            }
                            viewModel.updateWebUrl(url)
                            webView?.loadUrl(url)
                        }
                    },
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
        
        // WebView 容器
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 开启安全浏览（API 26+）
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = true
                    }
                    // 安全配置：禁用文件与内容访问，避免通过 WebView 读取本地文件或 content://
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    // 禁止 JS 自动弹窗
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // 兼容部分教务系统的 HTTP 资源（避免页面空白）
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // 安全配置：禁用文件访问，防止本地文件泄露
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
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

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url == "about:blank") {
                                return false
                            }
                            return !url.startsWith("http://") && !url.startsWith("https://")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val targetUrl = url.orEmpty()
                            if (targetUrl == "about:blank") {
                                return false
                            }
                            return !targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")
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
                        loadUrl(uiState.webUrl)
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
                        try {
                            // 读取并拼接通用工具函数和适配脚本
                            val assets = context.assets
                            val outputConsole = assets.open("js/output_console.js").bufferedReader().use { it.readText() }
                            val courseUtils = assets.open("js/course_utils.js").bufferedReader().use { it.readText() }
                            val qidiProvider = assets.open("js/qidi_provider.js").bufferedReader().use { it.readText() }

                            // 构建注入脚本
                            val js = StringBuilder()
                            js.append("(function() {\n")
                            js.append("try {\n")
                            js.append("window.__dawnResult = null;\n")
                            js.append("window.__dawnReady = false;\n")
                            js.append(outputConsole).append("\n;\n")
                            js.append(courseUtils).append("\n;\n")
                            js.append(qidiProvider).append("\n;\n")
                            
                            js.append("""
                                // 尝试运行 scheduleHtmlProvider
                                (async function() {
                                    try {
                                        if (typeof scheduleHtmlProvider === 'function') {
                                            console.log("Found scheduleHtmlProvider, running...");
                                            var result = await scheduleHtmlProvider();
                                            if (result !== "do not continue") {
                                                window.__dawnResult = result;
                                                window.__dawnReady = true;
                                                return;
                                            }
                                        }
                                    } catch(e) {
                                        console.error("Provider execution failed:", e);
                                    }
                                    
                                    // 降级策略：使用原有的 HTML 提取逻辑
                                    function findScheduleHtml(doc) {
                                        if (!doc) return null;
                                        
                                        // 1. 尝试强智教务系统特定 ID 提取 (参考 CrawlerCourseTable 逻辑)
                                        // 强智系统课程表通常使用 ID 格式 "节次-周次-2" (例如 "1-1-2")
                                        try {
                                            var qiangzhiData = [];
                                            var hasQiangzhi = false;
                                            // 遍历 1-5 节次 (对应强智系统的 5 个大节)
                                            for (var c = 1; c <= 5; c++) {
                                                // 遍历 1-7 周次 (对应周一到周日)
                                                for (var w = 1; w <= 7; w++) {
                                                    var id = c + "-" + w + "-2";
                                                    var el = doc.getElementById(id);
                                                    if (el) {
                                                        hasQiangzhi = true;
                                                        var text = el.innerText || el.textContent;
                                                        // 仅提取非空单元格
                                                        if (text && text.trim().length > 0) {
                                                            qiangzhiData.push({
                                                                "row": c,
                                                                "col": w,
                                                                "text": text.trim()
                                                            });
                                                        }
                                                    }
                                                }
                                            }
                                            // 如果检测到强智格式且包含数据，则直接返回特定 JSON
                                            if (hasQiangzhi && qiangzhiData.length > 0) {
                                                return JSON.stringify({
                                                    "type": "qiangzhi_direct",
                                                    "data": qiangzhiData
                                                });
                                            }
                                        } catch (e) { console.error("Qiangzhi extract failed", e); }
                                        
                                        var html = doc.body ? doc.body.innerHTML : "";
                                        if (html.indexOf('星期') !== -1 && (html.indexOf('节') !== -1 || html.indexOf('课') !== -1)) {
                                            return html;
                                        }
                                        var frames = doc.getElementsByTagName('frame');
                                        for (var i = 0; i < frames.length; i++) {
                                            try {
                                                var frameDoc = frames[i].contentDocument || frames[i].contentWindow.document;
                                                var result = findScheduleHtml(frameDoc);
                                                if (result) return result;
                                            } catch(e) {}
                                        }
                                        var iframes = doc.getElementsByTagName('iframe');
                                        for (var i = 0; i < iframes.length; i++) {
                                            try {
                                                var frameDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                                                var result = findScheduleHtml(frameDoc);
                                                if (result) return result;
                                            } catch(e) {}
                                        }
                                        return null;
                                    }
                                    window.__dawnResult = findScheduleHtml(document) || document.documentElement.outerHTML;
                                    window.__dawnReady = true;
                                })();
                            } catch(e) {
                                console.error("Provider execution failed:", e);
                                window.__dawnReady = true;
                            }
                            })();
                            """.trimIndent())

                            val currentWebView = webView
                            if (currentWebView == null) {
                                viewModel.updateResultText("未能提取到有效 HTML 内容")
                                return@Button
                            }
                            currentWebView.evaluateJavascript(js.toString(), null)
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
    val scrollState = rememberScrollState()

    var showDatePicker by remember { mutableStateOf(false) }
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

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 1. 解析结果概览
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "解析成功",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "共找到 ${uiState.parsedCourses.size} 门课程",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 课程列表详情
        if (uiState.parsedCourses.isNotEmpty()) {
            ParsedCourseList(
                courses = uiState.parsedCourses,
                onDelete = { index -> viewModel.deleteParsedCourse(index) }
            )
        }

        // 2. 学期设置
        ImportSettingsSection(title = "学期设置") {
            // 开学日期
            ListItem(
                headlineContent = { Text("开学日期") },
                supportingContent = {
                    val date = java.time.Instant.ofEpochMilli(uiState.semesterStartDate)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")))
                },
                leadingContent = { Icon(Icons.Default.DateRange, null) },
                modifier = Modifier.clickable { showDatePicker = true }
            )
            
            HorizontalDivider()

            // 学期周数
            ListItem(
                headlineContent = { Text("学期周数") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = { 
                                if (uiState.weekCount > 1) 
                                    viewModel.updateSemesterSettings(uiState.semesterStartDate, uiState.weekCount - 1) 
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Remove, null) }
                        
                        Text(
                            text = "${uiState.weekCount}周",
                            modifier = Modifier.widthIn(min = 48.dp),
                            textAlign = TextAlign.Center
                        )
                        
                        FilledIconButton(
                            onClick = { 
                                if (uiState.weekCount < 30) 
                                    viewModel.updateSemesterSettings(uiState.semesterStartDate, uiState.weekCount + 1) 
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        }

        // 3. 作息时间设置
        ImportSettingsSection(title = "作息时间") {
            // 单节时长
            ListItem(
                headlineContent = { Text("单节时长") },
                leadingContent = { Icon(Icons.Outlined.Timer, null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration - 5, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                            enabled = uiState.courseDuration > 5,
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Remove, null) }
                        
                        Text("${uiState.courseDuration}分钟", Modifier.padding(horizontal = 8.dp))
                        
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration + 5, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Add, null) }
                    }
                }
            )

            HorizontalDivider()
            
            // 课间时长
             ListItem(
                headlineContent = { Text("小课间") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration - 1, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                            enabled = uiState.breakDuration > 0,
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Remove, null) }
                        
                        Text("${uiState.breakDuration}分钟", Modifier.padding(horizontal = 8.dp))
                        
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration + 1, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Add, null) }
                    }
                }
            )

             HorizontalDivider()
            
            // 大节包含小节数 (用于大课间逻辑)
             ListItem(
                headlineContent = { Text("大节包含小节数") },
                supportingContent = { Text("每隔几节课休息一次大课间") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection - 1) },
                            enabled = uiState.sectionsPerBigSection > 1,
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Remove, null) }
                        
                        Text("${uiState.sectionsPerBigSection}节", Modifier.padding(horizontal = 8.dp))
                        
                        FilledIconButton(
                            onClick = { viewModel.updateTimeSettings(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection + 1) },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        }

        // 4. 时间表预览
        ImportSettingsSection(title = "时间表预览") {
             // 简单的预览列表
             Column(modifier = Modifier.padding(16.dp)) {
                 // 上午
                 SectionTimePreviewRow("上午", 1, uiState.amStartTime)
                 // 下午
                 SectionTimePreviewRow("下午", uiState.pmStartSection, uiState.pmStartTime)
                 // 晚上
                 SectionTimePreviewRow("晚上", uiState.eveStartSection, uiState.eveStartTime)
             }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.confirmImport() },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Default.Check, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("确认导入")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ImportSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SectionTimePreviewRow(
    label: String,
    startSection: Int,
    startTime: LocalTime
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "第 $startSection 节 ${startTime.format(DateTimeFormatter.ofPattern("HH:mm"))} 开始",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
