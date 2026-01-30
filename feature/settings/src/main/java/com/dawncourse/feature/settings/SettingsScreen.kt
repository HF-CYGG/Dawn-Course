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
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.domain.model.WallpaperMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToTimetableSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var showSemesterDialog by remember { mutableStateOf(false) }

    if (showSemesterDialog) {
        SemesterSettingsDialog(
            initialName = settings.currentSemesterName,
            initialWeeks = settings.totalWeeks,
            initialStartDate = settings.startDateTimestamp,
            onDismissRequest = { showSemesterDialog = false },
            onConfirm = { name, weeks, date ->
                viewModel.setCurrentSemesterName(name)
                viewModel.setTotalWeeks(weeks)
                viewModel.setStartDateTimestamp(date)
                showSemesterDialog = false
            }
        )
    }

    var showPermissionDialog by remember { mutableStateOf(false) }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要权限") },
            text = { Text("开启自动静音需要授予“勿扰权限”，以便在上课时自动切换静音模式。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "无法打开设置页面", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("去授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

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
                title = { Text("设置") },
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
            // 1. 课表核心管理 (Schedule Core)
            PreferenceCategory(title = "课表核心管理") {
                SettingRow(
                    title = "当前学期",
                    description = "${settings.currentSemesterName} (第 1 周 / 共 ${settings.totalWeeks} 周)",
                    icon = { Icon(Icons.Default.DateRange, null) },
                    onClick = { showSemesterDialog = true },
                    showDivider = true
                )
                SettingRow(
                    title = "课表布局与网格",
                    description = "调整每天节数、每节课时间及网格样式",
                    icon = { Icon(Icons.Default.AccessTime, null) },
                    onClick = onNavigateToTimetableSettings
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. 深度视觉定制 (Visual Customization)
            PreferenceCategory(title = "深度视觉定制") {
                // Preview
                CourseCardPreview(settings = settings)
                
                Spacer(modifier = Modifier.height(16.dp))

                // 主题模式
                SettingRow(
                    title = "主题模式",
                    icon = { Icon(Icons.Default.BrightnessMedium, null) }
                ) {
                     Row(modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(when(mode) {
                                    AppThemeMode.SYSTEM -> "跟随系统"
                                    AppThemeMode.LIGHT -> "浅色"
                                    AppThemeMode.DARK -> "深色"
                                }) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp))

                // 动态取色
                SwitchSetting(
                    title = "动态取色 (Material You)",
                    description = "根据壁纸自动生成主题色",
                    icon = { Icon(Icons.Default.Palette, null) },
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) },
                    showDivider = true
                )

                // 壁纸选择
                SettingRow(
                    title = "自定义壁纸",
                    description = if (settings.wallpaperUri != null) "已设置自定义壁纸" else "未设置",
                    icon = { Icon(Icons.Default.Wallpaper, null) },
                    action = {
                        Row {
                            if (settings.wallpaperUri != null) {
                                TextButton(onClick = { viewModel.setWallpaperUri(null) }) { Text("清除") }
                            }
                            Button(onClick = { 
                                try {
                                    wallpaperLauncher.launch("image/*")
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法打开图片选择器", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("选择")
                            }
                        }
                    },
                    showDivider = true
                )

                if (settings.wallpaperUri != null) {
                    // 壁纸模式
                    Row(modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                        WallpaperMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.wallpaperMode == mode,
                                onClick = { viewModel.setWallpaperMode(mode) },
                                label = { Text(when(mode) {
                                    WallpaperMode.CROP -> "裁剪适应"
                                    WallpaperMode.FILL -> "拉伸填充"
                                }) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                SmoothSliderSetting(
                    title = "背景遮罩浓度",
                    value = settings.transparency,
                    onValueChangeFinished = { viewModel.setTransparency(it) },
                    valueRange = 0f..1f,
                    valueText = { "${(it * 100).toInt()}%" },
                    description = "调节背景图上覆盖颜色的浓度",
                    showDivider = true
                )

                SmoothSliderSetting(
                    title = "背景模糊程度",
                    value = settings.backgroundBlur,
                    onValueChangeFinished = { viewModel.setBackgroundBlur(it) },
                    valueRange = 0f..100f,
                    valueText = { "${it.toInt()} dp" },
                    description = "调节背景图的模糊程度",
                    showDivider = true
                )

                SmoothSliderSetting(
                    title = "背景亮度",
                    value = settings.backgroundBrightness,
                    onValueChangeFinished = { viewModel.setBackgroundBrightness(it) },
                    valueRange = 0f..1f,
                    valueText = { "${(it * 100).toInt()}%" },
                    description = "调节背景图的亮度",
                    showDivider = true
                )
            }
        }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 显示逻辑调整 (Display Logic)
            PreferenceCategory(title = "显示逻辑调整") {
                SwitchSetting(
                    title = "显示周末",
                    description = "隐藏周六周日，增加平日课程卡片宽度",
                    icon = { Icon(Icons.Default.ViewWeek, null) },
                    checked = settings.showWeekend,
                    onCheckedChange = { viewModel.setShowWeekend(it) },
                    showDivider = true
                )
                
                SwitchSetting(
                    title = "显示侧边栏时间",
                    icon = { Icon(Icons.Default.Schedule, null) },
                    checked = settings.showSidebarTime,
                    onCheckedChange = { viewModel.setShowSidebarTime(it) },
                    showDivider = true
                )

                SwitchSetting(
                    title = "显示侧边栏节数",
                    icon = { Icon(Icons.Default.FormatListNumbered, null) },
                    checked = settings.showSidebarIndex,
                    onCheckedChange = { viewModel.setShowSidebarIndex(it) },
                    showDivider = true
                )
                
                SwitchSetting(
                    title = "隐藏非本周课程",
                    description = "仅显示当前周有课的课程",
                    icon = { Icon(Icons.Default.VisibilityOff, null) },
                    checked = settings.hideNonThisWeek,
                    onCheckedChange = { viewModel.setHideNonThisWeek(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. 通知与提醒 (Notifications)
            PreferenceCategory(title = "通知与提醒") {
                SwitchSetting(
                    title = "上课提醒",
                    description = "上课前 ${settings.reminderMinutes} 分钟发送通知",
                    icon = { Icon(Icons.Default.Notifications, null) },
                    checked = settings.enableClassReminder,
                    onCheckedChange = { viewModel.setEnableClassReminder(it) },
                    showDivider = settings.enableClassReminder
                )
                
                if (settings.enableClassReminder) {
                    SliderSetting(
                        title = "提前提醒时间",
                        value = settings.reminderMinutes.toFloat(),
                        onValueChange = { viewModel.setReminderMinutes(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 10, // 5, 10, 15, ..., 60
                        valueText = "${settings.reminderMinutes} 分钟",
                        showDivider = true
                    )
                }
                
                SwitchSetting(
                    title = "常驻通知栏",
                    description = "在通知栏显示下一节课信息",
                    icon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, null) },
                    checked = settings.enablePersistentNotification,
                    onCheckedChange = { viewModel.setEnablePersistentNotification(it) },
                    showDivider = true
                )
                SwitchSetting(
                    title = "课后自动静音",
                    description = "上课期间自动将手机设为静音/震动",
                    icon = { Icon(Icons.Default.DoNotDisturb, null) },
                    checked = settings.enableAutoMute,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                                showPermissionDialog = true
                            } else {
                                viewModel.setEnableAutoMute(true)
                            }
                        } else {
                            viewModel.setEnableAutoMute(false)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 5. 数据与同步 (Data & Backup)
            PreferenceCategory(title = "数据与同步") {
                SettingRow(
                    title = "WebDAV 同步",
                    description = "同步到坚果云/Nextcloud",
                    icon = { Icon(Icons.Default.CloudSync, null) },
                    onClick = { 
                        android.widget.Toast.makeText(context, "WebDAV 同步功能开发中", android.widget.Toast.LENGTH_SHORT).show() 
                    },
                    showDivider = true
                )
                SettingRow(
                    title = "备份与还原",
                    description = "导出 .json 备份文件",
                    icon = { Icon(Icons.Default.Save, null) },
                    onClick = { 
                        android.widget.Toast.makeText(context, "备份还原功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    showDivider = true
                )
                SettingRow(
                    title = "清空所有数据",
                    icon = { Icon(Icons.Default.DeleteForever, null) },
                    onClick = { 
                         android.widget.Toast.makeText(context, "清空数据功能开发中", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CourseCardPreview(
    settings: AppSettings
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Wallpaper Background
        if (settings.wallpaperUri != null) {
            AsyncImage(
                model = settings.wallpaperUri,
                contentDescription = null,
                contentScale = if (settings.wallpaperMode == WallpaperMode.CROP) ContentScale.Crop else ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            // Overlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = settings.transparency))
            )
        } else {
            // Default colorful background to show transparency
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    )
            )
        }

        // Mock Grid Lines
        Column(modifier = Modifier.fillMaxSize()) {
            val dividerColor = try {
                Color(android.graphics.Color.parseColor(settings.dividerColor))
            } catch (e: Exception) { Color.LightGray }
            
            repeat(3) {
                HorizontalDivider(
                    color = dividerColor.copy(alpha = settings.dividerAlpha),
                    thickness = settings.dividerWidthDp.dp,
                    modifier = Modifier.padding(top = 40.dp)
                )
            }
        }

        // Mock Course Card
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier
                    .size(width = 100.dp, height = 100.dp),
                shape = RoundedCornerShape(settings.cardCornerRadius.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.cardAlpha)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (settings.showCourseIcons) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "高等数学",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "@3-205",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        Text(
            text = "效果预览",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
