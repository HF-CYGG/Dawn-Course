package com.dawncourse.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalTime

/**
 * 完全符合 Material 3 规范的时间选择器对话框
 *
 * 包含表盘模式 (TimePicker) 和输入模式 (TimeInput) 切换。
 * 遵循 M3 的布局、间距、圆角 (28.dp) 和交互规范。
 *
 * @param initialTime 初始时间
 * @param onDismiss 取消回调
 * @param onConfirm 确认回调，返回选择的 [LocalTime]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizedTimePickerDialog(
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = false // 必须为 false 才能显示 AM/PM 垂直切换栏
    )

    var showInputMode by remember { mutableStateOf(false) }

    // 使用 Dialog 自定义容器以符合 M3 间距和圆角
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp), // M3 标准大圆角
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部标题
                Text(
                    text = if (showInputMode) "输入时间" else "选择时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )

                // 核心组件：根据模式切换显示
                if (showInputMode) {
                    TimeInput(
                        state = state,
                        colors = TimePickerDefaults.colors(
                            timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    )
                } else {
                    TimePicker(
                        state = state,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            selectorColor = MaterialTheme.colorScheme.primary,
                            timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 底部动作条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：切换输入模式图标
                    IconButton(onClick = { showInputMode = !showInputMode }) {
                        Icon(
                            imageVector = if (showInputMode) Icons.Outlined.Schedule else Icons.Filled.Keyboard,
                            contentDescription = if (showInputMode) "切换至表盘模式" else "切换至输入模式",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 右侧：M3 规范要求使用文本按钮 (TextButton)
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    TextButton(onClick = {
                        onConfirm(LocalTime.of(state.hour, state.minute))
                    }) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
