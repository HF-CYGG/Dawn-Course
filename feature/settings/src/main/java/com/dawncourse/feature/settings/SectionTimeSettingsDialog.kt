package com.dawncourse.feature.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.ui.components.BatchGenerateTimeContent

/**
 * 节次设置子页面类型
 */
private enum class SectionSettingsScreen { List, BatchGenerate }

/**
 * 节次时间设置弹窗
 *
 * @param settings 当前设置
 * @param viewModel 设置 ViewModel
 * @param onDismissRequest 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionTimeSettingsDialog(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit
) {
    // 正在编辑的节次序号（从 1 开始）
    var showTimePickerDialog by remember { mutableStateOf<Int?>(null) }
    // 当前子页面
    var currentScreen by remember { mutableStateOf(SectionSettingsScreen.List) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    if (targetState == SectionSettingsScreen.BatchGenerate) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut())
                    }
                },
                label = "SectionSettingsTransition"
            ) { screen ->
                when (screen) {
                    SectionSettingsScreen.List -> {
                        SectionTimeListContent(
                            settings = settings,
                            onDismissRequest = onDismissRequest,
                            onOpenBatchGenerate = { currentScreen = SectionSettingsScreen.BatchGenerate },
                            onEditSection = { showTimePickerDialog = it }
                        )
                    }
                    SectionSettingsScreen.BatchGenerate -> {
                        BatchGenerateTimeContent(
                            maxDailySections = settings.maxDailySections,
                            onDismissRequest = { currentScreen = SectionSettingsScreen.List },
                            onConfirm = { newTimes ->
                                viewModel.setSectionTimes(newTimes)
                                currentScreen = SectionSettingsScreen.List
                            }
                        )
                    }
                }
            }
        }
    }


    // 弹出时间编辑对话框
    val rawSectionIndex = showTimePickerDialog
    if (rawSectionIndex != null) {
        val maxDailySections = settings.maxDailySections.coerceAtLeast(1)
        val safeSectionIndex = rawSectionIndex.coerceIn(1, maxDailySections)

        val currentList = (1..maxDailySections).map { index ->
            settings.sectionTimes.getOrNull(index - 1) ?: run {
                val startHour = 8 + (index - 1)
                SectionTime(
                    String.format("%02d:00", startHour),
                    String.format("%02d:00", startHour + 1)
                )
            }
        }

        val currentTime = currentList.getOrNull(safeSectionIndex - 1)
        if (currentTime == null) {
            showTimePickerDialog = null
        } else {
            TimeRangeEditDialog(
                title = "编辑第 $safeSectionIndex 节时间",
                initialStartTime = currentTime.startTime,
                initialEndTime = currentTime.endTime,
                onDismissRequest = { showTimePickerDialog = null },
                onConfirm = { start, end ->
                    val newList = currentList.toMutableList()
                    newList[safeSectionIndex - 1] = SectionTime(start, end)
                    viewModel.setSectionTimes(newList)
                    showTimePickerDialog = null
                }
            )
        }
    }
}

/**
 * 节次列表页内容
 */
@Composable
private fun SectionTimeListContent(
    settings: AppSettings,
    onDismissRequest: () -> Unit,
    onOpenBatchGenerate: () -> Unit,
    onEditSection: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(24.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "节次时间设置",
                style = MaterialTheme.typography.headlineSmall
            )
            IconButton(onClick = onDismissRequest) {
                Icon(Icons.Default.Close, null)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Settings Button
        FilledTonalButton(
            onClick = onOpenBatchGenerate,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("快捷节次设置")
        }

        Spacer(modifier = Modifier.height(16.dp))

        val sectionTimes = remember(settings.sectionTimes, settings.maxDailySections) {
            (1..settings.maxDailySections).map { index ->
                if (index <= settings.sectionTimes.size) {
                    settings.sectionTimes[index - 1]
                } else {
                    val startHour = 8 + (index - 1)
                    SectionTime(
                        String.format("%02d:00", startHour),
                        String.format("%02d:00", startHour + 1)
                    )
                }
            }
        }

        // List Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                sectionTimes.forEachIndexed { index, time ->
                    val sectionIndex = index + 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditSection(sectionIndex) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "第 $sectionIndex 节",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${time.startTime} - ${time.endTime}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (index < sectionTimes.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 时间段编辑弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeEditDialog(
    title: String,
    initialStartTime: String,
    initialEndTime: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    // 解析 "HH:mm"
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
    
    var selectedTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

/**
 * 时间段选择 Tab 按钮
 */
@Composable
fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
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
