package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * 设置页面的 ViewModel
 *
 * 负责管理和持久化应用程序的设置选项。
 * 通过 [SettingsRepository] 与数据层交互，使用 StateFlow 暴露当前的设置状态。
 *
 * @property settingsRepository 设置数据仓库，用于存取设置数据
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * 当前的应用设置状态流
     *
     * 包含所有个性化配置项（如动态取色、透明度、壁纸等）。
     * 初始值为默认配置，后续会根据 DataStore 中的数据自动更新。
     */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /**
     * 设置是否启用动态取色 (Material You)
     *
     * @param enabled true 表示启用，false 表示禁用
     */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    /**
     * 设置背景透明度
     *
     * @param value 透明度值，范围 0.0 - 1.0
     */
    fun setTransparency(value: Float) {
        viewModelScope.launch {
            settingsRepository.setTransparency(value)
        }
    }

    /**
     * 设置应用字体样式
     *
     * @param style 选定的字体样式枚举 [AppFontStyle]
     */
    fun setFontStyle(style: AppFontStyle) {
        viewModelScope.launch {
            settingsRepository.setFontStyle(style)
        }
    }

    /**
     * 设置自定义壁纸 URI
     *
     * @param uri 壁纸图片的 URI 字符串，若为 null 则清除壁纸
     */
    fun setWallpaperUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setWallpaperUri(uri)
            
            // 异步生成模糊壁纸
            if (uri != null) {
                generateBlurredWallpaper(uri)
            } else {
                settingsRepository.setBlurredWallpaperUri(null)
            }
        }
    }

    private suspend fun generateBlurredWallpaper(uriString: String) {
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }

                // Scale down for performance and blur
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    bitmap.width / 10,
                    bitmap.height / 10,
                    true
                )

                // Simple Stack Blur or RenderScript Blur (Since RenderScript is deprecated, use a simple stack blur implementation or similar)
                // For simplicity, we'll just use a very scaled down image which acts as a blur when scaled up.
                // Or we can implement a fast blur algorithm here.
                // Let's rely on extreme scaling (1/10th size) + RenderEffect/Blur in Compose if needed, 
                // BUT the requirement is PRE-GENERATED BLUR.
                // So we should apply a blur here.
                
                // Applying a simple box blur or just saving the scaled down version (which is pixelated, not gaussian).
                // Let's try to do a simple blur on the scaled bitmap.
                val blurredBitmap = fastBlur(scaledBitmap, 20)

                // Save to internal storage
                val file = File(context.filesDir, "blurred_wallpaper.jpg")
                FileOutputStream(file).use { out ->
                    blurredBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                settingsRepository.setBlurredWallpaperUri(Uri.fromFile(file).toString())
                
                // Recycle
                if (bitmap != scaledBitmap) bitmap.recycle()
                if (scaledBitmap != blurredBitmap) scaledBitmap.recycle()
                // blurredBitmap.recycle() // Don't recycle immediately if used? It's saved.
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Stack Blur Algorithm by Mario Klingemann <mario@quasimondo.com>
    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config, true)

        if (radius < 1) {
            return (null)!!
        }

        val w = bitmap.width
        val h = bitmap.height

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(Math.max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) {
            dv[i] = (i / divsum)
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        var r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (y in 0 until h) {
            rinsum = 0
            ginsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            bsum = 0
            gsum = 0
            rsum = 0
            for (i in -radius..radius) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (x in 0 until w) {

                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[(stackpointer) % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }
        for (x in 0 until w) {
            rinsum = 0
            ginsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            bsum = 0
            gsum = 0
            rsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = Math.max(0, yp) + x

                sir = stack[i + radius]

                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - Math.abs(i)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }

                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                // Preserve alpha channel: ( 0xff000000 & pix[yi] )
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)

        return (bitmap)
    }

    /**
     * 设置课表分割线样式
     *
     * @param type 分割线样式 [DividerType]
     */
    fun setDividerType(type: DividerType) {
        viewModelScope.launch {
            settingsRepository.setDividerType(type)
        }
    }

    /**
     * 设置课表分割线宽度
     *
     * @param width 宽度值 (dp)
     */
    fun setDividerWidth(width: Float) {
        viewModelScope.launch {
            settingsRepository.setDividerWidth(width)
        }
    }

    /**
     * 设置课表分割线颜色
     *
     * @param color 颜色 Hex 字符串
     */
    fun setDividerColor(color: String) {
        viewModelScope.launch {
            settingsRepository.setDividerColor(color)
        }
    }

    /**
     * 设置课表分割线不透明度
     *
     * @param alpha 不透明度 (0.0 - 1.0)
     */
    fun setDividerAlpha(alpha: Float) {
        viewModelScope.launch {
            settingsRepository.setDividerAlpha(alpha)
        }
    }

    /**
     * 设置每天最大节数
     *
     * @param count 节数 (8-16)
     */
    fun setMaxDailySections(count: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxDailySections(count)
        }
    }

    fun setCourseItemHeight(height: Int) {
        viewModelScope.launch {
            settingsRepository.setCourseItemHeight(height)
        }
    }

    /**
     * 设置默认课程时长
     *
     * @param duration 节数 (1-4)
     */
    fun setDefaultCourseDuration(duration: Int) {
        viewModelScope.launch {
            settingsRepository.setDefaultCourseDuration(duration)
        }
    }

    fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        viewModelScope.launch {
            settingsRepository.setSectionTimes(times)
        }
    }

    fun setCardCornerRadius(radius: Int) {
        viewModelScope.launch { settingsRepository.setCardCornerRadius(radius) }
    }

    fun setCardAlpha(alpha: Float) {
        viewModelScope.launch { settingsRepository.setCardAlpha(alpha) }
    }

    fun setShowCourseIcons(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCourseIcons(show) }
    }

    fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode) {
        viewModelScope.launch { settingsRepository.setWallpaperMode(mode) }
    }

    fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setShowWeekend(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowWeekend(show) }
    }

    fun setShowSidebarTime(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowSidebarTime(show) }
    }

    fun setShowSidebarIndex(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowSidebarIndex(show) }
    }

    fun setHideNonThisWeek(hide: Boolean) {
        viewModelScope.launch { settingsRepository.setHideNonThisWeek(hide) }
    }

    fun setShowDateInHeader(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowDateInHeader(show) }
    }

    fun setCurrentSemesterName(name: String) {
        viewModelScope.launch { settingsRepository.setCurrentSemesterName(name) }
    }

    fun setTotalWeeks(weeks: Int) {
        viewModelScope.launch { settingsRepository.setTotalWeeks(weeks) }
    }

    fun setStartDateTimestamp(timestamp: Long) {
        viewModelScope.launch { settingsRepository.setStartDateTimestamp(timestamp) }
    }

    fun setEnableClassReminder(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableClassReminder(enable) }
    }

    fun setReminderMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setReminderMinutes(minutes) }
    }

    fun setEnablePersistentNotification(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnablePersistentNotification(enable) }
    }

    fun setEnableAutoMute(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableAutoMute(enable) }
    }
}
