package com.dawncourse.app

import android.content.Intent
import android.app.NotificationManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.ui.theme.DawnTheme
import com.dawncourse.feature.import_module.ImportScreen
import com.dawncourse.feature.settings.SettingsScreen
import com.dawncourse.feature.settings.ProfileManagementScreen
import com.dawncourse.feature.import_module.QidiAutoSyncScreen
import com.dawncourse.feature.timetable.TimetableRoute
import android.net.Uri
import android.widget.Toast
import com.dawncourse.feature.update.UpdateDialog
import com.dawncourse.feature.update.UpdateErrorDialog
import com.dawncourse.feature.update.UpdateUiState
import com.dawncourse.feature.update.UpdateViewModel
import com.dawncourse.feature.update.isValidUpdateDownloadUrl
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.lifecycle.ViewModelProvider
import com.dawncourse.feature.timetable.CourseEditorScreen
import com.dawncourse.feature.timetable.CourseEditorViewModel
import com.dawncourse.feature.timetable.notification.ReminderScheduler
import com.dawncourse.app.sync.WebDavAutoSyncScheduler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import com.dawncourse.feature.widget.worker.WidgetSyncManager
import com.dawncourse.app.crash.CrashReportDialog
import com.dawncourse.app.crash.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.data.repository.StartupSnapshotRuntime
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState
import com.dawncourse.feature.timetable.StartupTimetableContent
import com.dawncourse.feature.timetable.toAppSettings

