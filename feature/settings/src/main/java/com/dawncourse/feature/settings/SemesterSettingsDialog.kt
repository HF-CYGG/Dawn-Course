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
 * @param maxCourseWeek 当前已有课程中的最大周次 (用于校验)
 * @param onDismissRequest 取消回调
 * @param onConfirm 确认回调，返回 (名称, 周数, 开学日期)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsDialog(
    initialName: String,
    initialWeeks: Int,
    initialStartDate: Long, // timestamp in millis
    maxCourseWeek: Int = 0,
    onDismissRequest: () -> Unit,
    onConfirm: (String, Int, Long) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var weeks by remember { mutableStateOf(initialWeeks.toFloat()) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // 日期选择器逻辑
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

    // 缩短周数时的二次确认对话框
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认修改？") },
            text = { Text("当前课表中第 $maxCourseWeek 周仍有课程，设定为 ${weeks.toInt()} 周将导致部分课程无法显示。确定要继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    onConfirm(name, weeks.toInt(), startDate)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("取消") }
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
                // 学期名称输入框
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学期名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 总周数设置 (滑块)
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
                            // 当设置的周数小于已有课程的最大周数时，显示红色警告色
                            color = if (weeks.toInt() < maxCourseWeek) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = weeks,
                        onValueChange = { weeks = it },
                        valueRange = 10f..30f,
                        steps = 19
                    )

                    // 显示警告文本
                    if (weeks.toInt() < maxCourseWeek) {
                        Text(
                            text = "⚠ 当前课表中第 $maxCourseWeek 周仍有课程，设定为 ${weeks.toInt()} 周将导致部分课程无法显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // 开学日期选择框 (只读，点击弹出日期选择器)
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
                    enabled = false, // 禁用输入，通过 clickable 响应点击
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
                    // 如果设置周数小于最大课程周数，触发二次确认
                    if (weeks.toInt() < maxCourseWeek) {
                        showConfirmDialog = true
                    } else {
                        onConfirm(name, weeks.toInt(), startDate)
                    }
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
