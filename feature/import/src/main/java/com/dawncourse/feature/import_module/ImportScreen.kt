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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
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
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.*
import com.dawncourse.core.ui.util.CourseColorUtils
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImportSuccess: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
private fun SelectionStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState
) {
    val context = LocalContext.current
    val icsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
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

        ImportOptionCard(
            title = "教务系统网页导入",
            description = "内置浏览器访问教务系统，自动解析课程表（支持正方系统）",
            icon = Icons.Default.Search,
            onClick = { viewModel.setStep(ImportStep.WebView) }
        )

        ImportOptionCard(
            title = "ICS 日历文件导入",
            description = "选择设备上的 .ics 文件进行解析",
            icon = Icons.Default.DateRange,
            onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/plain", "*/*")) }
        )

        if (uiState.isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            Text("正在解析数据...", color = MaterialTheme.colorScheme.primary)
        }

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
            
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun WebViewStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var inputUrl by remember { mutableStateOf(uiState.webUrl) }
    var isLoading by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // URL Input Bar
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

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
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
                        webView?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            val decoded = runCatching { JSONTokener(html).nextValue() }.getOrNull()
                            val content = when {
                                decoded is String -> decoded
                                html == "null" -> ""
                                else -> html
                            }
                            if (content.isBlank()) {
                                viewModel.updateResultText("当前页面为空或未加载完成，请稍后重试")
                            } else {
                                viewModel.parseResultFromWebView(content)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isLoading
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("抓取当前页")
                }
            }
        }
    }
    
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            viewModel.setStep(ImportStep.Input)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingCourseIndex by remember { mutableIntStateOf(-1) }

    if (editingCourseIndex != -1 && editingCourseIndex < uiState.parsedCourses.size) {
        EditParsedCourseDialog(
            course = uiState.parsedCourses[editingCourseIndex],
            onDismiss = { editingCourseIndex = -1 },
            onConfirm = { 
                viewModel.updateParsedCourse(editingCourseIndex, it)
                editingCourseIndex = -1
            },
            onDelete = {
                viewModel.deleteParsedCourse(editingCourseIndex)
                editingCourseIndex = -1
            }
        )
    }

    Scaffold(
        bottomBar = {
            Button(
                onClick = { viewModel.confirmImport() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "确认导入 ${uiState.parsedCourses.size} 门课程",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Settings Section
            ImportSettingsSection(
                uiState = uiState,
                onSemesterSettingsChange = viewModel::updateSemesterSettings,
                onTimeSettingsChange = viewModel::updateTimeSettings
            )

            // 2. Course List Section
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "解析结果",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (uiState.parsedCourses.isEmpty()) {
                    Text(
                        "没有找到课程数据",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    uiState.parsedCourses.forEachIndexed { index, course ->
                        ParsedCourseItem(
                            course = course,
                            maxSection = uiState.detectedMaxSection,
                            courseDuration = uiState.courseDuration,
                            breakDuration = uiState.breakDuration,
                            bigBreakDuration = uiState.bigBreakDuration,
                            sectionsPerBigSection = uiState.sectionsPerBigSection,
                            onClick = { editingCourseIndex = index }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(80.dp)) // Bottom padding for FAB
        }
    }
}

@Composable
private fun ImportSettingsSection(
    uiState: ImportUiState,
    onSemesterSettingsChange: (Long, Int) -> Unit,
    onTimeSettingsChange: (Int, Int, Int, Int, Int) -> Unit
) {
    // Wrapper for time settings updates
    val updateTime = { max: Int, dur: Int, brk: Int, bigBrk: Int, bigSec: Int ->
        onTimeSettingsChange(max, dur, brk, bigBrk, bigSec)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Title
        Text(
            "基础配置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Group 1: Semester
                SettingGroup(title = "学期设置", icon = Icons.Default.DateRange) {
                    StepperRowItem(
                        label = "学期周数",
                        value = uiState.weekCount,
                        onValueChange = { onSemesterSettingsChange(uiState.semesterStartDate, it) },
                        range = 10..30
                    )
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Group 2: Time
                SettingGroup(title = "时间设置", icon = Icons.Default.AccessTime) {
                    StepperRowItem(
                        label = "每日节数",
                        value = uiState.detectedMaxSection,
                        onValueChange = { updateTime(it, uiState.courseDuration, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                        range = 4..20
                    )
                    StepperRowItem(
                        label = "单节时长",
                        value = uiState.courseDuration,
                        onValueChange = { updateTime(uiState.detectedMaxSection, it, uiState.breakDuration, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                        suffix = "分钟",
                        range = 30..120,
                        step = 5
                    )
                    StepperRowItem(
                        label = "课间时长",
                        value = uiState.breakDuration,
                        onValueChange = { updateTime(uiState.detectedMaxSection, uiState.courseDuration, it, uiState.bigBreakDuration, uiState.sectionsPerBigSection) },
                        suffix = "分钟",
                        range = 0..30,
                        step = 5
                    )
                     StepperRowItem(
                        label = "大节间隔",
                        value = uiState.bigBreakDuration,
                        onValueChange = { updateTime(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration, it, uiState.sectionsPerBigSection) },
                        suffix = "分钟",
                        range = 0..60,
                        step = 5
                    )
                    StepperRowItem(
                        label = "大节节数",
                        value = uiState.sectionsPerBigSection,
                        onValueChange = { updateTime(uiState.detectedMaxSection, uiState.courseDuration, uiState.breakDuration, uiState.bigBreakDuration, it) },
                        suffix = "节",
                        range = 1..4
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Group 3: Section Time Preview
                SettingGroup(title = "节次时间", icon = Icons.Default.FormatListNumbered) {
                     SectionTimePreviewRow(
                        maxSections = uiState.detectedMaxSection,
                        duration = uiState.courseDuration,
                        breakDuration = uiState.breakDuration,
                        bigBreakDuration = uiState.bigBreakDuration,
                        sectionsPerBigSection = uiState.sectionsPerBigSection
                     )
                }
            }
        }
    }
}

@Composable
private fun SectionTimePreviewRow(
    maxSections: Int,
    duration: Int,
    breakDuration: Int,
    bigBreakDuration: Int,
    sectionsPerBigSection: Int
) {
    // Generate times locally for preview
    // Start 8:00
    val times = remember(maxSections, duration, breakDuration, bigBreakDuration, sectionsPerBigSection) {
        val list = mutableListOf<String>()
        var currentMinutes = 8 * 60
        for (i in 1..maxSections) {
            val start = currentMinutes
            val end = start + duration
            
            val startStr = String.format("%02d:%02d", start / 60, start % 60)
            val endStr = String.format("%02d:%02d", end / 60, end % 60)
            list.add("$startStr - $endStr")
            
            val isBigBreak = (i % sectionsPerBigSection == 0)
            currentMinutes = end + if (isBigBreak) bigBreakDuration else breakDuration
        }
        list
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(times.size) { index ->
            SuggestionChip(
                onClick = { /* TODO: Open detailed editor */ },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第${index + 1}节",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            times[index],
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = null
            )
        }
    }
}