/**
 * 应用程序主 Activity
 *
 * 作为单一 Activity 架构 (Single Activity Architecture) 的宿主容器。
 * 使用 @AndroidEntryPoint 注解，允许在 Activity 中注入 Hilt 依赖（如 ViewModel）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val isBenchmarkMode: Boolean by lazy { BenchmarkMode.isEnabled(applicationContext) }

    /** Activity 注入 Runtime 本身不会解析 AppDatabase。 */
    @Inject
    lateinit var databaseStartupRuntime: DatabaseStartupRuntime

    /** 与数据库 Runtime 并行的 no-backup 加密快照状态；读取路径不解析 Room。 */
    @Inject
    lateinit var startupSnapshotRuntime: StartupSnapshotRuntime

    /** 只有数据库 Ready 后才允许创建，RecoveryRequired 时始终为 null。 */
    private var mainViewModel: MainViewModel? = null

    /** 仅由 Activity 主线程读写；onStop 只记录事实，下一次 onStart 才改变 Compose key。 */
    private var hasWidgetForegroundStarted = false
    private var hasWidgetStoppedSinceStart = false
    private var widgetForegroundGeneration by mutableLongStateOf(0L)

    override fun onStart() {
        super.onStart()
        when {
            !hasWidgetForegroundStarted -> {
                // 首次显式写入初始 key 0，不触发新 effect。
                hasWidgetForegroundStarted = true
                widgetForegroundGeneration = 0L
            }
            hasWidgetStoppedSinceStart -> {
                // 只有真正回前台时才让 Compose 看见新 key。
                hasWidgetStoppedSinceStart = false
                widgetForegroundGeneration += 1
            }
        }
    }

    override fun onStop() {
        // 不修改 Compose state，避免后台生命周期阶段重启 LaunchedEffect。
        hasWidgetStoppedSinceStart = true
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 开启 Edge-to-Edge 沉浸式模式
        //
        // targetSdk 35+ 起系统强制启用 edge-to-edge，无法关闭：
        // 旧的 WindowCompat.setDecorFitsSystemWindows(window, false) 调用在新 targetSdk 下
        // 变为无效果的 no-op（系统已经强制铺满），改用官方推荐的 enableEdgeToEdge()，
        // 它同时兼容新旧系统版本行为。
        // 状态栏图标明暗对比色仍由 core/ui 的 DawnTheme（WindowInsetsControllerCompat.
        // isAppearanceLightStatusBars）单独控制，与这里不冲突。
        enableEdgeToEdge()

        // Splash、快照和实时 Root 由同一个纯 policy 决定，避免各处分叉条件漂移。
        splashScreen.setKeepOnScreenCondition {
            DatabaseStartupUiPolicy.decide(
                databaseStartupRuntime.state.value,
                startupSnapshotRuntime.state.value,
                liveRootReady = mainViewModel?.uiState?.value is MainUiState.Success,
            ).keepSplash
        }

        // 获取当前应用版本号
        //
        // 必须在 onCreate 中取一次并复用：getPackageInfo 是一次同步 Binder IPC，
        // 若写在 setContent 的 composable 作用域内，会随每次重组在主线程重复发起 IPC，
        // 在冷启动阶段 system_server 繁忙时可能显著拖慢首帧。
        val currentVersionCode = runCatching {
            val packageInfo = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)
        }.getOrDefault(0L)

        // 设置 Compose 内容视图
        setContent {
            val databaseState by databaseStartupRuntime.state.collectAsState()
            val snapshotState by startupSnapshotRuntime.state.collectAsState()
            // DB Ready 后先创建 MainViewModel 以收集真实 live readiness；但在它 Success 前仍由
            // 同一 policy 保持快照或 Splash，不能提前构造 NavHost/ReportDrawn 的实时 Root。
            val preparationDecision = DatabaseStartupUiPolicy.decide(
                databaseState = databaseState,
                snapshotState = snapshotState,
            )
            val databaseViewModel = if (preparationDecision.createDatabaseViewModels) {
                remember {
                    ViewModelProvider(this@MainActivity)[MainViewModel::class.java].also {
                        mainViewModel = it
                    }
                }
            } else {
                null
            }
            val liveUiState = if (databaseViewModel == null) {
                MainUiState.Loading
            } else {
                databaseViewModel.uiState.collectAsState().value
            }
            val decision = DatabaseStartupUiPolicy.decide(
                databaseState = databaseState,
                snapshotState = snapshotState,
                liveRootReady = liveUiState is MainUiState.Success,
            )
            var automaticRecoveryRestartAttempted by rememberSaveable { mutableStateOf(false) }
            when {
                decision.showRecovery -> {
                    val startupState = databaseState as DatabaseRuntimeState.RecoveryRequired
                    // 只有 marker 已确认落盘时才首次自动交给独立进程重启。启动跳板失败时
                    // 不改变该页状态，用户仍可使用同一页的手动“安全重启”入口重试。
                    if (!automaticRecoveryRestartAttempted &&
                        DatabaseRecoveryRestartPolicy.shouldAutoRestart(startupState)
                    ) {
                        LaunchedEffect(startupState) {
                            automaticRecoveryRestartAttempted = true
                            ControlledProcessRestarter.restart(this@MainActivity)
                        }
                    }
                    DatabaseRecoveryScreen(
                        reason = startupState.reason,
                        entryMode = startupState.entryMode,
                        runtime = databaseStartupRuntime,
                        onRestartRequired = { ControlledProcessRestarter.restart(this@MainActivity) }
                    )
                }
                decision.showBlocked -> DatabaseStartupBlockedScreen()
                decision.showSnapshot -> {
                    val snapshot = (snapshotState as StartupSnapshotRuntimeState.Available).snapshot
                    val snapshotSettings = remember(snapshot.revision) { snapshot.visualSettings.toAppSettings() }
                    val snapshotDarkTheme = when (snapshotSettings.themeMode) {
                        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                        AppThemeMode.LIGHT -> false
                        AppThemeMode.DARK -> true
                    }
                    DawnTheme(appSettings = snapshotSettings, darkTheme = snapshotDarkTheme) {
                        StartupTimetableContent(snapshot = snapshot)
                    }
                }
                decision.showLiveRoot -> {
            val viewModel = requireNotNull(databaseViewModel)
            val uiState = liveUiState
            val exhaustedMuteRecoveries by viewModel.exhaustedMuteRecoveries.collectAsState()
            // 全局 UpdateViewModel
            val updateViewModel: UpdateViewModel = hiltViewModel()
            val updateUiState by updateViewModel.uiState.collectAsState()

            // 读取上一次启动时捕获的崩溃报告（如果有）
            //
            // 读取即清除文件（见 CrashReporter.readAndClear），保证同一份崩溃报告只弹一次。
            // 文件 IO 放到 Dispatchers.IO 执行，避免阻塞首帧渲染。
            var crashReport by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                crashReport = withContext(Dispatchers.IO) {
                    runCatching { CrashReporter.readAndClear(applicationContext) }.getOrNull()
                }
            }

            // 监听更新事件 (Toast)
            if (!isBenchmarkMode) {
                LaunchedEffect(Unit) {
                    updateViewModel.eventFlow.collect { event ->
                        when (event) {
                            is com.dawncourse.feature.update.UpdateEvent.ShowToast -> {
                                android.widget.Toast.makeText(this@MainActivity, event.message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            // Auto check for update on launch (silent)
            if (!isBenchmarkMode) {
                LaunchedEffect(Unit) {
                    delay(1200)
                    updateViewModel.checkUpdate(isManual = false, currentVersionCode = currentVersionCode)
                }
            }

            // 仅在设置加载成功后渲染界面，避免使用默认设置导致逻辑误触发
            if (uiState is MainUiState.Success) {
                val successState = uiState as MainUiState.Success
                val settings = successState.settings
                val scheduleRevision = successState.scheduleRevision

                // 只在实时 Root 已取得完整稳定聚合后替换整包快照；不和快照 UI 合并状态。
                LaunchedEffect(successState) {
                    viewModel.refreshStartupSnapshot(successState)
                    // 释放 Runtime 中的首帧大对象，不删除刚刚提交的加密快照文件。
                    startupSnapshotRuntime.releaseVisibleSnapshot()
                }

                // 监听设置变化，调度每日闹钟计算任务（WorkManager）
                //
                // 说明：
                // - DailySchedulerWorker 的职责是“根据今日课程 + 用户设置”去设置 AlarmManager 闹钟
                // - 它同时承担两类闹钟的计算与下发：
                //   1) 上课提醒（通知）
                //   2) 自动静音/取消静音
                //   3) 课程状态通知及其下一边界刷新
                //
                // 因此 WorkManager 的调度条件必须与上述“任一功能开关”一致：
                // - 只要【上课提醒】、【自动静音】或【课程状态通知】任一开启，就需要周期保底
                // - 三者都关闭时，仍先执行一次即时对账以清理旧 Alarm/通知，再取消周期保底任务
                // - revision 同时包含当前学期及 Widget 显示/筛选字段（含教师、颜色），编辑、导入、还原后都会触发即时收敛
                if (!isBenchmarkMode) {
                    LaunchedEffect(scheduleRevision, widgetForegroundGeneration) {
                        // `updateWidgetNow` 只启动 Widget 自己受 Supervisor/IO 保护的协程；必须在
                        // 任何后台挂起点前同步调用，Compose key 的取消与重启负责相邻版本线性化。
                        runCatching {
                            WidgetSyncManager.updateWidgetNow(applicationContext)
                        }.onFailure { error ->
                            android.util.Log.w(
                                "MainActivity",
                                "widget refresh failed",
                                error,
                            )
                        }
                        // LaunchedEffect 中的未捕获异常会冒泡到 Recomposer。此处仅下发后台
                        // 对账任务，WorkManager 或 OEM JobScheduler 的临时失败不应阻断主界面。
                        runCatching {
                            runStartupBackgroundWork {
                                ReminderScheduler.triggerImmediateWork(
                                    applicationContext,
                                    forceReplay = false,
                                )
                                if (scheduleRevision.hasEnabledSystemSchedule) {
                                    ReminderScheduler.scheduleDailyWork(applicationContext)
                                } else {
                                    ReminderScheduler.cancelWork(applicationContext)
                                }
                            }
                        }.onFailure {
                            android.util.Log.w(
                                "MainActivity",
                                "schedule daily reminder work failed",
                                it
                            )
                        }
                    }

                    // 监听 WebDAV 自动同步配置变化，统一调度 WorkManager 任务。
                    LaunchedEffect(
                        settings.enableWebDavAutoSync,
                        settings.webDavAutoSyncMode,
                        settings.webDavAutoSyncFixedAt,
                        settings.webDavAutoSyncIntervalValue,
                        settings.webDavAutoSyncIntervalUnit
                    ) {
                        runCatching {
                            runStartupBackgroundWork {
                                WebDavAutoSyncScheduler.schedule(applicationContext, settings)
                            }
                        }.onFailure {
                            android.util.Log.w(
                                "MainActivity",
                                "schedule WebDAV auto sync failed",
                                it
                            )
                        }
                    }
                }

                // 计算是否应使用深色模式
                val darkTheme = when (settings.themeMode) {
                    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                    AppThemeMode.LIGHT -> false
                    AppThemeMode.DARK -> true
                }

                // 应用全局主题
                DawnTheme(
                    appSettings = settings,
                    darkTheme = darkTheme
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Navigation Host 导航主机
                        // 管理应用内的页面跳转
                        val navController = rememberNavController()
                        NavHost(
                            navController = navController,
                            startDestination = "timetable"
                        ) {
                            // 课程表主页面
                            composable("timetable") {
                                TimetableRoute(
                                    onSettingsClick = {
                                        navController.navigate("settings")
                                    },
                                    onAddClick = {
                                        navController.navigate("course_editor")
                                    },
                                    onImportClick = {
                                        navController.navigate("import")
                                    },
                                    onCourseClick = { courseId ->
                                        // 传递 courseId 进行编辑，若为 0 或 null 则为新建
                                        navController.navigate("course_editor?courseId=$courseId")
                                    },
                                    onNavigateToQidiSync = {
                                        navController.navigate("zf_sync")
                                    },
                                    onNavigateToZfSync = {
                                        navController.navigate("zf_sync")
                                    }
                                )
                            }
                            
                            // 导入页面
                            composable("import") {
                                ImportScreen(
                                    onImportSuccess = {
                                        // 导入成功后返回上一页
                                        navController.popBackStack()
                                    }
                                )
                            }

                            // 设置页面
                            composable("settings") {
                                SettingsScreen(
                                    onBackClick = {
                                        navController.popBackStack()
                                    },
                                    onCheckUpdate = {
                                        updateViewModel.checkUpdate(isManual = true, currentVersionCode = currentVersionCode)
                                    },
                                    onOpenProfileManager = {
                                        navController.navigate("profile_manager")
                                    }
                                )
                            }

                            // 独立多课表管理页面，feature 仅接收导航回调。
                            composable("profile_manager") {
                                ProfileManagementScreen(
                                    onBackClick = { navController.popBackStack() },
                                    onImport = { profileId ->
                                        navController.navigate("profile_import?targetProfileId=$profileId")
                                    },
                                )
                            }

                            // 从课表管理进入时显式携带目标 Profile；导入 ViewModel 会在页面进入时冻结该落点。
                            composable(
                                route = "profile_import?targetProfileId={targetProfileId}",
                                arguments = listOf(navArgument("targetProfileId") {
                                    type = NavType.LongType
                                }),
                            ) { backStackEntry ->
                                ImportScreen(
                                    targetProfileId = backStackEntry.arguments?.getLong("targetProfileId") ?: 0L,
                                    onImportSuccess = {
                                        navController.popBackStack("timetable", inclusive = false)
                                    },
                                )
                            }
                            

                            // 正方自动同步页面（复用同一实现）
                            composable("zf_sync") {
                                QidiAutoSyncScreen(
                                    onBackClick = { navController.popBackStack() },
                                    onFinish = { navController.popBackStack() },
                                    provider = com.dawncourse.core.domain.model.SyncProviderType.ZF
                                )
                            }
                            
                            // 课程编辑页面，支持添加新课程和编辑已有课程
                            // 使用可选参数 courseId，如果不传则默认为新建模式
                            composable(
                                route = "course_editor?courseId={courseId}",
                                arguments = listOf(navArgument("courseId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                })
                            ) { backStackEntry ->
                                val courseId = backStackEntry.arguments?.getString("courseId")
                                val courseEditorViewModel: CourseEditorViewModel = hiltViewModel()
                                val course by courseEditorViewModel.course.collectAsState()
                                val currentSemesterId by courseEditorViewModel.currentSemesterId.collectAsState()
                                val currentSemesterWeekCount by courseEditorViewModel.currentSemesterWeekCount.collectAsState()
                                val hasValidTargetSemester by courseEditorViewModel.hasValidTargetSemester.collectAsState()
                                
                                // 如果是编辑模式且课程数据尚未加载完成，显示 Loading
                                val isEditing = courseId != null && courseId != "0"
                                if (isEditing && course == null) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    CourseEditorScreen(
                                        course = course,
                                        currentSemesterId = currentSemesterId,
                                        currentSemesterWeekCount = currentSemesterWeekCount,
                                        hasValidSemester = hasValidTargetSemester,
                                        onBackClick = { navController.popBackStack() },
                                        onSaveClick = { newCourses ->
                                            courseEditorViewModel.saveCourses(
                                                courses = newCourses,
                                                onSaved = { navController.popBackStack() },
                                                onConflict = { message ->
                                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // 恢复责任需要用户立即决策时，不与普通版本更新弹窗叠加。
                        if (!isBenchmarkMode && exhaustedMuteRecoveries.isEmpty()) {
                            when (val state = updateUiState) {
                                is UpdateUiState.Available -> {
                                    UpdateDialog(
                                        info = state.updateInfo,
                                        onDismiss = { updateViewModel.dismissDialog() },
                                        onUpdate = {
                                            val url = state.updateInfo.downloadUrl
                                            if (!isValidUpdateDownloadUrl(url)) {
                                                android.widget.Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.update_download_url_unsafe),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                updateViewModel.dismissDialog()
                                            } else {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    startActivity(intent)
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(
                                                        this@MainActivity,
                                                        getString(R.string.update_browser_unavailable),
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        },
                                        onIgnore = { updateViewModel.ignoreVersion(state.updateInfo.versionCode) },
                                        isUpdate = true
                                    )
                                }
                                is UpdateUiState.VersionInfo -> {
                                    UpdateDialog(
                                        info = state.updateInfo,
                                        onDismiss = { updateViewModel.dismissDialog() },
                                        onUpdate = {},
                                        onIgnore = {},
                                        isUpdate = false
                                    )
                                }
                                is UpdateUiState.Error -> {
                                    UpdateErrorDialog(
                                        message = state.message,
                                        onDismiss = { updateViewModel.dismissDialog() }
                                    )
                                }
                                else -> {}
                            }
                        }

                        // 静音恢复责任来自持久 Store；即使通知权限关闭，前台仍必须可见且不可静默消失。
                        exhaustedMuteRecoveries.firstOrNull()?.let { recovery ->
                            AlertDialog(
                                onDismissRequest = {},
                                title = { Text(stringResource(R.string.mute_recovery_dialog_title)) },
                                text = { Text(stringResource(R.string.mute_recovery_dialog_content)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            val manager = getSystemService(NotificationManager::class.java)
                                            val hasAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                                                manager.isNotificationPolicyAccessGranted
                                            if (hasAccess) {
                                                viewModel.retryMuteRecovery(recovery.key)
                                            } else {
                                                runCatching {
                                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.mute_recovery_retry))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { viewModel.releaseMuteRecovery(recovery.key) }
                                    ) {
                                        Text(stringResource(R.string.mute_recovery_release))
                                    }
                                }
                            )
                        }

                        // 上次启动崩溃报告弹窗
                        // 关闭后 crashReport 置空，避免同一次 Composition 内重复弹出
                        crashReport?.let { report ->
                            CrashReportDialog(
                                report = report,
                                onDismiss = { crashReport = null }
                            )
                        }
                    }
                }
            } else {
                // 加载中，显示空白（被 Splash Screen 遮挡）
                Box(modifier = Modifier.fillMaxSize())
            }
                }
                else -> Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
