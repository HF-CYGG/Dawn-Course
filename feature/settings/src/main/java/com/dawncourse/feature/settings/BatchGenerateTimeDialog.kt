package com.dawncourse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dawncourse.core.domain.model.SectionTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchGenerateTimeDialog(
    maxDailySections: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (List<SectionTime>) -> Unit
) {
    var sectionDuration by remember { mutableStateOf("45") }
    var breakDuration by remember { mutableStateOf("10") }
    
    var morningStartTime by remember { mutableStateOf(LocalTime.of(8, 0)) }
    
    var afternoonStartSection by remember { mutableStateOf("5") }
    var afternoonStartTime by remember { mutableStateOf(LocalTime.of(14, 0)) }
    
    var eveningStartSection by remember { mutableStateOf("9") }
    var eveningStartTime by remember { mutableStateOf(LocalTime.of(19, 0)) }
    
    var showTimePickerFor by remember { mutableStateOf<String?>(null) } // "morning", "afternoon", "evening"

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "一键设置时间",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "快速生成全天课程时间表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 基础设置 (Card Style)
                    SettingsGroupCard(title = "基础设置", icon = Icons.Outlined.Timer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CompactNumericInput(
                                value = sectionDuration,
                                onValueChange = { sectionDuration = it },
                                label = "每节时长",
                                suffix = "分",
                                modifier = Modifier.weight(1f)
                            )
                            CompactNumericInput(
                                value = breakDuration,
                                onValueChange = { breakDuration = it },
                                label = "课间休息",
                                suffix = "分",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // 2. 时间节点 (Card Style)
                    SettingsGroupCard(title = "时间节点", icon = Icons.Filled.AccessTime) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Morning
                            TimeNodeRow(
                                icon = Icons.Filled.WbSunny,
                                label = "上午 (第1节)",
                                time = morningStartTime,
                                onClick = { showTimePickerFor = "morning" }
                            )
                            
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Afternoon
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompactNumericInput(
                                    value = afternoonStartSection,
                                    onValueChange = { afternoonStartSection = it },
                                    label = "下午起始",
                                    prefix = "第",
                                    suffix = "节",
                                    modifier = Modifier.width(90.dp)
                                )
                                TimeNodeRow(
                                    icon = Icons.Filled.WbTwilight,
                                    label = "开始时间",
                                    time = afternoonStartTime,
                                    onClick = { showTimePickerFor = "afternoon" },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Evening
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompactNumericInput(
                                    value = eveningStartSection,
                                    onValueChange = { eveningStartSection = it },
                                    label = "晚上起始",
                                    prefix = "第",
                                    suffix = "节",
                                    modifier = Modifier.width(90.dp)
                                )
                                TimeNodeRow(
                                    icon = Icons.Filled.Bedtime,
                                    label = "开始时间",
                                    time = eveningStartTime,
                                    onClick = { showTimePickerFor = "evening" },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val duration = sectionDuration.toIntOrNull() ?: 45
                            val breakTime = breakDuration.toIntOrNull() ?: 10
                            val pmStartSec = afternoonStartSection.toIntOrNull() ?: 5
                            val eveStartSec = eveningStartSection.toIntOrNull() ?: 9
                            
                            val generated = generateTimes(
                                maxSections = maxDailySections,
                                duration = duration,
                                breakTime = breakTime,
                                amStart = morningStartTime,
                                pmStartSec = pmStartSec,
                                pmStart = afternoonStartTime,
                                eveStartSec = eveStartSec,
                                eveStart = eveningStartTime
                            )
                            onConfirm(generated)
                        }
                    ) {
                        Text("生成时间表")
                    }
                }
            }
        }
    }
    
    // Time Picker Dialog
    if (showTimePickerFor != null) {
        val initialTime = when(showTimePickerFor) {
            "morning" -> morningStartTime
            "afternoon" -> afternoonStartTime
            "evening" -> eveningStartTime
            else -> LocalTime.now()
        }
        val pickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true
        )
        
        DatePickerDialog(
            onDismissRequest = { showTimePickerFor = null },
            confirmButton = {
                TextButton(onClick = {
                    val newTime = LocalTime.of(pickerState.hour, pickerState.minute)
                    when(showTimePickerFor) {
                        "morning" -> morningStartTime = newTime
                        "afternoon" -> afternoonStartTime = newTime
                        "evening" -> eveningStartTime = newTime
                    }
                    showTimePickerFor = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerFor = null }) { Text("取消") }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = pickerState)
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = null, // Cleaner look
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

@Composable
private fun CompactNumericInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    prefix: String = "",
    suffix: String = "",
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { c -> c.isDigit() }) onValueChange(it) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        prefix = if (prefix.isNotEmpty()) { { Text(prefix) } } else null,
        suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )
    )
}

@Composable
private fun TimeNodeRow(
    icon: ImageVector,
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Text(
            text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun generateTimes(
    maxSections: Int,
    duration: Int,
    breakTime: Int,
    amStart: LocalTime,
    pmStartSec: Int,
    pmStart: LocalTime,
    eveStartSec: Int,
    eveStart: LocalTime
): List<SectionTime> {
    val result = mutableListOf<SectionTime>()
    var currentTime = amStart
    
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    
    for (i in 1..maxSections) {
        // Determine start time for this section
        val startTime = when (i) {
            1 -> amStart
            pmStartSec -> pmStart
            eveStartSec -> eveStart
            else -> currentTime.plusMinutes(breakTime.toLong())
        }
        
        val actualStart = if (i == 1 || i == pmStartSec || i == eveStartSec) {
            startTime
        } else {
            currentTime.plusMinutes(breakTime.toLong())
        }
        
        val endTime = actualStart.plusMinutes(duration.toLong())
        
        result.add(SectionTime(actualStart.format(formatter), endTime.format(formatter)))
        
        currentTime = endTime
    }
    return result
}
