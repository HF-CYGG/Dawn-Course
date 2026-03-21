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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.model.WebDavAutoSyncIntervalUnit
import com.dawncourse.core.domain.model.WebDavAutoSyncMode
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings DataStore 扩展属性
 *
 * 统一使用 Preferences DataStore 存储简单配置。
 */
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

    /** DataStore 实例 */
    private val dataStore = context.dataStore

    /**
     * DataStore Key 集合
     *
     * 注意：新增字段时需要同时维护读取与写入逻辑，避免默认值不一致。
     */
    private object PreferencesKeys {
        /** 是否启用动态取色 */
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        /** 壁纸 URI */
        val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        /** 背景透明度 */
        val TRANSPARENCY = floatPreferencesKey("transparency")
        /** 字体样式 */
        val FONT_STYLE = stringPreferencesKey("font_style")
        /** 分割线类型 */
        val DIVIDER_TYPE = stringPreferencesKey("divider_type")
        /** 分割线宽度 */
        val DIVIDER_WIDTH = floatPreferencesKey("divider_width_float")
        /** 分割线颜色 */
        val DIVIDER_COLOR = stringPreferencesKey("divider_color")
        /** 分割线透明度 */
        val DIVIDER_ALPHA = floatPreferencesKey("divider_alpha")
        /** 每日最大节次 */
        val MAX_DAILY_SECTIONS = intPreferencesKey("max_daily_sections")
        /** 默认课程时长 */
        val DEFAULT_COURSE_DURATION = intPreferencesKey("default_course_duration")
        /** 节次时间序列化字符串 */
        val SECTION_TIMES = stringPreferencesKey("section_times")
        /** 课程卡片高度 */
        val COURSE_ITEM_HEIGHT = intPreferencesKey("course_item_height")
        
        /** 课程卡片圆角半径 */
        val CARD_CORNER_RADIUS = intPreferencesKey("card_corner_radius")
        /** 课程卡片透明度 */
        val CARD_ALPHA = floatPreferencesKey("card_alpha")
        /** 是否显示课程图标 */
        val SHOW_COURSE_ICONS = booleanPreferencesKey("show_course_icons")
        /** 壁纸缩放模式 */
        val WALLPAPER_MODE = stringPreferencesKey("wallpaper_mode")
        /** 主题模式 */
        val THEME_MODE = stringPreferencesKey("theme_mode")
        /** 是否显示周末 */
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        /** 侧边栏时间显示 */
        val SHOW_SIDEBAR_TIME = booleanPreferencesKey("show_sidebar_time")
        /** 侧边栏节次索引显示 */
        val SHOW_SIDEBAR_INDEX = booleanPreferencesKey("show_sidebar_index")
        /** 隐藏非本周课程 */
        val HIDE_NON_THIS_WEEK = booleanPreferencesKey("hide_non_this_week")
        /** 表头显示日期 */
        val SHOW_DATE_IN_HEADER = booleanPreferencesKey("show_date_in_header")
        /** 当前学期名称（缓存） */
        val CURRENT_SEMESTER_NAME = stringPreferencesKey("current_semester_name")
        /** 当前学期总周数（缓存） */
        val TOTAL_WEEKS = intPreferencesKey("total_weeks")
        /** 当前学期开始时间戳（缓存） */
        val START_DATE_TIMESTAMP = androidx.datastore.preferences.core.longPreferencesKey("start_date_timestamp")
        /** 是否启用上课提醒 */
        val ENABLE_CLASS_REMINDER = booleanPreferencesKey("enable_class_reminder")
        /** 提前提醒分钟数 */
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        /** 是否启用常驻通知 */
        val ENABLE_PERSISTENT_NOTIFICATION = booleanPreferencesKey("enable_persistent_notification")
        /** 是否启用自动静音 */
        val ENABLE_AUTO_MUTE = booleanPreferencesKey("enable_auto_mute")
        /** 是否启用 WebDAV 自动同步 */
        val ENABLE_WEBDAV_AUTO_SYNC = booleanPreferencesKey("enable_webdav_auto_sync")
        /** WebDAV 自动同步模式 */
        val WEBDAV_AUTO_SYNC_MODE = stringPreferencesKey("webdav_auto_sync_mode")
        /** WebDAV 固定日期同步时间戳 */
        val WEBDAV_AUTO_SYNC_FIXED_AT = androidx.datastore.preferences.core.longPreferencesKey("webdav_auto_sync_fixed_at")
        /** WebDAV 间隔同步数值 */
        val WEBDAV_AUTO_SYNC_INTERVAL_VALUE = intPreferencesKey("webdav_auto_sync_interval_value")
        /** WebDAV 间隔同步单位 */
        val WEBDAV_AUTO_SYNC_INTERVAL_UNIT = stringPreferencesKey("webdav_auto_sync_interval_unit")
        /** 忽略的更新版本号 */
        val IGNORED_UPDATE_VERSION = intPreferencesKey("ignored_update_version")
        /** 上次导入地址 */
        val LAST_IMPORT_URL = stringPreferencesKey("last_import_url")
        /** 模糊壁纸 URI */
        val BLURRED_WALLPAPER_URI = stringPreferencesKey("blurred_wallpaper_uri")
        /** 背景模糊半径 */
        val BACKGROUND_BLUR = floatPreferencesKey("background_blur")
        /** 背景亮度 */
        val BACKGROUND_BRIGHTNESS = floatPreferencesKey("background_brightness")
    }

    /**
     * 设置流
     *
     * 将 DataStore 中的键值对映射为 [AppSettings]，并提供默认值兜底。
     */
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
        // 基础设置读取与默认值兜底
        val dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true
        val wallpaperUri = preferences[PreferencesKeys.WALLPAPER_URI]
        val transparency = preferences[PreferencesKeys.TRANSPARENCY] ?: 0f
        val fontStyleName = preferences[PreferencesKeys.FONT_STYLE] ?: AppFontStyle.SYSTEM.name
        val fontStyle = try {
            AppFontStyle.valueOf(fontStyleName)
        } catch (e: IllegalArgumentException) {
            AppFontStyle.SYSTEM
        }
        
        // 分割线配置读取
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
        
        // 节次时间序列化解析：start,end|start,end
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

        // 视觉与显示相关设置读取
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
        val enableWebDavAutoSync = preferences[PreferencesKeys.ENABLE_WEBDAV_AUTO_SYNC] ?: false
        val webDavAutoSyncModeName = preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_MODE] ?: WebDavAutoSyncMode.INTERVAL.name
        val webDavAutoSyncMode = try {
            WebDavAutoSyncMode.valueOf(webDavAutoSyncModeName)
        } catch (e: Exception) { WebDavAutoSyncMode.INTERVAL }
        val webDavAutoSyncFixedAt = preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_FIXED_AT] ?: 0L
        val webDavAutoSyncIntervalValue = preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_VALUE] ?: 24
        val webDavAutoSyncIntervalUnitName = preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_UNIT]
            ?: WebDavAutoSyncIntervalUnit.HOURS.name
        val webDavAutoSyncIntervalUnit = try {
            WebDavAutoSyncIntervalUnit.valueOf(webDavAutoSyncIntervalUnitName)
        } catch (e: Exception) { WebDavAutoSyncIntervalUnit.HOURS }
        val ignoredUpdateVersion = preferences[PreferencesKeys.IGNORED_UPDATE_VERSION] ?: 0
        val lastImportUrl = preferences[PreferencesKeys.LAST_IMPORT_URL]
        val blurredWallpaperUri = preferences[PreferencesKeys.BLURRED_WALLPAPER_URI]
        val backgroundBlur = preferences[PreferencesKeys.BACKGROUND_BLUR] ?: 0f
        val backgroundBrightness = preferences[PreferencesKeys.BACKGROUND_BRIGHTNESS] ?: 1.0f

        // 组合为 AppSettings 返回
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
            enableWebDavAutoSync = enableWebDavAutoSync,
            webDavAutoSyncMode = webDavAutoSyncMode,
            webDavAutoSyncFixedAt = webDavAutoSyncFixedAt,
            webDavAutoSyncIntervalValue = webDavAutoSyncIntervalValue,
            webDavAutoSyncIntervalUnit = webDavAutoSyncIntervalUnit,
            lastImportUrl = lastImportUrl,
            ignoredUpdateVersion = ignoredUpdateVersion,
            blurredWallpaperUri = blurredWallpaperUri,
            backgroundBlur = backgroundBlur,
            backgroundBrightness = backgroundBrightness
        )
    }

    /**
     * 设置动态取色开关
     */
    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR] = enabled
        }
    }

    /**
     * 设置壁纸 URI
     *
     * 当清空壁纸时，同时清理模糊缓存。
     */
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

    /**
     * 设置背景透明度
     */
    override suspend fun setTransparency(value: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSPARENCY] = value
        }
    }

    /**
     * 设置字体样式
     */
    override suspend fun setFontStyle(style: AppFontStyle) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_STYLE] = style.name
        }
    }

    /**
     * 设置分割线类型
     */
    override suspend fun setDividerType(type: DividerType) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_TYPE] = type.name
        }
    }

    /**
     * 设置分割线宽度
     */
    override suspend fun setDividerWidth(width: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_WIDTH] = width
        }
    }

    /**
     * 设置分割线颜色
     */
    override suspend fun setDividerColor(color: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_COLOR] = color
        }
    }

    /**
     * 设置分割线透明度
     */
    override suspend fun setDividerAlpha(alpha: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_ALPHA] = alpha
        }
    }

    /**
     * 设置每日最大节数
     */
    override suspend fun setMaxDailySections(count: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DAILY_SECTIONS] = count
        }
    }

    /**
     * 设置课程项高度
     */
    override suspend fun setCourseItemHeight(height: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COURSE_ITEM_HEIGHT] = height
        }
    }

    /**
     * 设置默认课程时长
     */
    override suspend fun setDefaultCourseDuration(duration: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] = duration
        }
    }

    /**
     * 设置节次时间并序列化存储
     */
    override suspend fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        dataStore.edit { preferences ->
            val serialized = times.joinToString("|") { "${it.startTime},${it.endTime}" }
            preferences[PreferencesKeys.SECTION_TIMES] = serialized
        }
    }

    /**
     * 设置课程卡片圆角半径
     */
    override suspend fun setCardCornerRadius(radius: Int) {
        dataStore.edit { it[PreferencesKeys.CARD_CORNER_RADIUS] = radius }
    }

    /**
     * 设置课程卡片透明度
     */
    override suspend fun setCardAlpha(alpha: Float) {
        dataStore.edit { it[PreferencesKeys.CARD_ALPHA] = alpha }
    }

    /**
     * 设置是否显示课程图标
     */
    override suspend fun setShowCourseIcons(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_COURSE_ICONS] = show }
    }

    /**
     * 设置壁纸缩放模式
     */
    override suspend fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode) {
        dataStore.edit { it[PreferencesKeys.WALLPAPER_MODE] = mode.name }
    }

    /**
     * 设置主题模式
     */
    override suspend fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    /**
     * 设置是否显示周末
     */
    override suspend fun setShowWeekend(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_WEEKEND] = show }
    }

    /**
     * 设置侧边栏时间显示
     */
    override suspend fun setShowSidebarTime(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_SIDEBAR_TIME] = show }
    }

    /**
     * 设置侧边栏节次索引显示
     */
    override suspend fun setShowSidebarIndex(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_SIDEBAR_INDEX] = show }
    }

    /**
     * 设置是否隐藏非本周课程
     */
    override suspend fun setHideNonThisWeek(hide: Boolean) {
        dataStore.edit { it[PreferencesKeys.HIDE_NON_THIS_WEEK] = hide }
    }

    /**
     * 设置是否在表头显示日期
     */
    override suspend fun setShowDateInHeader(show: Boolean) {
        dataStore.edit { it[PreferencesKeys.SHOW_DATE_IN_HEADER] = show }
    }

    /**
     * 设置当前学期名称（缓存）
     */
    override suspend fun setCurrentSemesterName(name: String) {
        dataStore.edit { it[PreferencesKeys.CURRENT_SEMESTER_NAME] = name }
    }

    /**
     * 设置当前学期总周数（缓存）
     */
    override suspend fun setTotalWeeks(weeks: Int) {
        dataStore.edit { it[PreferencesKeys.TOTAL_WEEKS] = weeks }
    }

    /**
     * 设置当前学期开始时间戳（缓存）
     */
    override suspend fun setStartDateTimestamp(timestamp: Long) {
        dataStore.edit { it[PreferencesKeys.START_DATE_TIMESTAMP] = timestamp }
    }

    /**
     * 设置是否启用上课提醒
     */
    override suspend fun setEnableClassReminder(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_CLASS_REMINDER] = enable }
    }

    /**
     * 清空所有设置
     */
    override suspend fun clearAllSettings() {
        dataStore.edit { it.clear() }
    }

    /**
     * 设置提前提醒时间
     */
    override suspend fun setReminderMinutes(minutes: Int) {
        dataStore.edit { it[PreferencesKeys.REMINDER_MINUTES] = minutes }
    }

    /**
     * 设置是否启用常驻通知
     */
    override suspend fun setEnablePersistentNotification(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_PERSISTENT_NOTIFICATION] = enable }
    }

    /**
     * 设置是否启用自动静音
     */
    override suspend fun setEnableAutoMute(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_AUTO_MUTE] = enable }
    }

    /**
     * 设置是否启用 WebDAV 自动同步
     */
    override suspend fun setEnableWebDavAutoSync(enable: Boolean) {
        dataStore.edit { it[PreferencesKeys.ENABLE_WEBDAV_AUTO_SYNC] = enable }
    }

    /**
     * 设置 WebDAV 自动同步模式
     */
    override suspend fun setWebDavAutoSyncMode(mode: WebDavAutoSyncMode) {
        dataStore.edit { it[PreferencesKeys.WEBDAV_AUTO_SYNC_MODE] = mode.name }
    }

    /**
     * 设置 WebDAV 固定日期同步时间
     */
    override suspend fun setWebDavAutoSyncFixedAt(timestamp: Long) {
        dataStore.edit { preferences ->
            if (timestamp <= 0L) {
                preferences.remove(PreferencesKeys.WEBDAV_AUTO_SYNC_FIXED_AT)
            } else {
                preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_FIXED_AT] = timestamp
            }
        }
    }

    /**
     * 设置 WebDAV 间隔同步数值
     */
    override suspend fun setWebDavAutoSyncIntervalValue(value: Int) {
        dataStore.edit { it[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_VALUE] = value }
    }

    /**
     * 设置 WebDAV 间隔同步单位
     */
    override suspend fun setWebDavAutoSyncIntervalUnit(unit: WebDavAutoSyncIntervalUnit) {
        dataStore.edit { it[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_UNIT] = unit.name }
    }

    /**
     * 设置忽略的更新版本号
     */
    override suspend fun setIgnoredUpdateVersion(versionCode: Int) {
        dataStore.edit { it[PreferencesKeys.IGNORED_UPDATE_VERSION] = versionCode }
    }

    /**
     * 设置上次导入地址
     *
     * 传入空字符串时会清空记录。
     */
    override suspend fun setLastImportUrl(url: String) {
        dataStore.edit { preferences ->
            if (url.isBlank()) {
                preferences.remove(PreferencesKeys.LAST_IMPORT_URL)
            } else {
                preferences[PreferencesKeys.LAST_IMPORT_URL] = url
            }
        }
    }

    /**
     * 设置模糊壁纸 URI
     */
    override suspend fun setBlurredWallpaperUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(PreferencesKeys.BLURRED_WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.BLURRED_WALLPAPER_URI] = uri
            }
        }
    }

    /**
     * 设置背景模糊半径
     */
    override suspend fun setBackgroundBlur(blur: Float) {
        dataStore.edit { it[PreferencesKeys.BACKGROUND_BLUR] = blur }
    }

    /**
     * 设置背景亮度
     */
    override suspend fun setBackgroundBrightness(brightness: Float) {
        dataStore.edit { it[PreferencesKeys.BACKGROUND_BRIGHTNESS] = brightness }
    }

    /**
     * 批量覆盖设置
     *
     * 用于云端恢复场景，一次性写入所有字段。
     */
    override suspend fun setAllSettings(settings: AppSettings) {
        // 为跨模块属性提前解包，避免 smart cast 失效
        val wallpaperUri = settings.wallpaperUri
        val lastImportUrl = settings.lastImportUrl
        val blurredWallpaperUri = settings.blurredWallpaperUri
        dataStore.edit { preferences ->
            // 注意：一次性写入所有配置，确保恢复时不存在“部分更新”的中间态
            preferences[PreferencesKeys.DYNAMIC_COLOR] = settings.dynamicColor
            if (wallpaperUri == null) {
                preferences.remove(PreferencesKeys.WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.WALLPAPER_URI] = wallpaperUri
            }
            preferences[PreferencesKeys.TRANSPARENCY] = settings.transparency
            preferences[PreferencesKeys.FONT_STYLE] = settings.fontStyle.name
            preferences[PreferencesKeys.DIVIDER_TYPE] = settings.dividerType.name
            preferences[PreferencesKeys.DIVIDER_WIDTH] = settings.dividerWidthDp
            preferences[PreferencesKeys.DIVIDER_COLOR] = settings.dividerColor
            preferences[PreferencesKeys.DIVIDER_ALPHA] = settings.dividerAlpha
            preferences[PreferencesKeys.COURSE_ITEM_HEIGHT] = settings.courseItemHeightDp
            preferences[PreferencesKeys.MAX_DAILY_SECTIONS] = settings.maxDailySections
            preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] = settings.defaultCourseDuration
            val sectionTimes = settings.sectionTimes.joinToString("|") { "${it.startTime},${it.endTime}" }
            preferences[PreferencesKeys.SECTION_TIMES] = sectionTimes
            preferences[PreferencesKeys.CARD_CORNER_RADIUS] = settings.cardCornerRadius
            preferences[PreferencesKeys.CARD_ALPHA] = settings.cardAlpha
            preferences[PreferencesKeys.SHOW_COURSE_ICONS] = settings.showCourseIcons
            preferences[PreferencesKeys.WALLPAPER_MODE] = settings.wallpaperMode.name
            preferences[PreferencesKeys.THEME_MODE] = settings.themeMode.name
            preferences[PreferencesKeys.SHOW_WEEKEND] = settings.showWeekend
            preferences[PreferencesKeys.SHOW_SIDEBAR_TIME] = settings.showSidebarTime
            preferences[PreferencesKeys.SHOW_SIDEBAR_INDEX] = settings.showSidebarIndex
            preferences[PreferencesKeys.HIDE_NON_THIS_WEEK] = settings.hideNonThisWeek
            preferences[PreferencesKeys.SHOW_DATE_IN_HEADER] = settings.showDateInHeader
            preferences[PreferencesKeys.CURRENT_SEMESTER_NAME] = settings.currentSemesterName
            preferences[PreferencesKeys.TOTAL_WEEKS] = settings.totalWeeks
            preferences[PreferencesKeys.START_DATE_TIMESTAMP] = settings.startDateTimestamp
            preferences[PreferencesKeys.ENABLE_CLASS_REMINDER] = settings.enableClassReminder
            preferences[PreferencesKeys.REMINDER_MINUTES] = settings.reminderMinutes
            preferences[PreferencesKeys.ENABLE_PERSISTENT_NOTIFICATION] = settings.enablePersistentNotification
            preferences[PreferencesKeys.ENABLE_AUTO_MUTE] = settings.enableAutoMute
            preferences[PreferencesKeys.ENABLE_WEBDAV_AUTO_SYNC] = settings.enableWebDavAutoSync
            preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_MODE] = settings.webDavAutoSyncMode.name
            if (settings.webDavAutoSyncFixedAt <= 0L) {
                preferences.remove(PreferencesKeys.WEBDAV_AUTO_SYNC_FIXED_AT)
            } else {
                preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_FIXED_AT] = settings.webDavAutoSyncFixedAt
            }
            preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_VALUE] = settings.webDavAutoSyncIntervalValue
            preferences[PreferencesKeys.WEBDAV_AUTO_SYNC_INTERVAL_UNIT] = settings.webDavAutoSyncIntervalUnit.name
            preferences[PreferencesKeys.IGNORED_UPDATE_VERSION] = settings.ignoredUpdateVersion
            if (lastImportUrl.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.LAST_IMPORT_URL)
            } else {
                preferences[PreferencesKeys.LAST_IMPORT_URL] = lastImportUrl
            }
            if (blurredWallpaperUri == null) {
                preferences.remove(PreferencesKeys.BLURRED_WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.BLURRED_WALLPAPER_URI] = blurredWallpaperUri
            }
            preferences[PreferencesKeys.BACKGROUND_BLUR] = settings.backgroundBlur
            preferences[PreferencesKeys.BACKGROUND_BRIGHTNESS] = settings.backgroundBrightness
        }
    }

    /**
     * 生成模糊壁纸并更新缓存 URI
     */
    override suspend fun generateBlurredWallpaper(uri: String?) {
        if (uri == null) {
            setBlurredWallpaperUri(null)
            return
        }
        val blurredUri = generateBlurredWallpaperInternal(uri)
        setBlurredWallpaperUri(blurredUri)
    }

    /**
     * 生成模糊壁纸的内部实现
     *
     * 1. 解析 URI
     * 2. 采样解码图片 (避免 OOM)
     * 3. 应用快速模糊算法
     * 4. 保存到私有文件目录
     * 5. 返回文件 URI
     */
    private suspend fun generateBlurredWallpaperInternal(uriString: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                // 解码图片，自动处理采样率以防内存溢出
                val bitmap = decodeSampledBitmap(uri) ?: return@withContext null
                // 应用模糊算法，半径 20
                val blurredBitmap = fastBlur(bitmap, 20)
                // 保存到 files/blurred_wallpaper.jpg
                val file = File(context.filesDir, "blurred_wallpaper.jpg")
                FileOutputStream(file).use { out ->
                    blurredBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                // 回收原图
                if (bitmap != blurredBitmap) bitmap.recycle()
                blurredBitmap.recycle()
                Uri.fromFile(file).toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 采样解码图片
     *
     * 计算合适的采样率 (inSampleSize) 并解码图片，确保图片尺寸不超过 targetMaxSize (320px)，
     * 以减少内存占用并加快模糊处理速度。
     */
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

    /**
     * 计算采样率
     *
     * 根据目标宽高计算 power-of-2 的采样率。
     */
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

    /**
     * 快速模糊算法 (Stack Blur)
     *
     * 一种高性能的模糊算法，近似于高斯模糊但速度更快。
     * 来源：StackBlur by Mario Klingemann
     *
     * @param sentBitmap 原始位图
     * @param radius 模糊半径
     * @return 模糊后的新位图
     */
    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) {
            // radius < 1 表示不需要模糊，直接返回原图，避免返回 null 导致崩溃。
            return sentBitmap
        }

        // sentBitmap.config 在大多数情况下是稳定可用的，但为了兼容极端/异常来源的 Bitmap，
        // 这里通过 runCatching 做一次兜底，避免 copy 因 config 异常导致崩溃。
        val config = runCatching { sentBitmap.config }.getOrNull() ?: Bitmap.Config.ARGB_8888
        val bitmap = sentBitmap.copy(config, true)

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