@Composable
private fun SettingGroup(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}

@Composable
private fun StepperRowItem(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    suffix: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledIconButton(
                onClick = { if (value - step >= range.first) onValueChange(value - step) },
                enabled = value - step >= range.first,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp))
            }
            
            Text(
                text = "$value$suffix",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = TextAlign.Center
            )
            
            FilledIconButton(
                onClick = { if (value + step <= range.last) onValueChange(value + step) },
                enabled = value + step <= range.last,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ParsedCourseItem(
    course: com.dawncourse.feature.import_module.model.ParsedCourse,
    maxSection: Int,
    courseDuration: Int,
    breakDuration: Int,
    bigBreakDuration: Int,
    sectionsPerBigSection: Int,
    onClick: () -> Unit
) {
    val isOutOfBounds = course.endSection > maxSection
    
    // Calculate time range (Start 8:00)
    fun calculateMinutes(section: Int): Int {
        // Section start logic: 
        // 8:00 (480 min)
        // + (section - 1) * duration
        // + breaks
        var minutes = 480
        for (i in 1 until section) {
            minutes += courseDuration
            val isBigBreak = (i % sectionsPerBigSection == 0)
            minutes += if (isBigBreak) bigBreakDuration else breakDuration
        }
        return minutes
    }

    val startMinute = calculateMinutes(course.startSection)
    // End time is start time + duration + internal breaks if spans multiple sections
    // But usually courses are contiguous.
    // Logic: calculate end of last section
    var endMinute = startMinute
    // For multi-section course, add duration and breaks between its internal sections
    for (i in course.startSection..course.endSection) {
        endMinute += courseDuration
        if (i < course.endSection) {
             val isBigBreak = (i % sectionsPerBigSection == 0)
             endMinute += if (isBigBreak) bigBreakDuration else breakDuration
        }
    }
    
    val startTimeStr = String.format("%02d:%02d", startMinute / 60, startMinute % 60)
    val endTimeStr = String.format("%02d:%02d", endMinute / 60, endMinute % 60)

    val cardColor = if (isOutOfBounds) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        CourseColorUtils.parseColor(CourseColorUtils.generateColor(course.name, course.teacher)).copy(alpha = 0.9f)
    }
    
    val contentColor = if (isOutOfBounds) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        CourseColorUtils.getBestContentColor(cardColor)
    }

    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Time & Section Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp)
            ) {
                Text(
                    text = "${course.startSection}-${course.endSection}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutOfBounds) MaterialTheme.colorScheme.error else contentColor
                )
                Text(
                    text = "节",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .height(40.dp)
                    .padding(horizontal = 12.dp),
                thickness = 1.dp,
                color = contentColor.copy(alpha = 0.2f)
            )

            // Right: Course Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = contentColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${course.location.ifEmpty { "未知地点" }} · ${course.teacher.ifEmpty { "未知教师" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "周${getDayText(course.dayOfWeek)} · $startTimeStr - $endTimeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}


private fun getDayText(day: Int): String {
    return when (day) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditParsedCourseDialog(
    course: com.dawncourse.feature.import_module.model.ParsedCourse,
    onDismiss: () -> Unit,
    onConfirm: (com.dawncourse.feature.import_module.model.ParsedCourse) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(course.name) }
    var location by remember { mutableStateOf(course.location) }
    var teacher by remember { mutableStateOf(course.teacher) }
    var startSection by remember { mutableStateOf(course.startSection.toString()) }
    var endSection by remember { mutableStateOf(course.endSection.toString()) }
    var dayOfWeek by remember { mutableStateOf(course.dayOfWeek) } // Int 1-7

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑课程") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("教室") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startSection,
                        onValueChange = { if (it.all { c -> c.isDigit() }) startSection = it },
                        label = { Text("开始节次") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = endSection,
                        onValueChange = { if (it.all { c -> c.isDigit() }) endSection = it },
                        label = { Text("结束节次") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
                
                Column {
                    Text("星期", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(7) { index ->
                            val day = index + 1
                            FilterChip(
                                selected = dayOfWeek == day,
                                onClick = { dayOfWeek = day },
                                label = { Text(getDayText(day)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = startSection.toIntOrNull() ?: course.startSection
                    val e = endSection.toIntOrNull() ?: course.endSection
                    onConfirm(course.copy(
                        name = name,
                        location = location,
                        teacher = teacher,
                        startSection = s,
                        endSection = e,
                        dayOfWeek = dayOfWeek
                    ))
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
