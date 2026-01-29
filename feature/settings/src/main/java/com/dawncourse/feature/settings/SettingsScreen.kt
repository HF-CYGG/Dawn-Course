package com.dawncourse.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.DividerType

/**
 * 设置页面
 *
 * 提供应用程序的个性化设置选项，经过视觉优化和功能重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore
            }
            viewModel.setWallpaperUri(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. 外观设置
            PreferenceCategory(title = "外观") {
                // 动态取色
                SwitchSetting(
                    title = "动态取色 (Material You)",
                    description = "根据壁纸自动生成主题色",
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 自定义壁纸
                SettingItem(
                    title = "自定义壁纸",
                    description = if (settings.wallpaperUri != null) "已设置壁纸" else "选择一张图片作为背景"
                ) {
                    Row {
                        Button(onClick = { wallpaperLauncher.launch("image/*") }) {
                            Text(if (settings.wallpaperUri != null) "更换" else "选择图片")
                        }
                        if (settings.wallpaperUri != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { viewModel.setWallpaperUri(null) }) {
                                Text("清除")
                            }
                        }
                    }
                }

                // 只有设置了壁纸才显示遮罩浓度调节
                if (settings.wallpaperUri != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SliderSetting(
                        title = "背景遮罩浓度",
                        value = settings.transparency, // Re-using transparency field for alpha
                        onValueChange = { viewModel.setTransparency(it) },
                        valueRange = 0.1f..0.9f,
                        valueText = "${(settings.transparency * 100).toInt()}%",
                        description = "调节背景图上覆盖颜色的浓度 (建议 40%-60%)"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 字体样式
                SettingItem(title = "字体样式") {
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        AppFontStyle.entries.forEach { style ->
                            val selected = settings.fontStyle == style
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setFontStyle(style) },
                                label = { Text(style.name) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. 课表设置
            PreferenceCategory(title = "课表显示") {
                // 每天总节数
                SliderSetting(
                    title = "每天总节数",
                    value = settings.maxDailySections.toFloat(),
                    onValueChange = { viewModel.setMaxDailySections(it.toInt()) },
                    valueRange = 8f..16f,
                    steps = 7,
                    valueText = "${settings.maxDailySections} 节"
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 默认课程时长
                SliderSetting(
                    title = "默认课程时长",
                    value = settings.defaultCourseDuration.toFloat(),
                    onValueChange = { viewModel.setDefaultCourseDuration(it.toInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                    valueText = "${settings.defaultCourseDuration} 节",
                    description = "新建课程时默认选中的时长"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 网格样式 (高级)
            PreferenceCategory(title = "网格样式 (高级)") {
                // 样式选择
                SettingItem(title = "分割线样式") {
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        DividerType.entries.forEach { type ->
                            val selected = settings.dividerType == type
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setDividerType(type) },
                                label = { 
                                    Text(when(type) {
                                        DividerType.SOLID -> "实线"
                                        DividerType.DASHED -> "虚线"
                                        DividerType.DOTTED -> "点线"
                                    }) 
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 宽度
                SliderSetting(
                    title = "线条宽度",
                    value = settings.dividerWidth.toFloat(),
                    onValueChange = { viewModel.setDividerWidth(it.toInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                    valueText = "${settings.dividerWidth} px"
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 不透明度
                SliderSetting(
                    title = "线条不透明度",
                    value = settings.dividerAlpha,
                    onValueChange = { viewModel.setDividerAlpha(it) },
                    valueRange = 0f..1f,
                    valueText = "${(settings.dividerAlpha * 100).toInt()}%"
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 颜色 (简单文本框，简化处理)
                OutlinedTextField(
                    value = settings.dividerColor,
                    onValueChange = { viewModel.setDividerColor(it) },
                    label = { Text("线条颜色 (Hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// --- Helper Composables ---

@Composable
fun PreferenceCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
fun SettingItem(
    title: String,
    description: String? = null,
    content: @Composable (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (content != null) {
            content()
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String? = null,
    description: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            steps = steps
        )
    }
}
