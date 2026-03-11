package com.dawncourse.feature.timetable

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dawncourse.core.domain.model.WallpaperMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * @param onWallpaperLightChanged 壁纸亮度分析结果回调（true 为亮图，false 为暗图）
 */
@Composable
fun TimetableBackground(
    wallpaperUri: String?,
    wallpaperMode: WallpaperMode,
    backgroundBlur: Float,
    backgroundBrightness: Float,
    transparency: Float,
    onWallpaperLightChanged: (Boolean) -> Unit = {}
) {
    if (wallpaperUri != null) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()
        var analyzedUri by remember { mutableStateOf<String?>(null) }
        // 将图片下采样到屏幕尺寸，降低模糊与渲染成本
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        
        // 预先计算亮度矩阵，避免频繁创建对象
        val brightnessMatrix = remember(backgroundBrightness) {
            ColorMatrix().apply {
                setToScale(backgroundBrightness, backgroundBrightness, backgroundBrightness, 1f)
            }
        }

        // 指定解码尺寸，减少内存占用
        val imageRequest = remember(wallpaperUri, screenWidthPx, screenHeightPx) {
            ImageRequest.Builder(context)
                .data(wallpaperUri)
                .size(screenWidthPx, screenHeightPx) // Force resize to screen resolution
                .allowHardware(false)
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
                },
            onSuccess = { state ->
                if (analyzedUri == wallpaperUri) return@AsyncImage
                analyzedUri = wallpaperUri
                val drawable = state.result.drawable
                coroutineScope.launch {
                    // 计算壁纸整体亮度，用于决定周数与时间文字颜色
                    val isLight = isWallpaperLight(drawable.toBitmap())
                    onWallpaperLightChanged(isLight)
                }
            }
        )

        // 遮罩层
        val overlayColor = Color.Black
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor.copy(alpha = transparency))
        )
    } else {
        // 默认背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}

/**
 * 分析壁纸平均亮度，判断是否属于亮色图片
 *
 * true 表示亮图（建议用深色文字），false 表示暗图（建议用白色文字）
 */
private suspend fun isWallpaperLight(bitmap: Bitmap): Boolean {
    return withContext(Dispatchers.Default) {
        try {
            // 缩小图片以降低计算成本，避免主线程卡顿
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            var totalLuminance = 0.0
            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            for (pixel in pixels) {
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b)
            }
            // 不能回收原始 bitmap，它仍可能被图片绘制管线持有
            // 仅回收缩放后的临时 bitmap，避免内存泄漏
            if (scaled != bitmap) {
                scaled.recycle()
            }
            val avgLuminance = totalLuminance / (32 * 32)
            avgLuminance > 128.0
        } catch (_: Throwable) {
            false
        }
    }
}
