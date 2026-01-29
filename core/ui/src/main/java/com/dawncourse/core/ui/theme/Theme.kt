package com.dawncourse.core.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dawncourse.core.domain.model.AppSettings

// 深色模式配色方案
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

// 浅色模式配色方案
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

/**
 * 应用全局主题 Composable
 *
 * 负责提供 Material Design 3 的上下文环境，包括颜色、排版等。
 * 支持 Android 12+ 的动态取色 (Dynamic Color) 功能。
 *
 * @param appSettings 应用设置（动态取色、字体等）
 * @param darkTheme 是否使用深色主题，默认为系统设置
 * @param content 需要被主题包裹的 UI 内容
 */
@Composable
fun DawnTheme(
    appSettings: AppSettings = AppSettings(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 确定当前使用的配色方案
    val colorScheme = when {
        // 如果开启动态取色且系统版本支持，则使用系统生成的动态配色
        appSettings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 否则使用自定义的深色/浅色方案
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    // 设置状态栏颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 在 Edge-to-Edge 模式下，状态栏背景应为透明
            window.statusBarColor = androidx.compose.ui.graphics.Color.Transparent.toArgb()
            // 状态栏图标颜色控制：非深色模式下图标为深色
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val typography = getTypography(appSettings.fontStyle)

    CompositionLocalProvider(LocalAppSettings provides appSettings) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
