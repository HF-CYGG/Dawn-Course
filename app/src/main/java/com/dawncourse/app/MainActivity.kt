package com.dawncourse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.dawncourse.core.ui.theme.DawnTheme
import com.dawncourse.feature.settings.SettingsScreen
import com.dawncourse.feature.timetable.TimetableRoute
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.dawncourse.feature.timetable.CourseEditorScreen
import com.dawncourse.feature.timetable.CourseEditorViewModel
import com.dawncourse.feature.import_module.ImportScreen

/**
 * 应用程序主 Activity
 *
 * 作为单一 Activity 架构 (Single Activity Architecture) 的宿主容器。
 * 使用 @AndroidEntryPoint 注解，允许在 Activity 中注入 Hilt 依赖（如 ViewModel）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 开启 Edge-to-Edge 沉浸式模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置 Compose 内容视图
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()

            // 应用全局主题
            DawnTheme(appSettings = settings) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Wallpaper Background
                    if (settings.wallpaperUri != null) {
                        AsyncImage(
                            model = settings.wallpaperUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Navigation Host
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "timetable"
                    ) {
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
                                },
                                onImportClick = {
                                    navController.navigate("import")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("import") {
                            ImportScreen(
                                onImportSuccess = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable(
                            route = "course_editor?courseId={courseId}",
                            arguments = listOf(navArgument("courseId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            })
                        ) { backStackEntry ->
                            val courseId = backStackEntry.arguments?.getString("courseId")
                            val viewModel: CourseEditorViewModel = hiltViewModel()
                            val course by viewModel.course.collectAsState()
                            
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
                                        viewModel.saveCourse(newCourse) {
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
