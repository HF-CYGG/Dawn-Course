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
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 学期设置对话框
 *
 * 用于设置当前学期的名称、总周数以及开学日期。
 * 开学日期决定了当前是第几周的计算逻辑。
 *
 * @param initialName 初始学期名称
 * @param initialWeeks 初始总周数
 * @param initialStartDate 初始开学日期时间戳 (毫秒)
 * @param onDismissRequest 取消回调
 * @param onConfirm 确认回调，返回 (名称, 周数, 开学日期)
 */
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
        DawnDatePickerDialog(
            state = datePickerState,
            onDismissRequest = { showDatePicker = false },
            onConfirm = {
                datePickerState.selectedDateMillis?.let { startDate = it }
                showDatePicker = false
            }
        )
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
