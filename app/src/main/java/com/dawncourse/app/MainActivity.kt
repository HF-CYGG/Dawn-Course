package com.dawncourse.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.ui.theme.DawnTheme
import com.dawncourse.feature.settings.SettingsScreen
import com.dawncourse.feature.timetable.TimetableRoute
import com.dawncourse.feature.timetable.notification.PersistentNotificationService
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.dawncourse.feature.timetable.CourseEditorScreen
import com.dawncourse.feature.timetable.CourseEditorViewModel
import com.dawncourse.feature.timetable.notification.ReminderScheduler

/**
 * 应用程序主 Activity
 *
 * 作为单一 Activity 架构 (Single Activity Architecture) 的宿主容器。
 * 使用 @AndroidEntryPoint 注解，允许在 Activity 中注入 Hilt 依赖（如 ViewModel）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install Splash Screen before super.onCreate
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        
        // 开启 Edge-to-Edge 沉浸式模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置 Compose 内容视图
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()

            // 监听设置变化，调度提醒任务
            LaunchedEffect(settings.enableClassReminder) {
                if (settings.enableClassReminder) {
                    ReminderScheduler.scheduleDailyWork(applicationContext)
                } else {
                    ReminderScheduler.cancelWork(applicationContext)
                }
            }

            // 监听设置变化，启动/停止常驻通知服务
            LaunchedEffect(settings.enablePersistentNotification) {
                val intent = Intent(applicationContext, PersistentNotificationService::class.java)
                if (settings.enablePersistentNotification) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                } else {
                    applicationContext.stopService(intent)
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
                    // Navigation Host
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
                                onCourseClick = { courseId ->
                                    navController.navigate("course_editor?courseId=$courseId")
                                }
                            )
                        }
                        
                        // 设置页面
                        composable("settings") {
                            SettingsScreen(
                                onBackClick = {
                                    navController.popBackStack()
                                },
                                onNavigateToTimetableSettings = {
                                    navController.navigate("timetable_settings")
                                }
                            )
                        }
                        
                        // 课表设置页面
                        composable("timetable_settings") {
                            com.dawncourse.feature.settings.TimetableSettingsScreen(
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        
                        // 课程编辑页面，支持添加新课程和编辑已有课程
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
                            
                            val isEditing = courseId != null && courseId != "0"
                            if (isEditing && course == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                CourseEditorScreen(
                                    course = course,
                                    onBackClick = { navController.popBackStack() },
                                    onSaveClick = { newCourse ->
                                        courseEditorViewModel.saveCourse(newCourse) {
                                            navController.popBackStack()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
