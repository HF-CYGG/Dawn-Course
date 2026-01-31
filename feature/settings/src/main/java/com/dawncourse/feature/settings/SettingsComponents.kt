package com.dawncourse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设置页面分组标题与卡片容器
 *
 * @param title 分组标题
 * @param modifier 修饰符
 * @param content 卡片内容
 */
@Composable
fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                content()
            }
        }
    }
}

/**
 * 通用设置项行
 *
 * @param title 标题
 * @param description 描述文本 (可选)
 * @param icon 左侧图标 (可选)
 * @param action 右侧操作组件 (可选)
 * @param onClick 点击事件 (可选)
 * @param showDivider 是否显示底部分割线
 * @param content 额外的自定义内容 (显示在标题下方)
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    content: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = { onClick?.invoke() },
        color = Color.Transparent,
        enabled = onClick != null
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(modifier = Modifier.padding(end = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (action != null) {
                    Box(modifier = Modifier.padding(start = 8.dp)) { action() }
                }
            }
            if (content != null) {
                content()
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * 开关设置项
 *
 * @param title 标题
 * @param description 描述
 * @param icon 图标
 * @param checked 开关状态
 * @param onCheckedChange 状态变更回调
 * @param showDivider 是否显示分割线
 */
@Composable
fun SwitchSetting(
    title: String,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = false
) {
    SettingRow(
        title = title,
        description = description,
        icon = icon,
        action = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        onClick = { onCheckedChange(!checked) },
        showDivider = showDivider
    )
}

/**
 * 滑动条设置项
 *
 * @param title 标题
 * @param value 当前值
 * @param onValueChange 值变更回调
 * @param valueRange 值范围
 * @param steps 分段数
 * @param valueText 当前值的文本显示 (显示在标题右侧)
 * @param description 描述
 * @param icon 图标
 * @param showDivider 是否显示分割线
 */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String? = null,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    showDivider: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (icon != null) {
                    Box(modifier = Modifier.padding(end = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                Column {
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
            }
            if (valueText != null) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun ColorPicker(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    colors: List<Color> = listOf(
        Color(0xFFE5E7EB), // Gray
        Color(0xFFEF4444), // Red
        Color(0xFFF97316), // Orange
        Color(0xFFEAB308), // Yellow
        Color(0xFF22C55E), // Green
        Color(0xFF3B82F6), // Blue
        Color(0xFF8B5CF6), // Purple
        Color(0xFFEC4899)  // Pink
    )
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        val initialColor = remember(selectedColor) {
            try {
                Color(android.graphics.Color.parseColor(selectedColor))
            } catch (e: Exception) {
                Color.Gray
            }
        }
        ColorPickerDialog(
            initialColor = initialColor,
            onDismiss = { showCustomDialog = false },
            onConfirm = { color ->
                val hex = "#" + Integer.toHexString(color.toArgb()).uppercase().takeLast(6)
                onColorSelected(hex)
                showCustomDialog = false
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        colors.forEach { color ->
            val hex = "#" + Integer.toHexString(color.toArgb()).uppercase().takeLast(6)
            val isSelected = selectedColor.equals(hex, ignoreCase = true)
            
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onColorSelected(hex) }
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else Modifier
                    )
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = null,
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.align(Alignment.Center).size(16.dp)
                    )
                }
            }
        }

        // Custom Color Button
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
                    )
                )
                .clickable { showCustomDialog = true }
        ) {
             Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Add,
                contentDescription = "Custom",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(20.dp)
            )
        }
    }
}
