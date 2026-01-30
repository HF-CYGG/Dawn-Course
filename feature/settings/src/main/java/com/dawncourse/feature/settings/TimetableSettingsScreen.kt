package com.dawncourse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.model.SectionTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var showTimePickerDialog by remember { mutableStateOf<Int?>(null) } // Int is the section index (1-based) being edited

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表显示设置") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. 基础设置
            PreferenceCategory(title = "基础设置") {
                // 每天总节数
                SliderSetting(
                    title = "每天总节数",
                    value = settings.maxDailySections.toFloat(),
                    onValueChange = { viewModel.setMaxDailySections(it.toInt()) },
                    valueRange = 8f..16f,
                    steps = 7,
                    valueText = "${settings.maxDailySections} 节"
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 默认课程时长
                SliderSetting(
                    title = "默认课程时长",
                    value = settings.defaultCourseDuration.toFloat(),
                    onValueChange = { viewModel.setDefaultCourseDuration(it.toInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                    valueText = "${settings.defaultCourseDuration} 节",
                    description = "新建课程时默认选中的时长"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. 节次时间设置
            PreferenceCategory(title = "节次时间") {
                Text(
                    text = "点击修改每节课的起止时间",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )

                // Generate display list (merge settings with defaults)
                val sectionTimes = remember(settings.sectionTimes, settings.maxDailySections) {
                    (1..settings.maxDailySections).map { index ->
                        if (index <= settings.sectionTimes.size) {
                            settings.sectionTimes[index - 1]
                        } else {
                            // Default: 8:00 start, 1 hour each
                            val startHour = 8 + (index - 1)
                            SectionTime(
                                String.format("%02d:00", startHour),
                                String.format("%02d:00", startHour + 1) // Default 60 min slots? Or maybe 45? 
                                // Previous logic was just a start label. Let's assume 45 min + 10 min break default if generating new.
                                // Actually, user previous logic was: Text("${TIMETABLE_START_HOUR + i - 1}:00")
                                // So Section 1: 8:00. Section 2: 9:00.
                                // This implies 60 minute slots starting on the hour.
                            )
                        }
                    }
                }

                sectionTimes.forEachIndexed { index, time ->
                    val sectionIndex = index + 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTimePickerDialog = sectionIndex }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 $sectionIndex 节",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${time.startTime} - ${time.endTime}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (index < sectionTimes.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 网格样式 (高级)
            PreferenceCategory(title = "网格线设置") {
                // 样式选择
                SettingRow(title = "线样式") {
                    Row(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                        DividerType.entries.forEach { type ->
                            val selected = settings.dividerType == type
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setDividerType(type) },
                                label = { 
                                    Text(when(type) {
                                        DividerType.SOLID -> "实线"
                                        DividerType.DASHED -> "虚线"
                                        DividerType.DOTTED -> "点线"
                                    }) 
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                // 宽度
                SliderSetting(
                    title = "线宽",
                    value = settings.dividerWidthDp,
                    onValueChange = { viewModel.setDividerWidth(it) },
                    valueRange = 0.5f..5f,
                    steps = 9,
                    valueText = "${String.format("%.1f", settings.dividerWidthDp)} dp",
                    showDivider = true
                )

                // 不透明度
                SliderSetting(
                    title = "不透明度",
                    value = settings.dividerAlpha,
                    onValueChange = { viewModel.setDividerAlpha(it) },
                    valueRange = 0f..1f,
                    steps = 10,
                    valueText = "${(settings.dividerAlpha * 100).toInt()}%",
                    showDivider = true
                )

                // 颜色选择器
                SettingRow(
                    title = "网格线颜色",
                    showDivider = false
                ) {
                    ColorPicker(
                        selectedColor = settings.dividerColor,
                        onColorSelected = { viewModel.setDividerColor(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. 显示设置
            PreferenceCategory(title = "显示设置") {
                SettingRow(
                    title = "显示日期",
                    description = "在星期下方显示具体日期 (如 9.1)",
                    showDivider = false,
                    action = {
                        Switch(
                            checked = settings.showDateInHeader,
                            onCheckedChange = { viewModel.setShowDateInHeader(it) }
                        )
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Time Picker Dialog Logic
    if (showTimePickerDialog != null) {
        val sectionIndex = showTimePickerDialog!!
        // Get current time or default
        val currentList = (1..settings.maxDailySections).map { index ->
             if (index <= settings.sectionTimes.size) settings.sectionTimes[index - 1]
             else {
                 val startHour = 8 + (index - 1)
                 SectionTime(String.format("%02d:00", startHour), String.format("%02d:00", startHour + 1))
             }
        }
        val currentTime = currentList[sectionIndex - 1]
        
        // We need a custom dialog to edit both start and end, or two steps.
        // Let's do a simple dialog with two text fields or two clickable areas for TimePicker?
        // Or better: Two TimePickers in a row? Too big.
        // Let's use a custom Dialog with state to toggle between Start/End picker.
        
        TimeRangeEditDialog(
            title = "编辑第 $sectionIndex 节时间",
            initialStartTime = currentTime.startTime,
            initialEndTime = currentTime.endTime,
            onDismissRequest = { showTimePickerDialog = null },
            onConfirm = { start, end ->
                // Update the list
                val newList = currentList.toMutableList()
                newList[sectionIndex - 1] = SectionTime(start, end)
                viewModel.setSectionTimes(newList)
                showTimePickerDialog = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeEditDialog(
    title: String,
    initialStartTime: String,
    initialEndTime: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    // Parse time for picker state
    fun parse(t: String): Pair<Int, Int> {
        val p = t.split(":").map { it.toIntOrNull() ?: 0 }
        return (p.getOrNull(0) ?: 0) to (p.getOrNull(1) ?: 0)
    }

    val startState = rememberTimePickerState(
        initialHour = parse(initialStartTime).first,
        initialMinute = parse(initialStartTime).second,
        is24Hour = true
    )
    val endState = rememberTimePickerState(
        initialHour = parse(initialEndTime).first,
        initialMinute = parse(initialEndTime).second,
        is24Hour = true
    )
    
    // Tab state: 0 for Start, 1 for End
    var selectedTab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Toggle Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TabButton(
                        text = "开始: ${String.format("%02d:%02d", startState.hour, startState.minute)}",
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    TabButton(
                        text = "结束: ${String.format("%02d:%02d", endState.hour, endState.minute)}",
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Picker
                if (selectedTab == 0) {
                    TimePicker(state = startState)
                } else {
                    TimePicker(state = endState)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = String.format("%02d:%02d", startState.hour, startState.minute)
                    val end = String.format("%02d:%02d", endState.hour, endState.minute)
                    onConfirm(start, end)
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

@Composable
fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
    }
}
