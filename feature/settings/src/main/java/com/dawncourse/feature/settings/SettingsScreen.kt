package com.dawncourse.feature.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.domain.model.WallpaperMode
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.DividerType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.Manifest
import android.os.Build
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.provider.Settings

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border

/**
 * 设置主页面
 *
 * 包含应用的所有配置项入口，如：
 * - 课表核心管理 (学期、时间表)
 * - 视觉定制 (主题、壁纸、卡片样式)
 * - 功能选项 (自动静音、导入导出等)
 *
 * @param onBackClick 返回回调
 * @param onNavigateToTimetableSettings 跳转到课表详细设置的回调
 * @param viewModel [SettingsViewModel] 实例
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onCheckUpdate: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val boundProvider by viewModel.boundProvider.collectAsState()
    val lastSyncDesc by viewModel.lastSyncDescription.collectAsState()
    val context = LocalContext.current
    var maxCourseWeek by remember { mutableIntStateOf(0) }
    var dialogState by remember { mutableStateOf<SettingsDialogState>(SettingsDialogState.None) }
    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore
            }
            viewModel.setWallpaperUri(it.toString())
        }
    }

    // Notification Permission Handling
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingNotificationAction?.invoke()
        } else {
            Toast.makeText(context, "开启通知失败：请授予通知权限", Toast.LENGTH_SHORT).show()
        }
        pendingNotificationAction = null
    }

    fun checkAndRequestNotificationPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                onGranted()
            } else {
                pendingNotificationAction = onGranted
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            onGranted()
        }
    }
    val openSemesterDialog = {
        viewModel.getMaxCourseWeek { max ->
            maxCourseWeek = max
            dialogState = SettingsDialogState.Semester
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            TimetableSection(
                settings = settings,
                viewModel = viewModel,
                onOpenSemester = openSemesterDialog,
                onOpenSectionTime = { dialogState = SettingsDialogState.SectionTime },
                onOpenBatchUpdate = { dialogState = SettingsDialogState.BatchUpdateDuration }
            )
            AppearanceSection(
                settings = settings,
                viewModel = viewModel,
                onPickWallpaper = {
                    try {
                        wallpaperLauncher.launch("image/*")
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            LayoutSection(settings = settings, viewModel = viewModel)
            GridLineSection(settings = settings, viewModel = viewModel)
            NotificationSection(
                settings = settings,
                context = context,
                viewModel = viewModel,
                onRequestNotificationPermission = { onGranted ->
                    checkAndRequestNotificationPermission(onGranted)
                },
                onRequestAutoMutePermission = { dialogState = SettingsDialogState.Permission }
            )
            DataSyncSection(
                boundProvider = boundProvider,
                lastSyncDesc = lastSyncDesc,
                context = context,
                onShowDialog = { dialogState = it }
            )
            AboutSection(
                context = context,
                onCheckUpdate = onCheckUpdate
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    SettingsDialogManager(
        dialogState = dialogState,
        settings = settings,
        viewModel = viewModel,
        maxCourseWeek = maxCourseWeek,
        context = context,
        onDismiss = { dialogState = SettingsDialogState.None },
        onChangeDialog = { dialogState = it }
    )
}

private sealed class SettingsDialogState {
    data object None : SettingsDialogState()
    data object Semester : SettingsDialogState()
    data object SectionTime : SettingsDialogState()
    data object BatchUpdateDuration : SettingsDialogState()
    data object SyncProvider : SettingsDialogState()
    data object BindQidi : SettingsDialogState()
    data object BindZf : SettingsDialogState()
    data object ClearAllData : SettingsDialogState()
    data object Permission : SettingsDialogState()
    data class UnbindConfirm(val provider: SyncProviderType) : SettingsDialogState()
}

@Composable
private fun TimetableSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onOpenSemester: () -> Unit,
    onOpenSectionTime: () -> Unit,
    onOpenBatchUpdate: () -> Unit
) {
    PreferenceCategory(title = "课表管理") {
        SettingRow(
            title = "当前学期",
            description = "${settings.currentSemesterName} (第 1 周 / 共 ${settings.totalWeeks} 周)",
            icon = { Icon(Icons.Default.DateRange, null) },
            onClick = onOpenSemester,
            showDivider = true
        )
        SliderSetting(
            title = "每天总节数",
            value = settings.maxDailySections.toFloat(),
            onValueChange = { viewModel.setMaxDailySections(it.toInt()) },
            valueRange = 8f..16f,
            steps = 7,
            valueText = "${settings.maxDailySections} 节",
            showDivider = true
        )
        SliderSetting(
            title = "默认课程时长",
            value = settings.defaultCourseDuration.toFloat(),
            onValueChange = { viewModel.setDefaultCourseDuration(it.toInt()) },
            valueRange = 1f..4f,
            steps = 2,
            valueText = "${settings.defaultCourseDuration} 节",
            showDivider = true
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onOpenBatchUpdate) {
                Text("应用到所有课程")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingRow(
            title = "节次时间设置",
            description = "调整每一节课的上课与下课时间",
            icon = { Icon(Icons.Default.AccessTime, null) },
            onClick = onOpenSectionTime
        )
    }
}

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onPickWallpaper: () -> Unit
) {
    PreferenceCategory(title = "外观与视觉") {
        CourseCardPreview(settings = settings)
        Spacer(modifier = Modifier.height(16.dp))
        SettingRow(
            title = "主题模式",
            icon = { Icon(Icons.Default.BrightnessMedium, null) }
        ) {
            Row(modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    AppThemeMode.SYSTEM -> "跟随系统"
                                    AppThemeMode.LIGHT -> "浅色"
                                    AppThemeMode.DARK -> "深色"
                                }
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp))
        SwitchSetting(
            title = "动态取色 (Material You)",
            description = "根据壁纸自动生成主题色",
            icon = { Icon(Icons.Default.Palette, null) },
            checked = settings.dynamicColor,
            onCheckedChange = { viewModel.setDynamicColor(it) },
            showDivider = true
        )
        SettingRow(
            title = "自定义壁纸",
            description = if (settings.wallpaperUri != null) "已设置自定义壁纸" else "未设置",
            icon = { Icon(Icons.Default.Wallpaper, null) },
            action = {
                Row {
                    if (settings.wallpaperUri != null) {
                        TextButton(onClick = { viewModel.setWallpaperUri(null) }) { Text("清除") }
                    }
                    Button(onClick = onPickWallpaper) {
                        Text("选择")
                    }
                }
            },
            showDivider = true
        )
        if (settings.wallpaperUri != null) {
            Row(modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                WallpaperMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.wallpaperMode == mode,
                        onClick = { viewModel.setWallpaperMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    WallpaperMode.CROP -> "裁剪适应"
                                    WallpaperMode.FILL -> "拉伸填充"
                                }
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
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
        SliderSetting(
            title = "课程卡片高度",
            value = settings.courseItemHeightDp.toFloat(),
            onValueChange = { viewModel.setCourseItemHeight(it.toInt()) },
            valueRange = 40f..100f,
            steps = 12,
            valueText = "${settings.courseItemHeightDp} dp",
            showDivider = true
        )
        SliderSetting(
            title = "课程卡片圆角",
            value = settings.cardCornerRadius.toFloat(),
            onValueChange = { viewModel.setCardCornerRadius(it.toInt()) },
            valueRange = 0f..24f,
            steps = 24,
            valueText = "${settings.cardCornerRadius} dp",
            showDivider = true
        )
        SmoothSliderSetting(
            title = "课程卡片透明度",
            value = settings.cardAlpha,
            onValueChangeFinished = { viewModel.setCardAlpha(it) },
            valueRange = 0.1f..1f,
            valueText = { "${(it * 100).toInt()}%" },
            description = "调节课程卡片的不透明度",
            showDivider = true
        )
        SwitchSetting(
            title = "显示课程图标",
            description = "在课程卡片上显示课程图标",
            icon = { Icon(Icons.Default.Image, null) },
            checked = settings.showCourseIcons,
            onCheckedChange = { viewModel.setShowCourseIcons(it) }
        )
    }
}

@Composable
private fun LayoutSection(
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    PreferenceCategory(title = "显示与布局") {
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
            title = "显示日期",
            description = "在星期下方显示具体日期 (如 9.1)",
            icon = { Icon(Icons.Default.CalendarToday, null) },
            checked = settings.showDateInHeader,
            onCheckedChange = { viewModel.setShowDateInHeader(it) },
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
}

@Composable
private fun GridLineSection(
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    PreferenceCategory(title = "网格线设置") {
        SettingRow(title = "线样式") {
            Row(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                DividerType.entries.forEach { type ->
                    val selected = settings.dividerType == type
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setDividerType(type) },
                        label = {
                            Text(
                                when (type) {
                                    DividerType.SOLID -> "实线"
                                    DividerType.DASHED -> "虚线"
                                    DividerType.DOTTED -> "点线"
                                }
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SliderSetting(
            title = "线宽",
            value = settings.dividerWidthDp,
            onValueChange = { viewModel.setDividerWidth(it) },
            valueRange = 0.5f..5f,
            steps = 9,
            valueText = "${String.format("%.1f", settings.dividerWidthDp)} dp",
            showDivider = true
        )
        SliderSetting(
            title = "不透明度",
            value = settings.dividerAlpha,
            onValueChange = { viewModel.setDividerAlpha(it) },
            valueRange = 0f..1f,
            steps = 10,
            valueText = "${(settings.dividerAlpha * 100).toInt()}%",
            showDivider = true
        )
        var showColorPicker by remember { mutableStateOf(false) }
        val currentColor = try {
            Color(android.graphics.Color.parseColor(settings.dividerColor))
        } catch (e: Exception) {
            MaterialTheme.colorScheme.outlineVariant
        }
        SettingRow(
            title = "网格线颜色",
            showDivider = false,
            action = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(currentColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                )
            },
            onClick = { showColorPicker = true }
        )
        if (showColorPicker) {
            ColorPickerDialog(
                initialColor = currentColor,
                onDismiss = { showColorPicker = false },
                onConfirm = { color ->
                    val hexColor = String.format("#%08X", color.toArgb())
                    viewModel.setDividerColor(hexColor)
                    showColorPicker = false
                }
            )
        }
    }
}

@Composable
private fun NotificationSection(
    settings: AppSettings,
    context: Context,
    viewModel: SettingsViewModel,
    onRequestNotificationPermission: ((() -> Unit) -> Unit),
    onRequestAutoMutePermission: () -> Unit
) {
    PreferenceCategory(title = "通知与提醒") {
        SwitchSetting(
            title = "上课提醒",
            description = "上课前 ${settings.reminderMinutes} 分钟发送通知",
            icon = { Icon(Icons.Default.Notifications, null) },
            checked = settings.enableClassReminder,
            onCheckedChange = {
                if (it) {
                    onRequestNotificationPermission { viewModel.setEnableClassReminder(true) }
                } else {
                    viewModel.setEnableClassReminder(false)
                }
            },
            showDivider = settings.enableClassReminder
        )
        if (settings.enableClassReminder) {
            SliderSetting(
                title = "提前提醒时间",
                value = settings.reminderMinutes.toFloat(),
                onValueChange = { viewModel.setReminderMinutes(it.toInt()) },
                valueRange = 5f..60f,
                steps = 11,
                valueText = "${settings.reminderMinutes} 分钟",
                showDivider = true
            )
        }
        SwitchSetting(
            title = "自动静音",
            description = "上课期间自动开启免打扰模式",
            icon = { Icon(Icons.Default.DoNotDisturb, null) },
            checked = settings.enableAutoMute,
            onCheckedChange = {
                if (it) {
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                        onRequestAutoMutePermission()
                    } else {
                        viewModel.setEnableAutoMute(true)
                    }
                } else {
                    viewModel.setEnableAutoMute(false)
                }
            }
        )
    }
}

@Composable
private fun DataSyncSection(
    boundProvider: SyncProviderType?,
    lastSyncDesc: String,
    context: Context,
    onShowDialog: (SettingsDialogState) -> Unit
) {
    PreferenceCategory(title = "数据与同步") {
        SettingRow(
            title = "账号绑定（测试）",
            description = if (boundProvider != null) {
                "已绑定 ${boundProvider.name}"
            } else {
                "未绑定"
            },
            icon = { Icon(Icons.Default.CloudSync, null) },
            action = {
                if (boundProvider != null) {
                    TextButton(onClick = { onShowDialog(SettingsDialogState.UnbindConfirm(boundProvider)) }) {
                        Text("解绑")
                    }
                } else {
                    TextButton(onClick = { onShowDialog(SettingsDialogState.SyncProvider) }) { Text("绑定") }
                }
            },
            showDivider = true
        )
        SettingRow(
            title = "WebDAV 同步",
            description = lastSyncDesc,
            icon = { Icon(Icons.Default.Cloud, null) },
            onClick = { Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show() },
            showDivider = true
        )
        SettingRow(
            title = "备份与还原",
            icon = { Icon(Icons.Default.Restore, null) },
            onClick = { Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show() },
            showDivider = true
        )
        SettingRow(
            title = "清空所有数据",
            icon = { Icon(Icons.Default.DeleteForever, null) },
            onClick = { onShowDialog(SettingsDialogState.ClearAllData) }
        )
    }
}

@Composable
private fun AboutSection(
    context: Context,
    onCheckUpdate: () -> Unit
) {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = remember { packageInfo.versionName }
    PreferenceCategory(title = "关于") {
        UpdateSettingItem(
            currentVersion = versionName,
            onCheckUpdate = onCheckUpdate
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingRow(
            title = "开源许可",
            onClick = { }
        )
    }
}

@Composable
private fun SettingsDialogManager(
    dialogState: SettingsDialogState,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    maxCourseWeek: Int,
    context: Context,
    onDismiss: () -> Unit,
    onChangeDialog: (SettingsDialogState) -> Unit
) {
    when (dialogState) {
        SettingsDialogState.None -> Unit
        SettingsDialogState.SectionTime -> {
            SectionTimeSettingsDialog(
                settings = settings,
                viewModel = viewModel,
                onDismissRequest = onDismiss
            )
        }
        SettingsDialogState.Semester -> {
            SemesterSettingsDialog(
                initialName = settings.currentSemesterName,
                initialWeeks = settings.totalWeeks,
                initialStartDate = settings.startDateTimestamp,
                maxCourseWeek = maxCourseWeek,
                onDismissRequest = onDismiss,
                onConfirm = { name, weeks, date ->
                    viewModel.setCurrentSemesterName(name)
                    viewModel.setTotalWeeks(weeks)
                    viewModel.setStartDateTimestamp(date)
                    onDismiss()
                }
            )
        }
        SettingsDialogState.BatchUpdateDuration -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("批量更新课程时长") },
                text = { Text("确定要将所有现有课程的时长都修改为 ${settings.defaultCourseDuration} 节吗？\n\n此操作将覆盖所有课程的当前时长，且不可撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.updateAllCoursesDuration(settings.defaultCourseDuration)
                            onDismiss()
                        }
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
        SettingsDialogState.SyncProvider -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("选择绑定方式") },
                text = {
                    Column {
                        Text(
                            text = "请选择教务系统进行账号绑定，绑定后可一键同步课程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            onClick = { onChangeDialog(SettingsDialogState.BindQidi) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            ListItem(
                                leadingContent = { Icon(Icons.Default.School, null) },
                                headlineContent = { Text("起迪教务") },
                                supportingContent = { Text("适用于起迪教务系统账号登录") },
                                modifier = Modifier.clickable { onChangeDialog(SettingsDialogState.BindQidi) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            onClick = { onChangeDialog(SettingsDialogState.BindZf) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            ListItem(
                                leadingContent = { Icon(Icons.Default.AccountBalance, null) },
                                headlineContent = { Text("正方教务") },
                                supportingContent = { Text("适用于正方教务系统账号登录") },
                                modifier = Modifier.clickable { onChangeDialog(SettingsDialogState.BindZf) }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
        SettingsDialogState.BindQidi -> {
            BindAccountDialog(
                title = "绑定起迪教务",
                onDismiss = onDismiss,
                onConfirm = { endpoint, username, password ->
                    viewModel.bindQidiCredentials(endpoint = endpoint, username = username, password = password)
                    onDismiss()
                }
            )
        }
        SettingsDialogState.BindZf -> {
            BindAccountDialog(
                title = "绑定正方教务",
                onDismiss = onDismiss,
                onConfirm = { endpoint, username, password ->
                    viewModel.bindZfCredentials(endpoint = endpoint, username = username, password = password)
                    onDismiss()
                }
            )
        }
        is SettingsDialogState.UnbindConfirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("确认解绑") },
                text = { Text("确定要解除与 ${dialogState.provider.name} 的绑定吗？\n解绑后将无法自动同步课程数据。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearSyncCredentials()
                        onDismiss()
                        Toast.makeText(context, "已解绑", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("确定解绑", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
        SettingsDialogState.ClearAllData -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("确认清空") },
                text = { Text("确定要清空所有本地数据吗？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearAllData()
                        onDismiss()
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
        SettingsDialogState.Permission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("需要权限") },
                text = { Text("开启自动静音需要授予“勿扰权限”，以便在上课时自动切换静音模式。") },
                confirmButton = {
                    TextButton(onClick = {
                        onDismiss()
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("去授权")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun BindAccountDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (endpoint: String, username: String, password: String) -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val isValid = endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("教务地址") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("学号") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(endpoint, username, password) },
                enabled = isValid
            ) { Text("绑定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun CourseCardPreview(settings: AppSettings) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "效果预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            // Preview Card
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = settings.courseItemHeightDp.dp)
                    .clip(RoundedCornerShape(settings.cardCornerRadius.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.cardAlpha))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(settings.cardCornerRadius.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(4.dp)
                ) {
                    if (settings.showCourseIcons) {
                        Icon(
                            imageVector = Icons.Default.StickyNote2, // Using StickyNote2 as generic course icon
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "高等数学",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "@教1-101",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

