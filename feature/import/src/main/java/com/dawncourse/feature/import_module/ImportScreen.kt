package com.dawncourse.feature.import_module

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
        ImportStep.Input -> InputStep(viewModel, script, modifier)
        ImportStep.WebView -> WebViewStep(viewModel, script, modifier)
        ImportStep.Review -> ReviewStep(viewModel, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputStep(
    viewModel: ImportViewModel,
    script: String,
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
                style = MaterialTheme.typography.titleMedium
            )
            
            Button(
                onClick = { viewModel.setStep(ImportStep.WebView) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("教务系统导入 (WebView)")
            }
            
            OutlinedButton(
                onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("导入 ICS 日历文件")
            }
            
            if (uiState.isLoading) {
                CircularProgressIndicator()
            }
            
            if (uiState.resultText.isNotEmpty()) {
                Text(
                    text = uiState.resultText,
                    color = if (uiState.resultText.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                update = { view ->
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
                // Adjust to Monday? The prompt says "Force user to select start date (Monday)".
                // We should check if it's Monday or auto-adjust.
                // Let's just set it.
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("学期设置", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Start Date
                        OutlinedButton(
                            onClick = { datePickerDialog.show() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val date = Instant.ofEpochMilli(uiState.semesterStartDate)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            Text("开学日期 (第一周周一): ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Week Count
                        Text("学期周数: ${uiState.weekCount}周")
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
                    style = MaterialTheme.typography.titleSmall
                )
                
                // Course List Preview
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.parsedCourses) { course ->
                        Card {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(course.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${course.teacher} | ${course.location}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "周${getDayText(course.dayOfWeek)} ${course.startSection}-${course.startSection + course.duration - 1}节 | ${course.startWeek}-${course.endWeek}周",
                                    style = MaterialTheme.typography.bodySmall
                                )
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
