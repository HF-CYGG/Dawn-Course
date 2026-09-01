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
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import kotlinx.coroutines.delay
import javax.inject.Inject
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.domain.repository.OperationalDataReadiness

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

    /** 只有数据库 Ready 后才允许创建，RecoveryRequired 时始终为 null。 */
    private var mainViewModel: MainViewModel? = null

    override fun onStart() {
        super.onStart()
        // 每次回到前台时，强制刷新 Widget，以防系统时间变更或其他状态变化未及时同步
        if (!isBenchmarkMode &&
            databaseStartupRuntime.readiness() == OperationalDataReadiness.READY
        ) {
            WidgetSyncManager.updateWidgetNow(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // 开启 Edge-to-Edge 沉浸式模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 数据库仍在 IO 检查时保持 Splash；恢复状态必须释放 Splash 展示可见入口。
        splashScreen.setKeepOnScreenCondition {
            databaseStartupRuntime.state.value is DatabaseRuntimeState.Starting ||
                mainViewModel?.uiState?.value is MainUiState.Loading
        }

        // 设置 Compose 内容视图
        setContent {
            val databaseState by databaseStartupRuntime.state.collectAsState()
            when (val startupState = databaseState) {
                DatabaseRuntimeState.Starting -> Box(modifier = Modifier.fillMaxSize())
                is DatabaseRuntimeState.RecoveryRequired -> DatabaseRecoveryScreen(
                    reason = startupState.reason,
                    runtime = databaseStartupRuntime,
                    onRestartRequired = { ControlledProcessRestarter.restart(this@MainActivity) }
                )
                DatabaseRuntimeState.StartupBlocked -> DatabaseStartupBlockedScreen()
                DatabaseRuntimeState.Ready -> {
            val viewModel = remember {
                ViewModelProvider(this@MainActivity)[MainViewModel::class.java].also {
                    mainViewModel = it
                }
            }
            val uiState by viewModel.uiState.collectAsState()
            val exhaustedMuteRecoveries by viewModel.exhaustedMuteRecoveries.collectAsState()
            
            // 全局 UpdateViewModel
            val updateViewModel: UpdateViewModel = hiltViewModel()
            val updateUiState by updateViewModel.uiState.collectAsState()

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

            // 获取当前应用版本号
            val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            val currentVersionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)

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
                // - revision 同时包含当前学期和课程字段，编辑、导入、还原后都会触发即时收敛
                if (!isBenchmarkMode) {
                    LaunchedEffect(scheduleRevision) {
                        ReminderScheduler.triggerImmediateWork(applicationContext, forceReplay = false)
                        // Profile、学期或课程切换必须与系统触发器同时收敛，避免 Widget 暂留旧课表。
                        WidgetSyncManager.updateWidgetNow(applicationContext)
                        if (scheduleRevision.hasEnabledSystemSchedule) {
                            ReminderScheduler.scheduleDailyWork(applicationContext)
                        } else {
                            ReminderScheduler.cancelWork(applicationContext)
                        }
                    }

                    // 监听 WebDAV 自动同步配置变化，统一调度 WorkManager 任务
                    LaunchedEffect(
                        settings.enableWebDavAutoSync,
                        settings.webDavAutoSyncMode,
                        settings.webDavAutoSyncFixedAt,
                        settings.webDavAutoSyncIntervalValue,
                        settings.webDavAutoSyncIntervalUnit
                    ) {
                        WebDavAutoSyncScheduler.schedule(applicationContext, settings)
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
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(this@MainActivity, "未找到浏览器，无法下载", android.widget.Toast.LENGTH_SHORT).show()
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
                    }
                }
            } else {
                // 加载中，显示空白（被 Splash Screen 遮挡）
                Box(modifier = Modifier.fillMaxSize())
            }
                }
            }
        }
    }
}
