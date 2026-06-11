package com.dawncourse.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private object SettingsMotion {
    const val PressedScale = 0.985f
    const val ValueEnterMillis = 140
    const val ValueExitMillis = 90
}

/**
 * 设置分组容器
 *
 * @param title 分组标题
 * @param content 分组内容
 */
@Composable
fun PreferenceCategory(
    title: String,
    supportingText: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
        animationSpec = tween(durationMillis = 160),
        label = "PreferenceCategoryBorder"
    )
    val headerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        animationSpec = tween(durationMillis = 160),
        label = "PreferenceCategoryHeader"
    )
    val animatedAccentColor by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(durationMillis = 180),
        label = "PreferenceCategoryAccent"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(if (supportingText == null) 24.dp else 34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(animatedAccentColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (supportingText != null) {
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/**
 * 通用设置行
 *
 * @param title 主标题
 * @param description 描述文本
 * @param icon 左侧图标
 * @param action 右侧动作区域
 * @param onClick 点击回调
 * @param showArrow 是否显示右侧箭头
 * @param showDivider 是否显示分割线
 * @param content 行内扩展内容
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    valueText: String? = null,
    action: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false,
    showDivider: Boolean = false,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconContentColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressEnabled = onClick != null
    val rowScale by animateFloatAsState(
        targetValue = if (pressEnabled && isPressed) SettingsMotion.PressedScale else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "SettingRowPressedScale"
    )
    val rowColor by animateColorAsState(
        targetValue = if (pressEnabled && isPressed) {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
        label = "SettingRowPressedColor"
    )

    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.scale(rowScale),
        color = rowColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            icon()
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (description != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (danger) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.78f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (valueText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    SettingValuePill(valueText = valueText, danger = danger)
                }
                if (action != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    action()
                }
                if (showArrow) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f)
                    )
                }
            }
            content()
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = if (icon != null) 56.dp else 16.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f)
                )
            }
        }
    }
}

/**
 * 带开关的设置行
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
 * 基础滑杆设置项（实时更新）
 */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: String,
    description: String? = null,
    showDivider: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            SliderValuePill(valueText = valueText)
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
        Slider(
            modifier = Modifier.height(36.dp),
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f))
        }
    }
}

@Composable
private fun SettingValuePill(
    valueText: String,
    danger: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (danger) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        AnimatedContent(
            targetState = valueText,
            transitionSpec = {
                fadeIn(animationSpec = tween(SettingsMotion.ValueEnterMillis)).togetherWith(
                    fadeOut(animationSpec = tween(SettingsMotion.ValueExitMillis))
                ).using(SizeTransform(clip = false))
            },
            label = "SettingValuePill"
        ) { text ->
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (danger) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }
    }
}

/**
 * 平滑滑杆设置项（滑动结束后回调）
 */
@Composable
fun SmoothSliderSetting(
    title: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    description: String? = null,
    showDivider: Boolean = false
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            SliderValuePill(valueText = valueText(sliderValue))
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
        Slider(
            modifier = Modifier.height(36.dp),
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            valueRange = valueRange
        )
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f))
        }
    }
}

@Composable
private fun SliderValuePill(valueText: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        AnimatedContent(
            targetState = valueText,
            transitionSpec = {
                fadeIn(animationSpec = tween(SettingsMotion.ValueEnterMillis)).togetherWith(
                    fadeOut(animationSpec = tween(SettingsMotion.ValueExitMillis))
                ).using(SizeTransform(clip = false))
            },
            label = "SliderValuePill"
        ) { text ->
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 检查更新入口项
 */
@Composable
fun UpdateSettingItem(
    currentVersion: String,
    onCheckUpdate: () -> Unit
) {
    SettingRow(
        title = "检查更新",
        description = "当前版本 v$currentVersion",
        icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp)) },
        onClick = onCheckUpdate,
        showArrow = true
    )
}
