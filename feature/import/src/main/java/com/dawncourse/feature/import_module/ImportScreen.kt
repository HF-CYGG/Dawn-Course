package com.dawncourse.feature.import_module

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun ImportScreen(
    onImportSuccess: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // ... (rest of the file until ReviewStep)

    if (uiState.step == ImportStep.Review) {
        ReviewStep(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // ... (rest of the main function)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("导入课表") },
                    navigationIcon = {
                        if (uiState.step != ImportStep.Selection) {
                            IconButton(onClick = { viewModel.setStep(ImportStep.Selection) }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (uiState.step) {
                    ImportStep.Selection -> SelectionStep(
                        viewModel = viewModel,
                        uiState = uiState,
                        onImportSuccess = onImportSuccess
                    )
                    ImportStep.WebView -> WebViewStep(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                    ImportStep.Input -> InputStep(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                    else -> {}
                }
            }
        }
    }
    
    // ... (LaunchedEffect for ImportSuccess)
    LaunchedEffect(uiState.importSuccess) {
        if (uiState.importSuccess) {
            onImportSuccess()
            viewModel.resetImportSuccess()
        }
    }
}

// ... (Other steps: SelectionStep, WebViewStep, InputStep)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    viewModel: ImportViewModel,
    modifier: Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
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

// Helper components for steps
@Composable
private fun SelectionStep(
    viewModel: ImportViewModel,
    uiState: ImportUiState,
    onImportSuccess: () -> Unit
) {
    val icsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importIcsFile(context = null, uri = it) } // Context passed in ViewModel is tricky, better read content here or pass context.
        // Actually ViewModel function signature might need Context or ContentResolver.
        // Let's check ImportViewModel usage. It seems it might be handling it internally if it has context or we need to pass it.
        // In previous code: onClick = { icsLauncher.launch(...) }
        // And the callback?
        // Ah, the previous code snippet for SelectionStep was:
        /*
            val icsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { viewModel.importIcsFile(context, it) }
            }
        */
        // I need to restore the full content of ImportScreen.kt properly.
        // I am overwriting the file, so I must include everything I didn't change or replace it with the read content.
        // I read offset 420, which is ReviewStep.
        // I need to be careful not to delete SelectionStep, WebViewStep, etc.
        // Since I don't have the full content of ImportScreen.kt in memory (only parts), I should use `apply_patch` instead of `write_file` for `ImportScreen.kt`.
        // `write_file` overwrites EVERYTHING.
        // I will use `apply_patch`.
    }
    // ...
}
