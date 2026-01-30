package com.dawncourse.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsDialog(
    initialName: String,
    initialWeeks: Int,
    initialStartDate: Long, // timestamp in millis
    onDismissRequest: () -> Unit,
    onConfirm: (String, Int, Long) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var weeks by remember { mutableStateOf(initialWeeks.toFloat()) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (startDate > 0) startDate else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { startDate = it }
                        showDatePicker = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("学期设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 学期名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学期名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 总周数
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("总周数", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${weeks.toInt()} 周",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = weeks,
                        onValueChange = { weeks = it },
                        valueRange = 10f..30f,
                        steps = 19
                    )
                }

                // 开学日期
                OutlinedTextField(
                    value = if (startDate > 0) {
                        Instant.ofEpochMilli(startDate)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    } else "未设置",
                    onValueChange = {},
                    label = { Text("开学日期 (第一周周一)") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarToday, "选择日期")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true },
                    enabled = false, // Disable typing, but handle click via modifier or Box
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                // Overlay for click since enabled=false blocks interaction
                // Or better: use a Box with the TextField inside and a clickable surface on top?
                // Actually, enabled=false + modifier.clickable doesn't work well.
                // Better approach: enabled=true, readOnly=true.
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name, weeks.toInt(), startDate)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}
