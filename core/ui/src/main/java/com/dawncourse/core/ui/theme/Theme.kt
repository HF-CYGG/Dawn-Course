package com.dawncourse.core.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import com.dawncourse.core.domain.model.AppSettings
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
// 壁纸动态取色的对比色（用于主页面顶栏等需要与背景形成对比的图标/文字）
val LocalWallpaperContrastColor = staticCompositionLocalOf { Color.Unspecified }

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
    val context = LocalContext.current
    // 由壁纸生成的主题配色（动态取色开关打开且设置了壁纸时才生效）
    var wallpaperColorScheme by remember(appSettings.dynamicColor, appSettings.wallpaperUri, darkTheme) {
        mutableStateOf<ColorScheme?>(null)
    }
    // 由壁纸亮度推导的中性对比色，避免顶栏图标“太花”
    var wallpaperContrastColor by remember(appSettings.dynamicColor, appSettings.wallpaperUri, darkTheme) {
        mutableStateOf<Color?>(null)
    }

    // 壁纸变化或主题模式变化时重新计算动态配色
    LaunchedEffect(appSettings.dynamicColor, appSettings.wallpaperUri, darkTheme) {
        if (!appSettings.dynamicColor || appSettings.wallpaperUri.isNullOrBlank()) {
            wallpaperColorScheme = null
            wallpaperContrastColor = null
            return@LaunchedEffect
        }
        val result = generateColorSchemeFromWallpaper(context, appSettings.wallpaperUri, darkTheme)
        wallpaperColorScheme = result?.first
        wallpaperContrastColor = result?.second
    }

    // 颜色策略：优先壁纸取色，其次系统动态取色，最后回退到默认主题
    val colorScheme = when {
        appSettings.dynamicColor && wallpaperColorScheme != null -> wallpaperColorScheme!!
        appSettings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    // 顶栏对比色：优先壁纸对比色，否则使用主题 onSurface
    val resolvedTopBarIconColor = wallpaperContrastColor ?: colorScheme.onSurface
    
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

    CompositionLocalProvider(
        LocalAppSettings provides appSettings,
        LocalWallpaperContrastColor provides resolvedTopBarIconColor
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

/**
 * 从壁纸提取主色并生成简化主题色，同时计算顶栏对比色
 *
 * 设计原则：
 * 1. 主色取自壁纸主色，但降低饱和度，避免视觉过于花哨
 * 2. 亮度范围根据深浅主题限制，确保整体可读性
 * 3. 对比色使用中性灰阶，确保顶栏图标与背景形成对比
 */
private suspend fun generateColorSchemeFromWallpaper(
    context: Context,
    wallpaperUri: String?,
    darkTheme: Boolean
): Pair<ColorScheme, Color>? {
    if (wallpaperUri.isNullOrBlank()) return null
    // 使用低分辨率位图取色，减少内存与计算开销
    val bitmap = loadPaletteBitmap(context, wallpaperUri) ?: return null
    val palette = Palette.from(bitmap).maximumColorCount(8).generate()
    val dominant = palette.getDominantColor(0)
    if (dominant == 0) return null
    // HSL 调整：降低饱和度并限定亮度区间，避免过亮或过暗
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(dominant, hsl)
    val saturation = hsl[1].coerceAtMost(0.35f)
    val lightness = if (darkTheme) {
        hsl[2].coerceIn(0.30f, 0.60f)
    } else {
        hsl[2].coerceIn(0.45f, 0.80f)
    }
    val primary = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness)))
    val secondary = Color(ColorUtils.HSLToColor(floatArrayOf((hsl[0] + 20f) % 360f, saturation * 0.7f, lightness)))
    val tertiary = Color(ColorUtils.HSLToColor(floatArrayOf((hsl[0] + 40f) % 360f, saturation * 0.5f, lightness)))
    val scheme = if (darkTheme) {
        darkColorScheme(primary = primary, secondary = secondary, tertiary = tertiary)
    } else {
        lightColorScheme(primary = primary, secondary = secondary, tertiary = tertiary)
    }
    // 对比色：根据壁纸主色亮度选择深灰或浅灰
    val contrastColor = if (ColorUtils.calculateLuminance(dominant) > 0.6) {
        Color(0xFF1F1F1F)
    } else {
        Color(0xFFF5F5F5)
    }
    return scheme to contrastColor
}

/**
 * 加载用于调色板分析的低分辨率壁纸位图
 *
 * 仅用于取色，不用于显示，优先控制尺寸以降低内存占用。
 */
private suspend fun loadPaletteBitmap(context: Context, wallpaperUri: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = Uri.parse(wallpaperUri)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // 限制目标尺寸，避免高分辨率壁纸造成内存压力
                    val targetWidth = minOf(info.size.width, 256)
                    val targetHeight = minOf(info.size.height, 256)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            } else {
                val options = BitmapFactory.Options().apply {
                    // 旧版本通过采样率降低解码成本
                    inSampleSize = 4
                }
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            }
        }.getOrNull()
    }
}
