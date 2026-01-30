package com.dawncourse.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("一键设置时间") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 时长设置
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = sectionDuration,
                        onValueChange = { if (it.all { c -> c.isDigit() }) sectionDuration = it },
                        label = { Text("每节时长(分)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = breakDuration,
                        onValueChange = { if (it.all { c -> c.isDigit() }) breakDuration = it },
                        label = { Text("课间休息(分)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                
                HorizontalDivider()
                
                // 2. 上午设置
                TimeSettingRow(
                    label = "上午 (第1节)",
                    time = morningStartTime,
                    onClick = { showTimePickerFor = "morning" }
                )
                
                // 3. 下午设置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = afternoonStartSection,
                        onValueChange = { if (it.all { c -> c.isDigit() }) afternoonStartSection = it },
                        label = { Text("下午起始节次") },
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TimeSettingRow(
                        label = "开始时间",
                        time = afternoonStartTime,
                        onClick = { showTimePickerFor = "afternoon" },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4. 晚上设置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = eveningStartSection,
                        onValueChange = { if (it.all { c -> c.isDigit() }) eveningStartSection = it },
                        label = { Text("晚上起始节次") },
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TimeSettingRow(
                        label = "开始时间",
                        time = eveningStartTime,
                        onClick = { showTimePickerFor = "evening" },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
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
                Text("生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
    
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
        
        AlertDialog(
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
            },
            text = { TimePicker(state = pickerState) }
        )
    }
}

@Composable
fun TimeSettingRow(
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = time.format(DateTimeFormatter.ofPattern("HH:mm")),
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = false, // We handle click manually
        modifier = modifier.clickable { onClick() },
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
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
        
        // Ensure time continuity (if manual setting jumps back, we respect manual setting)
        // But for normal flow (i.e. section 2), we use previous end + break.
        // The logic above: if it's a "start section", force the time. Else add break to *previous end*.
        
        // Re-eval:
        // For i=1: amStart.
        // For i=pmStartSec: pmStart.
        // For i=eveStartSec: eveStart.
        // For others: prevEnd + break.
        
        val actualStart = if (i == 1 || i == pmStartSec || i == eveStartSec) {
            startTime
        } else {
            // We need the end time of previous section.
            // But we don't have it in the loop variable directly easily unless we track it.
            // Let's track 'lastEndTime'
            // Wait, currentTime variable in my logic was supposed to be 'lastEndTime'.
            currentTime.plusMinutes(breakTime.toLong())
        }
        
        val endTime = actualStart.plusMinutes(duration.toLong())
        
        result.add(SectionTime(actualStart.format(formatter), endTime.format(formatter)))
        
        currentTime = endTime
    }
    return result
}
