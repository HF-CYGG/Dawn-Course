package com.dawncourse.feature.import_module

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import com.dawncourse.core.ui.util.CourseColorUtils
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.LocalDate

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
            description = "内置浏览器访问教务系统，自动解析课程表（支持新旧正方、青果系统）",
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
@Composable
private fun WebViewStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var inputUrl by remember { mutableStateOf(uiState.webUrl) }
    var isLoading by remember { mutableStateOf(false) }
    
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
                    // 启用 JS 和 DOM 存储，适配现代 SPA 网页
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            url?.let { inputUrl = it }
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            url?.let { inputUrl = it }
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
                        // 注入 JS 脚本以获取页面源码
                        // 包含对 frameset/iframe 的递归查找逻辑，以支持旧版教务系统的嵌套结构
                        val js = """
                            (function() {
                                function findScheduleHtml(doc) {
                                    if (!doc) return null;
                                    var html = doc.body ? doc.body.innerHTML : "";
                                    // 简单的特征匹配：是否包含“星期”和“节/课”
                                    if (html.indexOf('星期') !== -1 && (html.indexOf('节') !== -1 || html.indexOf('课') !== -1)) {
                                        return html;
                                    }
                                    
                                    // 递归检查 frames
                                    var frames = doc.getElementsByTagName('frame');
                                    for (var i = 0; i < frames.length; i++) {
                                        try {
                                            var frameDoc = frames[i].contentDocument || frames[i].contentWindow.document;
                                            var result = findScheduleHtml(frameDoc);
                                            if (result) return result;
                                        } catch(e) {}
                                    }
                                    
                                    // 递归检查 iframes
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
                                return findScheduleHtml(document) || document.documentElement.outerHTML;
                            })()
                        """.trimIndent()

                        webView?.evaluateJavascript(js) { result ->
                            // 处理 JS 返回的转义字符串
                            val rawHtml = try {
                                if (result == "null" || result == null) "" 
                                else JSONTokener(result).nextValue().toString()
                            } catch (e: Exception) {
                                ""
                            }
                            
                            if (rawHtml.isNotEmpty()) {
                                viewModel.parseResultFromWebView(rawHtml)
                            } else {
                                viewModel.updateResultText("未能提取到有效 HTML 内容")
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

// ReviewStep 和其他辅助组件将在文件剩余部分继续...
