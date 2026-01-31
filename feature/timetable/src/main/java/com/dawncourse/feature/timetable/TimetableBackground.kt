package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dawncourse.core.domain.model.WallpaperMode

/**
 * 课表背景组件
 *
 * 负责渲染课表的背景，支持：
 * 1. 纯色背景 (Default)
 * 2. 图片壁纸 (Crop/Fill)
 * 3. 背景特效：模糊 (Blur)、亮度 (Brightness)、遮罩透明度 (Transparency)
 *
 * @param wallpaperUri 壁纸 URI，若为 null 则使用默认背景色
 * @param wallpaperMode 壁纸缩放模式 [WallpaperMode]
 * @param backgroundBlur 背景模糊半径 (dp)
 * @param backgroundBrightness 背景亮度 (0.0-1.0)
 * @param transparency 遮罩层透明度 (0.0-1.0)
 * @param isDarkTheme 是否为深色模式 (影响遮罩层颜色)
 */
@Composable
fun TimetableBackground(
    wallpaperUri: String?,
    wallpaperMode: WallpaperMode,
    backgroundBlur: Float,
    backgroundBrightness: Float,
    transparency: Float,
    isDarkTheme: Boolean
) {
    if (wallpaperUri != null) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        
        // Optimize: Downsample image to screen size to improve blur performance and memory usage
        // This is crucial for performance on high-res wallpapers
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        
        // Calculate brightness matrix once
        val brightnessMatrix = remember(backgroundBrightness) {
            ColorMatrix().apply {
                setToScale(backgroundBrightness, backgroundBrightness, backgroundBrightness, 1f)
            }
        }

        // Image Request with specific size
        val imageRequest = remember(wallpaperUri, screenWidthPx, screenHeightPx) {
            ImageRequest.Builder(context)
                .data(wallpaperUri)
                .size(screenWidthPx, screenHeightPx) // Force resize to screen resolution
                .crossfade(true)
                .build()
        }

        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = if (wallpaperMode == WallpaperMode.CROP) ContentScale.Crop else ContentScale.FillBounds,
            colorFilter = ColorFilter.colorMatrix(brightnessMatrix),
            modifier = Modifier
                .fillMaxSize()
                .let {
                    if (backgroundBlur > 0f) it.blur(backgroundBlur.dp) else it
                }
        )

        // Overlay Layer
        val overlayColor = if (isDarkTheme) Color.Black else Color.White
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor.copy(alpha = transparency))
        )
    } else {
        // Default Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}
