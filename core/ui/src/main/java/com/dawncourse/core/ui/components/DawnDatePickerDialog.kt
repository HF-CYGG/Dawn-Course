package com.dawncourse.core.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 通用日期选择对话框
 *
 * 统一应用内的日期选择样式，并强制使用简体中文显示。
 *
 * @param state DatePickerState，用于控制选中的日期
 * @param onDismissRequest 取消/关闭对话框的回调
 * @param onConfirm 确认选择的回调
 * @param title 对话框标题，默认为 "选择日期"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DawnDatePickerDialog(
    state: DatePickerState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String = "选择日期"
) {
    // 强制使用简体中文配置
    val configuration = LocalConfiguration.current
    val newConfiguration = remember(configuration) {
        android.content.res.Configuration(configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides newConfiguration
    ) {
        DatePickerDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text("取消")
                }
            }
        ) {
            DatePicker(
                state = state,
                title = {
                    Text(
                        text = title,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                },
                headline = {
                    val selectedDate = state.selectedDateMillis
                    val dateText = if (selectedDate != null) {
                        // Use UTC for calendar selection to avoid timezone shifts on display
                        val date = Instant.ofEpochMilli(selectedDate)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
                    } else {
                        "请选择日期"
                    }
                    Text(
                        text = dateText,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.headlineLarge
                    )
                }
            )
        }
    }
}

@Composable
fun AnimatedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = updateTransition(targetState = expanded, label = "menuTransition")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120) },
        label = "menuAlpha"
    ) { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120) },
        label = "menuScale"
    ) { if (it) 1f else 0.96f }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            visible = true
        } else {
            delay(120)
            visible = false
        }
    }
    if (visible) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            modifier = modifier.graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.1f, 0f)
            }
        ) {
            content()
        }
    }
}
