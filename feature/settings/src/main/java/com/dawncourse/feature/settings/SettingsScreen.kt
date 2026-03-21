package com.dawncourse.feature.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.dawncourse.core.domain.model.WebDavAutoSyncIntervalUnit
import com.dawncourse.core.domain.model.WebDavAutoSyncMode
import com.dawncourse.core.domain.model.WebDavCredentials
import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import com.dawncourse.core.ui.components.OptimizedTimePickerDialog
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.Manifest
import android.os.Build
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.URLUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
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
    // WebDAV 同步状态
    val webDavCredentials by viewModel.webDavCredentials.collectAsState()
    val webDavRemoteInfo by viewModel.webDavRemoteInfo.collectAsState()
    val webDavActionResult by viewModel.webDavActionResult.collectAsState()
    // 本地备份弹窗状态
    val localBackupState by viewModel.localBackupState.collectAsState()
    val context = LocalContext.current
    var maxCourseWeek by remember { mutableIntStateOf(0) }
    var dialogState by remember { mutableStateOf<SettingsDialogState>(SettingsDialogState.None) }
    // WebDAV 底部弹窗显示状态
    var showWebDavSheet by remember { mutableStateOf(false) }
    // 本地备份弹窗显示状态
    var showLocalBackupSheet by remember { mutableStateOf(false) }
    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.setWallpaperUri(it.toString())
        }
    }

    // 导出备份：通过 SAF 创建文件
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportLocalBackup(uri.toString())
        }
    }

    // 导入备份：通过 SAF 选择文件
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.importLocalBackup(uri.toString())
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
                        wallpaperLauncher.launch(arrayOf("image/*"))
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
                onShowDialog = { dialogState = it },
                onOpenWebDav = {
                    showWebDavSheet = true
                    viewModel.refreshWebDavRemoteInfo()
                },
                onOpenLocalBackup = {
                    showLocalBackupSheet = true
                    // 每次打开重置提示与进度，避免旧状态残留
                    viewModel.resetLocalBackupState()
                }
            )
            AboutSection(
                context = context,
                onCheckUpdate = onCheckUpdate
            )

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = remember { packageInfo.versionName }

            AboutBrandFooter(
                versionName = versionName,
                onRepoClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HF-CYGG/DawnCourse"))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                },
                onLicenseClick = {
                     try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HF-CYGG/DawnCourse/blob/main/LICENSE"))
                        context.startActivity(intent)
                     } catch (e: Exception) {
                        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                }
            )
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

    if (showWebDavSheet) {
        WebDavSyncSheet(
            settings = settings,
            credentials = webDavCredentials,
            remoteInfo = webDavRemoteInfo,
            actionResult = webDavActionResult,
            context = context,
            onDismiss = { showWebDavSheet = false },
            onSave = { serverUrl, username, password ->
                viewModel.saveWebDavCredentials(serverUrl, username, password)
            },
            onClear = { viewModel.clearWebDavCredentials() },
            onToggleAutoSync = { viewModel.setEnableWebDavAutoSync(it) },
            onSetAutoSyncMode = { viewModel.setWebDavAutoSyncMode(it) },
            onSetAutoSyncFixedAt = { viewModel.setWebDavAutoSyncFixedAt(it) },
            onSetAutoSyncIntervalValue = { viewModel.setWebDavAutoSyncIntervalValue(it) },
            onSetAutoSyncIntervalUnit = { viewModel.setWebDavAutoSyncIntervalUnit(it) },
            onRefreshRemote = { viewModel.refreshWebDavRemoteInfo() },
            onUpload = { force -> viewModel.uploadWebDavBackup(force) },
            onDownload = { viewModel.downloadWebDavBackup() }
        )
    }

    if (showLocalBackupSheet) {
        LocalBackupSheet(
            state = localBackupState,
            onDismiss = {
                showLocalBackupSheet = false
                viewModel.resetLocalBackupState()
            },
            onExport = {
                // 生成默认文件名，便于用户区分备份时间
                val fileName = "DawnCourse_Backup_${
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                }.json"
                exportBackupLauncher.launch(fileName)
            },
            onImport = {
                importBackupLauncher.launch(arrayOf("application/json"))
            }
        )
    }
}

