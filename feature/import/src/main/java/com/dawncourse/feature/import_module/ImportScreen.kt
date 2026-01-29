package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 课程导入页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImportSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ImportViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    
    // Load parser script from assets
    val script = remember {
        try {
            context.assets.open("parsers/zhengfang.js").use { 
                BufferedReader(InputStreamReader(it)).readText() 
            }
        } catch (e: Exception) { "" }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is ImportEvent.Success) {
                onImportSuccess()
            }
        }
    }

    // Back handler to navigate steps
    BackHandler(enabled = uiState.step != ImportStep.Input) {
        if (uiState.step == ImportStep.WebView) {
            viewModel.setStep(ImportStep.Input)
        } else if (uiState.step == ImportStep.Review) {
            viewModel.setStep(ImportStep.Input) // Or WebView? Input for now.
        }
    }

    when (uiState.step) {
        ImportStep.Input -> InputStep(viewModel, modifier)
        ImportStep.WebView -> WebViewStep(viewModel, script, modifier)
        ImportStep.Review -> ReviewStep(viewModel, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputStep(
    viewModel: ImportViewModel,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            viewModel.runIcsImport(text)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("导入课程") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "请选择导入方式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            ImportOptionCard(
                title = "教务系统导入",
                description = "内置浏览器登录教务系统，自动抓取课表",
                icon = Icons.Default.Search,
                onClick = { viewModel.setStep(ImportStep.WebView) }
            )
            
            ImportOptionCard(
                title = "ICS 日历文件导入",
                description = "导入导出的 .ics 日历文件",
                icon = Icons.Default.DateRange,
                onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/plain", "*/*")) }
            )
            
            if (uiState.isLoading) {
                Spacer(modifier = Modifier.height(32.dp))
                CircularProgressIndicator()
                Text("正在处理...", modifier = Modifier.padding(top = 16.dp))
            }
            
            if (uiState.resultText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.resultText.contains("失败")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.resultText,
                        modifier = Modifier.padding(16.dp),
                        color = if (uiState.resultText.contains("失败")) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ImportOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewStep(
    viewModel: ImportViewModel,
    script: String,
    modifier: Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var webView: WebView? by remember { mutableStateOf(null) }
    var urlText by remember { mutableStateOf(uiState.webUrl) }
    var canImport by remember { mutableStateOf(false) } // Simple detection

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("教务系统登录") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setStep(ImportStep.Input) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, "Reload")
                        }
                        Button(
                            onClick = {
                                webView?.evaluateJavascript(
                                    "(function() { return document.documentElement.outerHTML; })();"
                                ) { html ->
                                    // HTML comes back as a JSON string (e.g. "\u003Chtml...")
                                    // We need to unescape it roughly or just pass it.
                                    // evaluateJavascript returns the result as a JSON string.
                                    // If the result is a string, it's wrapped in quotes and escaped.
                                    // We should remove the surrounding quotes and unescape common chars if needed,
                                    // OR let ScriptEngine handle it?
                                    // Actually, standard JSON.parse(html) in JS logic might fail if we pass a double-JSON-encoded string.
                                    // Let's strip the quotes.
                                    val cleanHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                        // Simple unescape for common chars if necessary, 
                                        // but usually just stripping quotes is enough for raw HTML injection 
                                        // if we treat it as string.
                                        // However, standard unescape is better.
                                        // For now, let's just pass it. The ScriptEngine will put it into a JS variable.
                                        // NOTE: evaluateJavascript returns "\"<html>...</html>\"".
                                        // We need the actual HTML content.
                                        try {
                                            org.json.JSONTokener(html).nextValue().toString()
                                        } catch (e: Exception) {
                                            html
                                        }
                                    } else {
                                        html
                                    }
                                    viewModel.parseHtmlFromWebView(cleanHtml, script)
                                }
                            },
                            // Always enabled for manual trigger, or highlight when detected
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (canImport) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("导入")
                        }
                    }
                )
                // URL Bar
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { 
                            webView?.loadUrl(urlText) 
                            viewModel.updateWebUrl(urlText)
                        }) {
                            Icon(Icons.Default.Check, "Go")
                        }
                    },
                    placeholder = { Text("输入教务系统网址") }
                )
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text("正在解析...", modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { urlText = it }
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Simple heuristic: if URL contains "kbcx" (课表查询) or similar
                                canImport = url?.contains("kbcx") == true || url?.contains("table") == true
                            }
                        }
                        loadUrl(uiState.webUrl)
                        webView = this
                    }
                },
                update = { _ ->
                    // View update logic if needed
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    viewModel: ImportViewModel,
    modifier: Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Date Picker Dialog
    val datePickerDialog = remember {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = uiState.semesterStartDate
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selected = java.util.Calendar.getInstance()
                selected.set(year, month, dayOfMonth, 0, 0, 0)
                viewModel.updateSemesterSettings(selected.timeInMillis, uiState.weekCount)
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("确认导入信息") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.setStep(ImportStep.Input) }) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                },
                actions = {
                    Button(onClick = { viewModel.confirmImport() }) {
                        Icon(Icons.Default.Check, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("确认导入")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Settings Card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "学期设置", 
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Start Date
                        OutlinedButton(
                            onClick = { datePickerDialog.show() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val date = Instant.ofEpochMilli(uiState.semesterStartDate)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            Icon(Icons.Default.DateRange, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开学日期 (第一周周一): ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Week Count
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("学期周数: ${uiState.weekCount}周", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Slider(
                            value = uiState.weekCount.toFloat(),
                            onValueChange = { viewModel.updateSemesterSettings(uiState.semesterStartDate, it.toInt()) },
                            valueRange = 1f..30f,
                            steps = 29
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "解析结果: ${uiState.parsedCourses.size} 个课程段",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Course List Preview
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(uiState.parsedCourses) { course ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Color indicator
                                Box(
                                    modifier = Modifier
                                        .size(4.dp, 40.dp)
                                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(course.name, style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "${course.teacher} | ${course.location}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "周${getDayText(course.dayOfWeek)} ${course.startSection}-${course.startSection + course.duration - 1}节 | ${course.startWeek}-${course.endWeek}周",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
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
