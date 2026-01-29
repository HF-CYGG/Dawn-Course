package com.dawncourse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.dawncourse.core.ui.theme.DawnTheme
import com.dawncourse.feature.timetable.TimetableRoute
import dagger.hilt.android.AndroidEntryPoint

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
        // 这将允许内容延伸到状态栏和导航栏下方
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置 Compose 内容视图
        setContent {
            // 应用全局主题
            DawnTheme {
                // 显示课表功能路由（目前作为首页）
                // 后续可以使用 Navigation Compose 进行多页面管理
                TimetableRoute()
            }
        }
    }
}