private sealed class SettingsDialogState {
    data object None : SettingsDialogState()
    data object Semester : SettingsDialogState()
    data object SectionTime : SettingsDialogState()
    data object BatchUpdateDuration : SettingsDialogState()
    data object SyncProvider : SettingsDialogState()
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
            description = "点击修改学期及周次设置",
            icon = { Icon(Icons.Default.DateRange, null) },
            onClick = onOpenSemester,
            showArrow = true,
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
            description = "点击配置具体上下课时间",
            icon = { Icon(Icons.Default.AccessTime, null) },
            onClick = onOpenSectionTime,
            showArrow = true
        )
    }
}

@Composable
private fun AdvancedCourseCardPreview(settings: AppSettings) {
    Text(
        "视觉效果预览",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. 底层：模拟用户壁纸
            if (settings.wallpaperUri != null) {
                AsyncImage(
                    model = settings.wallpaperUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(settings.backgroundBlur.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // 2. 中层：遮罩、亮度
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 1f - settings.backgroundBrightness))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = settings.transparency))
            )

            // 3. 上层：真实的课程卡片
            Box(modifier = Modifier.align(Alignment.Center)) {
                Surface(
                    modifier = Modifier
                        .size(width = 110.dp, height = 130.dp)
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            RoundedCornerShape(settings.cardCornerRadius.dp)
                        ),
                    shape = RoundedCornerShape(settings.cardCornerRadius.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.cardAlpha)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (settings.showCourseIcons) {
                            Icon(Icons.Default.Book, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                        Text("示例课程", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("@图书馆", fontSize = 10.sp, modifier = Modifier.alpha(0.8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onPickWallpaper: () -> Unit
) {
    var showAppearanceAdvanced by remember { mutableStateOf(false) }
    val advancedArrowRotation by animateFloatAsState(
        targetValue = if (showAppearanceAdvanced) 180f else 0f,
        label = "advancedAppearanceArrow"
    )
    PreferenceCategory(title = "外观与视觉") {
        AdvancedCourseCardPreview(settings = settings)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            SettingRow(
                title = "背景与卡片细节",
                description = if (showAppearanceAdvanced) "点击收起" else "点击展开",
                icon = { Icon(Icons.Default.Tune, null) },
                action = {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(advancedArrowRotation)
                    )
                },
                onClick = { showAppearanceAdvanced = !showAppearanceAdvanced },
                showDivider = !showAppearanceAdvanced
            )
            if (showAppearanceAdvanced) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
            }
        }
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
private fun GridLineStylePreview(
    type: DividerType,
    width: Float,
    alpha: Float,
    colorHex: String
) {
    val lineColor = try {
        Color(android.graphics.Color.parseColor(colorHex)).copy(alpha = alpha)
    } catch (e: Exception) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val pathEffect = when (type) {
                DividerType.DASHED -> PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                DividerType.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f, 8f), 0f)
                else -> null
            }
            
            val canvasWidth = size.width
            val canvasHeight = size.height
            val strokeWidthPx = width.dp.toPx()

            // 绘制横线
            drawLine(
                color = lineColor,
                start = Offset(0f, canvasHeight / 2),
                end = Offset(canvasWidth, canvasHeight / 2),
                strokeWidth = strokeWidthPx,
                pathEffect = pathEffect
            )
            // 绘制竖线
            drawLine(
                color = lineColor,
                start = Offset(canvasWidth / 2, 0f),
                end = Offset(canvasWidth / 2, canvasHeight),
                strokeWidth = strokeWidthPx,
                pathEffect = pathEffect
            )
        }
        Text(
            "预览",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .alpha(0.5f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun GridLineSection(
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    PreferenceCategory(title = "网格线设置") {
        GridLineStylePreview(
            type = settings.dividerType,
            width = settings.dividerWidthDp,
            alpha = settings.dividerAlpha,
            colorHex = settings.dividerColor
        )
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
    onShowDialog: (SettingsDialogState) -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenLocalBackup: () -> Unit
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
                    TextButton(onClick = { onShowDialog(SettingsDialogState.BindZf) }) { Text("绑定") }
                }
            },
            showDivider = true
        )
        SettingRow(
            title = "WebDAV 同步",
            description = lastSyncDesc,
            icon = { Icon(Icons.Default.Cloud, null) },
            onClick = onOpenWebDav,
            showDivider = true
        )
        SettingRow(
            title = "备份与还原",
            icon = { Icon(Icons.Default.Restore, null) },
            onClick = onOpenLocalBackup,
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
    val dialogVisible = dialogState != SettingsDialogState.None
    // 弹窗卡片渐入/渐出动画
    AnimatedVisibility(
        visible = dialogVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 140))
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
                    title = { Text("绑定正方教务") },
                    text = {
                        Column {
                            Text(
                                text = "仅支持正方教务账号绑定，绑定后可自动更新课表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // 绑定入口卡片
                            Card(
                                onClick = { onChangeDialog(SettingsDialogState.BindZf) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccountBalance,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "正方教务",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "适用于正方教务系统",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 本地备份与还原弹窗
 *
 * 通过 SAF 完成文件读写，不申请存储权限：
 * - 导出：创建 JSON 文件并写入备份数据
 * - 导入：选择 JSON 文件并覆盖本地数据
 */
private fun LocalBackupSheet(
    state: LocalBackupUiState,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    // 还原操作需要二次确认
    var showImportConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(
                    imageVector = Icons.Default.SettingsBackupRestore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                // 标题区
                Text(
                    text = "备份与还原",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                // 导出模块
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("导出到本地设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "将课表与设置导出为备份文件，方便换机或离线保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onExport,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !state.isProcessing
                        ) {
                            Text("立即导出")
                        }
                    }
                }
                // 还原模块
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("从本地文件恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "选择之前导出的备份文件进行恢复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "注意：此操作将覆盖当前的全部数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { showImportConfirm = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !state.isProcessing
                        ) {
                            Text("选择备份文件")
                        }
                    }
                }
                // 操作结果提示
                AnimatedVisibility(visible = state.message.isNotBlank()) {
                    val messageColor = if (state.success == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = messageColor
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 处理中的遮罩，防止误触
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isProcessing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("确认还原") },
            text = { Text("还原操作将清空当前的全部课程和设置，且不可逆，是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        onImport()
                    }
                ) { Text("继续", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * WebDAV 同步配置弹窗
 *
 * 负责：
 * - 账号的录入、保存与清除
 * - 云端备份状态的展示与刷新
 * - 上传/下载与冲突强制覆盖的交互
 */
private fun WebDavSyncSheet(
    settings: AppSettings,
    credentials: WebDavCredentials?,
    remoteInfo: WebDavSyncResult?,
    actionResult: WebDavSyncResult?,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (serverUrl: String, username: String, password: String) -> Unit,
    onClear: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSetAutoSyncMode: (WebDavAutoSyncMode) -> Unit,
    onSetAutoSyncFixedAt: (Long) -> Unit,
    onSetAutoSyncIntervalValue: (Int) -> Unit,
    onSetAutoSyncIntervalUnit: (WebDavAutoSyncIntervalUnit) -> Unit,
    onRefreshRemote: () -> Unit,
    onUpload: (force: Boolean) -> Unit,
    onDownload: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 账号录入区的输入状态
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // 是否显示账号编辑面板
    var showEditPanel by remember { mutableStateOf(false) }
    // 防止重复弹 Toast 的消息缓存
    var lastActionMessage by remember { mutableStateOf<String?>(null) }
    // 云端存在更新时的强制覆盖对话框
    var showForceUploadDialog by remember { mutableStateOf(false) }
    // 日期/时间选择与展示格式
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val zoneId = remember { ZoneId.systemDefault() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var selectedTime by remember { mutableStateOf(LocalTime.of(8, 0)) }
    // 间隔输入框文本，需与设置同步
    var intervalValueText by remember { mutableStateOf(settings.webDavAutoSyncIntervalValue.toString()) }
    val isConfigured = credentials != null && !showEditPanel

    LaunchedEffect(credentials) {
        // 账号更新时同步到输入框，便于编辑与复用
        if (credentials == null) {
            serverUrl = ""
            username = ""
            password = ""
            showEditPanel = true
        } else {
            serverUrl = credentials.serverUrl
            username = credentials.username
            password = credentials.password
            showEditPanel = false
        }
    }

    LaunchedEffect(actionResult) {
        // 操作结果反馈：弹 Toast，并在需要时提示强制上传
        if (actionResult != null) {
            if (!actionResult.message.isNullOrBlank() && actionResult.message != lastActionMessage) {
                Toast.makeText(context, actionResult.message, Toast.LENGTH_SHORT).show()
                lastActionMessage = actionResult.message
            }
            if (actionResult.requiresForceUpload) {
                showForceUploadDialog = true
            }
        }
    }

    // 当外部更新固定同步时间时，同步到时间选择器默认值
    LaunchedEffect(settings.webDavAutoSyncFixedAt) {
        if (settings.webDavAutoSyncFixedAt > 0L) {
            val localTime = Instant.ofEpochMilli(settings.webDavAutoSyncFixedAt)
                .atZone(zoneId)
                .toLocalTime()
            selectedTime = localTime.withSecond(0).withNano(0)
        }
    }

    LaunchedEffect(settings.webDavAutoSyncIntervalValue) {
        intervalValueText = settings.webDavAutoSyncIntervalValue.toString()
    }

    LaunchedEffect(credentials, showEditPanel) {
        if (credentials != null && !showEditPanel) {
            onRefreshRemote()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "WebDAV 同步",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            AnimatedContent(
                targetState = isConfigured,
                transitionSpec = {
                    (fadeIn() + expandVertically()).togetherWith(fadeOut() + shrinkVertically())
                },
                label = "WebDavState"
            ) { configured ->
                if (!configured) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "配置账号以开启云端备份",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("服务器地址") },
                                placeholder = { Text("https://dav.example.com/dav/") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("账号") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("应用专用密码") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    onSave(serverUrl, username, password)
                                    showEditPanel = false
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                enabled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("连接并保存", fontWeight = FontWeight.Bold)
                            }
                            if (credentials != null) {
                                TextButton(
                                    onClick = onClear,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("清除账号", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "已连接到服务器",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        text = credentials?.username.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = credentials?.serverUrl.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { showEditPanel = true }) {
                                    Text("修改", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "自动同步",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "定期与云端保持一致",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = settings.enableWebDavAutoSync,
                                        onCheckedChange = onToggleAutoSync
                                    )
                                }

                                if (settings.enableWebDavAutoSync) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                    Text(text = "同步方式", style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = settings.webDavAutoSyncMode == WebDavAutoSyncMode.FIXED_TIME,
                                            onClick = { onSetAutoSyncMode(WebDavAutoSyncMode.FIXED_TIME) },
                                            label = { Text("固定日期") }
                                        )
                                        FilterChip(
                                            selected = settings.webDavAutoSyncMode == WebDavAutoSyncMode.INTERVAL,
                                            onClick = { onSetAutoSyncMode(WebDavAutoSyncMode.INTERVAL) },
                                            label = { Text("间隔同步") }
                                        )
                                    }
                                    if (settings.webDavAutoSyncMode == WebDavAutoSyncMode.FIXED_TIME) {
                                        val fixedAtText = if (settings.webDavAutoSyncFixedAt > 0L) {
                                            dateFormatter.format(Date(settings.webDavAutoSyncFixedAt))
                                        } else {
                                            "未设置"
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = "同步时间", style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    text = fixedAtText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            OutlinedButton(onClick = { showDatePicker = true }) {
                                                Text("选择日期")
                                            }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = intervalValueText,
                                            onValueChange = { value ->
                                                if (value.all { it.isDigit() } && value.length <= 4) {
                                                    intervalValueText = value
                                                    val parsed = value.toIntOrNull()
                                                    if (parsed != null && parsed > 0) {
                                                        onSetAutoSyncIntervalValue(parsed)
                                                    }
                                                }
                                            },
                                            label = { Text("间隔数值") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = settings.webDavAutoSyncIntervalUnit == WebDavAutoSyncIntervalUnit.MINUTES,
                                                onClick = { onSetAutoSyncIntervalUnit(WebDavAutoSyncIntervalUnit.MINUTES) },
                                                label = { Text("分钟") }
                                            )
                                            FilterChip(
                                                selected = settings.webDavAutoSyncIntervalUnit == WebDavAutoSyncIntervalUnit.HOURS,
                                                onClick = { onSetAutoSyncIntervalUnit(WebDavAutoSyncIntervalUnit.HOURS) },
                                                label = { Text("小时") }
                                            )
                                            FilterChip(
                                                selected = settings.webDavAutoSyncIntervalUnit == WebDavAutoSyncIntervalUnit.DAYS,
                                                onClick = { onSetAutoSyncIntervalUnit(WebDavAutoSyncIntervalUnit.DAYS) },
                                                label = { Text("天") }
                                            )
                                        }
                                    }
                                }

                                val remoteTimestamp = remoteInfo?.remoteLastModified
                                val remoteDesc = when {
                                    remoteInfo == null -> "正在检查云端状态"
                                    remoteInfo.success.not() -> remoteInfo.message
                                    remoteTimestamp == null -> "云端无备份"
                                    else -> "云端备份：${dateFormatter.format(Date(remoteTimestamp))}"
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = remoteDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (remoteInfo != null && !remoteInfo.success) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { onUpload(false) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("备份数据")
                                    }
                                    OutlinedButton(
                                        onClick = onDownload,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("恢复数据")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 固定日期选择：先选日期，再选时间，组合为最终时间戳
    if (showDatePicker) {
        val initialDate = if (settings.webDavAutoSyncFixedAt > 0L) {
            Instant.ofEpochMilli(settings.webDavAutoSyncFixedAt).atZone(zoneId).toLocalDate()
        } else {
            LocalDate.now()
        }
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        DawnDatePickerDialog(
            state = dateState,
            onDismissRequest = { showDatePicker = false },
            onConfirm = {
                pendingDateMillis = dateState.selectedDateMillis
                showDatePicker = false
                showTimePicker = true
            },
            title = "选择同步日期"
        )
    }

    if (showTimePicker) {
        val initialTime = if (settings.webDavAutoSyncFixedAt > 0L) {
            Instant.ofEpochMilli(settings.webDavAutoSyncFixedAt).atZone(zoneId).toLocalTime()
        } else {
            selectedTime
        }
        OptimizedTimePickerDialog(
            initialTime = initialTime,
            onDismiss = { showTimePicker = false },
            onConfirm = { time ->
                // 以本地时区组装完整时间戳，并切换为固定日期模式
                selectedTime = time
                val dateMillis = pendingDateMillis ?: Instant.now().toEpochMilli()
                val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.of("UTC")).toLocalDate()
                val timestamp = date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
                onSetAutoSyncMode(WebDavAutoSyncMode.FIXED_TIME)
                onSetAutoSyncFixedAt(timestamp)
                showTimePicker = false
            }
        )
    }

    if (showForceUploadDialog) {
        AlertDialog(
            onDismissRequest = { showForceUploadDialog = false },
            title = { Text("云端有更新") },
            text = { Text("云端备份更新时间晚于本地记录，是否强制覆盖上传？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceUploadDialog = false
                        onUpload(true)
                    }
                ) { Text("强制上传") }
            },
            dismissButton = {
                TextButton(onClick = { showForceUploadDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 绑定教务账号对话框
 *
 * 负责收集入口地址、学号与密码，并在提交时进行正方入口校验。
 *
 * @param title 对话框标题
 * @param onDismiss 关闭回调
 * @param onConfirm 校验通过后的提交回调
 */
@Composable
private fun BindAccountDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (endpoint: String, username: String, password: String) -> Unit
) {
    // 教务入口地址
    var endpoint by remember { mutableStateOf("") }
    // 学号（账号）
    var username by remember { mutableStateOf("") }
    // 密码
    var password by remember { mutableStateOf("") }
    // 入口校验错误提示
    var endpointError by remember { mutableStateOf<String?>(null) }
    // 基础必填校验
    val isValid = endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 入口地址输入
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        // 当地址被清空或通过正方识别时，自动清理错误提示
                        if (endpointError != null && (it.isBlank() || isZfEndpoint(it))) {
                            endpointError = null
                        }
                    },
                    label = { Text("教务地址") },
                    placeholder = { Text("例如: http://jwgl.xxx.edu.cn") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    // 存在错误时展示红色错误态
                    isError = endpointError != null,
                    // 入口错误提示
                    supportingText = {
                        if (endpointError != null) {
                            Text(endpointError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 学号输入
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("学号") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 密码输入
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 先校验入口是否为正方教务系统
                    if (!isZfEndpoint(endpoint)) {
                        endpointError = "暂时只支持正方教务系统，请填写正方教务入口地址"
                        return@TextButton
                    }
                    // 校验通过后提交
                    onConfirm(endpoint, username, password)
                },
                enabled = isValid
            ) { Text("绑定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 判断入口地址是否符合正方教务系统特征
 *
 * 规则：
 * - 允许用户省略 scheme（自动补全为 https）
 * - 必须是合法的网络 URL
 * - host/path 中包含 “jwgl” 关键字（覆盖常见正方入口）
 */
private fun isZfEndpoint(raw: String): Boolean {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return false
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val candidate = if (URLUtil.isNetworkUrl(withScheme)) withScheme else URLUtil.guessUrl(withScheme)
    if (!URLUtil.isNetworkUrl(candidate)) return false
    return runCatching {
        val uri = Uri.parse(candidate)
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        (host + path).lowercase().contains("jwgl")
    }.getOrDefault(false)
}



@Composable
private fun AboutBrandFooter(
    versionName: String,
    onRepoClick: () -> Unit,
    onLicenseClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 32.dp), // 增加顶部间距，与上方设置卡片拉开距离
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. App Icon 占位 (使用 Surface 模拟圆角图标感)
        Surface(
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(16.dp), // Squircle 风格圆角
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (appIcon != null) {
                    AsyncImage(
                        model = appIcon,
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome, // "Dawn" 黎明的意向
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 2. 应用名称与版本
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Dawn Course",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        // 3. 核心宗旨清单 (点缀式排版)
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val footerNoteStyle = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text("永久免费", style = footerNoteStyle)
            Text(" · ", style = footerNoteStyle)
            Text("开源协议", style = footerNoteStyle)
            Text(" · ", style = footerNoteStyle)
            Text("离线优先", style = footerNoteStyle)
        }

        // 4. 交互式链接 (带图标的 TextButton)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onRepoClick) {
                Icon(
                    imageVector = Icons.Default.Code, // 或者使用自定义的 GitHub Icon
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("源码仓库")
            }

            // 装饰性的垂直分隔线
            VerticalDivider(
                modifier = Modifier
                    .height(16.dp)
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            TextButton(onClick = onLicenseClick) {
                Icon(
                    imageVector = Icons.Default.Gavel,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("GPL-3.0")
            }
        }
    }
}

