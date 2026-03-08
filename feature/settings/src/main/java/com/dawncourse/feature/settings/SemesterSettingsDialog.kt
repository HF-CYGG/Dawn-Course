package com.dawncourse.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 学期设置对话框 (美化版)
 *
 * 用于设置当前学期的名称、总周数以及开学日期。
 * 采用 Material 3 高级感设计，包含背景容器感和直观的交互卡片。
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
    var weeks by remember { mutableFloatStateOf(initialWeeks.toFloat()) }
    var startDate by remember { mutableLongStateOf(initialStartDate) }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "学期设置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))

                // 学期名称 - 使用填充式更显现代
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学期名称") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(24.dp))

                // 总周数控制区 - 加上背景块
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("总周数", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${weeks.toInt()} 周",
                                color = if (weeks.toInt() < maxCourseWeek) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
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
                                text = "⚠ 将导致部分课程隐藏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 开学日期 - 模拟 iOS 的列表点击感
                Text(
                    "开学日期 (第一周周一)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { showDatePicker = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CalendarToday,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (startDate > 0) {
                                Instant.ofEpochMilli(startDate)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            } else {
                                "点击选择日期"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (weeks.toInt() < maxCourseWeek) {
                                showConfirmDialog = true
                            } else {
                                onConfirm(name, weeks.toInt(), startDate)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("保存设置") }
                }
            }
        }
    }
}
