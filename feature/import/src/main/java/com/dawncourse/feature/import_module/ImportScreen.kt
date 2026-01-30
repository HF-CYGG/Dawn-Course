package com.dawncourse.feature.import_module

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                title = { Text("导入课表") },
                navigationIcon = {
                    if (uiState.step != ImportStep.Input) {
                        IconButton(onClick = { viewModel.setStep(ImportStep.Input) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ImportOptionCard(
            title = "教务系统网页导入",
            description = "通过内置浏览器访问教务系统自动解析",
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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Text("正在处理...", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
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

@Composable
private fun ImportOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WebViewStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState
) {
    // A simple WebView that loads the URL
    // In a real app, this would be more complex with script injection.
    // For now, we assume user navigates to a page and we try to parse it.
    
    var webView: WebView? by remember { mutableStateOf(null) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(uiState.webUrl)
                    webView = this
                }
            },
            modifier = Modifier.weight(1f)
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { webView?.reload() }) {
                Text("刷新")
            }
            Button(onClick = {
                // Try to extract content and parse
                // This is a simplification. Real parsing often needs JS injection.
                // Assuming parseResultFromWebView handles raw HTML or text.
                webView?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                    // html is a JSON string of the HTML content (quoted)
                    // We need to unquote it roughly
                    val unquoted = html.removePrefix("\"").removeSuffix("\"").replace("\\u003C", "<")
                    viewModel.parseResultFromWebView(unquoted)
                }
            }) {
                Text("解析当前页面")
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
    modifier: Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // M3 Date Picker State
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = uiState.semesterStartDate,
        initialDisplayedMonthMillis = uiState.semesterStartDate
    )
    
    // Handle Date Selection
    if (showDatePicker) {
        DawnDatePickerDialog(
            state = datePickerState,
            onDismissRequest = { showDatePicker = false },
            onConfirm = {
                datePickerState.selectedDateMillis?.let { millis ->
                    viewModel.updateSemesterSettings(millis, uiState.weekCount)
                }
                showDatePicker = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Start Date
                    OutlinedButton(
                        onClick = { showDatePicker = true },
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
            
            Button(
                onClick = { viewModel.confirmImport() },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("确认导入")
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
