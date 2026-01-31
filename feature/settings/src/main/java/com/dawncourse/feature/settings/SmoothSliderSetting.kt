package com.dawncourse.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 平滑滑动条设置项
 *
 * 解决 DataStore 更新延迟导致的滑动卡顿问题。
 * 在滑动过程中只更新本地 UI 状态，仅在手指抬起 (onValueChangeFinished) 时提交数据到 DataStore。
 *
 * @param title 标题
 * @param value 外部数据源的当前值
 * @param onValueChangeFinished 滑动结束时的回调 (用于提交数据)
 * @param valueRange 值范围
 * @param valueText 值显示的格式化函数
 * @param description 描述
 * @param showDivider 是否显示分割线
 */
@Composable
fun SmoothSliderSetting(
    title: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: (Float) -> String,
    description: String? = null,
    showDivider: Boolean = false
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueText(sliderValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            valueRange = valueRange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}
