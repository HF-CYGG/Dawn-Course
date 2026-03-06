package com.dawncourse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionTimeSettingsDialog(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit
) {
    var showTimePickerDialog by remember { mutableStateOf<Int?>(null) }
    var showBatchGenerateDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "节次时间设置",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, null)
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showBatchGenerateDialog = true }) {
                        Text("一键生成")
                    }
                }

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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    sectionTimes.forEachIndexed { index, time ->
                        val sectionIndex = index + 1
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTimePickerDialog = sectionIndex }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
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
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }

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

    if (showBatchGenerateDialog) {
        BatchGenerateTimeDialog(
            maxDailySections = settings.maxDailySections,
            onDismissRequest = { showBatchGenerateDialog = false },
            onConfirm = { newTimes ->
                viewModel.setSectionTimes(newTimes)
                showBatchGenerateDialog = false
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
