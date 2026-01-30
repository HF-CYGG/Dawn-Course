package com.dawncourse.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置数据仓库的实现类
 *
 * 使用 Jetpack DataStore (Preferences) 来持久化存储简单的键值对配置。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val dataStore = context.dataStore

    private object PreferencesKeys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        val TRANSPARENCY = floatPreferencesKey("transparency")
        val FONT_STYLE = stringPreferencesKey("font_style")
        val DIVIDER_TYPE = stringPreferencesKey("divider_type")
        val DIVIDER_WIDTH = floatPreferencesKey("divider_width_float")
        val DIVIDER_COLOR = stringPreferencesKey("divider_color")
        val DIVIDER_ALPHA = floatPreferencesKey("divider_alpha")
        val MAX_DAILY_SECTIONS = intPreferencesKey("max_daily_sections")
        val DEFAULT_COURSE_DURATION = intPreferencesKey("default_course_duration")
        val SECTION_TIMES = stringPreferencesKey("section_times")
        val COURSE_ITEM_HEIGHT = intPreferencesKey("course_item_height")
        
        // New Keys
        val CARD_CORNER_RADIUS = intPreferencesKey("card_corner_radius")
        val CARD_ALPHA = floatPreferencesKey("card_alpha")
        val SHOW_COURSE_ICONS = booleanPreferencesKey("show_course_icons")
        val WALLPAPER_MODE = stringPreferencesKey("wallpaper_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val SHOW_SIDEBAR_TIME = booleanPreferencesKey("show_sidebar_time")
        val SHOW_SIDEBAR_INDEX = booleanPreferencesKey("show_sidebar_index")
        val HIDE_NON_THIS_WEEK = booleanPreferencesKey("hide_non_this_week")
        val SHOW_DATE_IN_HEADER = booleanPreferencesKey("show_date_in_header")
        val CURRENT_SEMESTER_NAME = stringPreferencesKey("current_semester_name")
        val TOTAL_WEEKS = intPreferencesKey("total_weeks")
        val START_DATE_TIMESTAMP = androidx.datastore.preferences.core.longPreferencesKey("start_date_timestamp")
        val ENABLE_CLASS_REMINDER = booleanPreferencesKey("enable_class_reminder")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val ENABLE_PERSISTENT_NOTIFICATION = booleanPreferencesKey("enable_persistent_notification")
        val ENABLE_AUTO_MUTE = booleanPreferencesKey("enable_auto_mute")
        val BLURRED_WALLPAPER_URI = stringPreferencesKey("blurred_wallpaper_uri")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true
        val wallpaperUri = preferences[PreferencesKeys.WALLPAPER_URI]
        val transparency = preferences[PreferencesKeys.TRANSPARENCY] ?: 0f
        val fontStyleName = preferences[PreferencesKeys.FONT_STYLE] ?: AppFontStyle.SYSTEM.name
        val fontStyle = try {
            AppFontStyle.valueOf(fontStyleName)
        } catch (e: IllegalArgumentException) {
            AppFontStyle.SYSTEM
        }
        
        val dividerTypeName = preferences[PreferencesKeys.DIVIDER_TYPE] ?: DividerType.SOLID.name
        val dividerType = try {
            DividerType.valueOf(dividerTypeName)
        } catch (e: Exception) { DividerType.SOLID }
        val dividerWidthDp = preferences[PreferencesKeys.DIVIDER_WIDTH] ?: 1f
        val dividerColor = preferences[PreferencesKeys.DIVIDER_COLOR] ?: "#E5E7EB"
        val dividerAlpha = preferences[PreferencesKeys.DIVIDER_ALPHA] ?: 1.0f
        val courseItemHeightDp = preferences[PreferencesKeys.COURSE_ITEM_HEIGHT] ?: 64
        val maxDailySections = preferences[PreferencesKeys.MAX_DAILY_SECTIONS] ?: 12
        val defaultCourseDuration = preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] ?: 2
        
        val sectionTimesString = preferences[PreferencesKeys.SECTION_TIMES] ?: ""
        val sectionTimes = if (sectionTimesString.isNotEmpty()) {
            sectionTimesString.split("|").mapNotNull { pair ->
                val parts = pair.split(",")
                if (parts.size == 2) {
                    com.dawncourse.core.domain.model.SectionTime(parts[0], parts[1])
                } else null
            }
        } else {
            emptyList()
        }

        // Read new settings
        val cardCornerRadius = preferences[PreferencesKeys.CARD_CORNER_RADIUS] ?: 16
        val cardAlpha = preferences[PreferencesKeys.CARD_ALPHA] ?: 0.9f
        val showCourseIcons = preferences[PreferencesKeys.SHOW_COURSE_ICONS] ?: true
        val wallpaperModeName = preferences[PreferencesKeys.WALLPAPER_MODE] ?: com.dawncourse.core.domain.model.WallpaperMode.CROP.name
        val wallpaperMode = try {
            com.dawncourse.core.domain.model.WallpaperMode.valueOf(wallpaperModeName)
        } catch (e: Exception) { com.dawncourse.core.domain.model.WallpaperMode.CROP }
        val themeModeName = preferences[PreferencesKeys.THEME_MODE] ?: com.dawncourse.core.domain.model.AppThemeMode.SYSTEM.name
        val themeMode = try {
            com.dawncourse.core.domain.model.AppThemeMode.valueOf(themeModeName)
        } catch (e: Exception) { com.dawncourse.core.domain.model.AppThemeMode.SYSTEM }
        val showWeekend = preferences[PreferencesKeys.SHOW_WEEKEND] ?: true
        val showSidebarTime = preferences[PreferencesKeys.SHOW_SIDEBAR_TIME] ?: true
        val showSidebarIndex = preferences[PreferencesKeys.SHOW_SIDEBAR_INDEX] ?: true
        val hideNonThisWeek = preferences[PreferencesKeys.HIDE_NON_THIS_WEEK] ?: false
        val showDateInHeader = preferences[PreferencesKeys.SHOW_DATE_IN_HEADER] ?: false
        val currentSemesterName = preferences[PreferencesKeys.CURRENT_SEMESTER_NAME] ?: "2025年春季学期"
        val totalWeeks = preferences[PreferencesKeys.TOTAL_WEEKS] ?: 20
        val startDateTimestamp = preferences[PreferencesKeys.START_DATE_TIMESTAMP] ?: 0L
        val enableClassReminder = preferences[PreferencesKeys.ENABLE_CLASS_REMINDER] ?: false
        val reminderMinutes = preferences[PreferencesKeys.REMINDER_MINUTES] ?: 10
        val enablePersistentNotification = preferences[PreferencesKeys.ENABLE_PERSISTENT_NOTIFICATION] ?: false
        val enableAutoMute = preferences[PreferencesKeys.ENABLE_AUTO_MUTE] ?: false
        val blurredWallpaperUri = preferences[PreferencesKeys.BLURRED_WALLPAPER_URI]

        AppSettings(
            dynamicColor = dynamicColor,
            wallpaperUri = wallpaperUri,
            transparency = transparency,
            fontStyle = fontStyle,
            dividerType = dividerType,
            dividerWidthDp = dividerWidthDp,
            dividerColor = dividerColor,
            dividerAlpha = dividerAlpha,
            courseItemHeightDp = courseItemHeightDp,
            maxDailySections = maxDailySections,
            defaultCourseDuration = defaultCourseDuration,
            sectionTimes = sectionTimes,
            cardCornerRadius = cardCornerRadius,
            cardAlpha = cardAlpha,
            showCourseIcons = showCourseIcons,
            wallpaperMode = wallpaperMode,
            themeMode = themeMode,
            showWeekend = showWeekend,
            showSidebarTime = showSidebarTime,
            showSidebarIndex = showSidebarIndex,
            hideNonThisWeek = hideNonThisWeek,
            showDateInHeader = showDateInHeader,
            currentSemesterName = currentSemesterName,
            totalWeeks = totalWeeks,
            startDateTimestamp = startDateTimestamp,
            enableClassReminder = enableClassReminder,
            reminderMinutes = reminderMinutes,
            enablePersistentNotification = enablePersistentNotification,
            enableAutoMute = enableAutoMute,
            blurredWallpaperUri = blurredWallpaperUri
        )
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun setWallpaperUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(PreferencesKeys.WALLPAPER_URI)
                preferences.remove(PreferencesKeys.BLURRED_WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.WALLPAPER_URI] = uri
            }
        }
    }

    override suspend fun setTransparency(value: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSPARENCY] = value
        }
    }

    override suspend fun setFontStyle(style: AppFontStyle) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_STYLE] = style.name
        }
    }

    override suspend fun setDividerType(type: DividerType) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_TYPE] = type.name
        }
    }

    override suspend fun setDividerWidth(width: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_WIDTH] = width
        }
    }

    override suspend fun setDividerColor(color: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_COLOR] = color
        }
    }

    override suspend fun setDividerAlpha(alpha: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_ALPHA] = alpha
        }
    }

    override suspend fun setMaxDailySections(count: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DAILY_SECTIONS] = count
        }
    }

    override suspend fun setCourseItemHeight(height: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COURSE_ITEM_HEIGHT] = height
        }
    }

    override suspend fun setDefaultCourseDuration(duration: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] = duration
        }
    }

    override suspend fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        dataStore.edit { preferences ->
            val serialized = times.joinToString("|") { "${it.startTime},${it.endTime}" }
            preferences[PreferencesKeys.SECTION_TIMES] = serialized
        }
    }

    override suspend fun setCardCornerRadius(radius: Int) {
        dataStore.edit { it[PreferencesKeys.CARD_CORNER_RADIUS] = radius }
    }

    override suspend fun setCardAlpha(alpha: Float) {
        dataStore.edit { it[PreferencesKeys.CARD_ALPHA] = alpha }
    }

    override suspend fun setShowCourseIcons(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_COURSE_ICONS] = show }
    }

    override suspend fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode) {
        dataStore.edit { it[PreferencesKeys.WALLPAPER_MODE] = mode.name }
    }

    override suspend fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    override suspend fun setShowWeekend(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_WEEKEND] = show }
    }

    override suspend fun setShowSidebarTime(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_SIDEBAR_TIME] = show }
    }

    override suspend fun setShowSidebarIndex(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_SIDEBAR_INDEX] = show }
    }

    override suspend fun setHideNonThisWeek(hide: Boolean) {
        dataStore.edit { it[PreferencesKeys.HIDE_NON_THIS_WEEK] = hide }
    }

    override suspend fun setShowDateInHeader(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_DATE_IN_HEADER] = show }
    }

    override suspend fun setCurrentSemesterName(name: String) {
        dataStore.edit { it[PreferencesKeys.CURRENT_SEMESTER_NAME] = name }
    }

    override suspend fun setTotalWeeks(weeks: Int) {
        dataStore.edit { it[PreferencesKeys.TOTAL_WEEKS] = weeks }
    }

    override suspend fun setStartDateTimestamp(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.START_DATE_TIMESTAMP] = timestamp }
    }

    override suspend fun setEnableClassReminder(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_CLASS_REMINDER] = enable }
    }

    override suspend fun setReminderMinutes(minutes: Int) {
        dataStore.edit { it[PreferencesKeys.REMINDER_MINUTES] = minutes }
    }

    override suspend fun setEnablePersistentNotification(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_PERSISTENT_NOTIFICATION] = enable }
    }

    override suspend fun setEnableAutoMute(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_AUTO_MUTE] = enable }
    }

    override suspend fun setBlurredWallpaperUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(PreferencesKeys.BLURRED_WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.BLURRED_WALLPAPER_URI] = uri
            }
        }
    }

    override suspend fun generateBlurredWallpaper(uri: String?) {
        if (uri == null) {
            setBlurredWallpaperUri(null)
            return
        }
        val blurredUri = generateBlurredWallpaperInternal(uri)
        setBlurredWallpaperUri(blurredUri)
    }

    private suspend fun generateBlurredWallpaperInternal(uriString: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = decodeSampledBitmap(uri) ?: return@withContext null
                val blurredBitmap = fastBlur(bitmap, 20)
                val file = File(context.filesDir, "blurred_wallpaper.jpg")
                FileOutputStream(file).use { out ->
                    blurredBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                if (bitmap != blurredBitmap) bitmap.recycle()
                blurredBitmap.recycle()
                Uri.fromFile(file).toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        val targetMaxSize = 320
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val maxSize = maxOf(info.size.width, info.size.height)
                if (maxSize > targetMaxSize) {
                    val scale = maxSize.toFloat() / targetMaxSize.toFloat()
                    val targetWidth = (info.size.width / scale).toInt().coerceAtLeast(1)
                    val targetHeight = (info.size.height / scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val inSampleSize = calculateInSampleSize(options, targetMaxSize, targetMaxSize)
            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

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
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (y in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
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
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
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
        return bitmap
    }
}
