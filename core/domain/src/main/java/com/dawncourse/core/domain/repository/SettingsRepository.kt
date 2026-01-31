package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * 设置数据仓库接口
 *
 * 定义了对应用设置的读取和修改操作。
 */
interface SettingsRepository {
    /**
     * 设置状态流
     *
     * 实时发射最新的应用设置对象 [AppSettings]。
     */
    val settings: Flow<AppSettings>

    /**
     * 设置是否启用动态取色
     *
     * @param enabled 是否启用
     */
    suspend fun setDynamicColor(enabled: Boolean)
    
    /**
     * 设置自定义壁纸 URI
     *
     * @param uri 壁纸文件的 URI 字符串，传入 null 则清除壁纸
     */
    suspend fun setWallpaperUri(uri: String?)

    /**
     * 生成并缓存模糊后的壁纸
     *
     * @param uri 原始壁纸的 URI
     */
    suspend fun generateBlurredWallpaper(uri: String?)
    
    /**
     * 设置背景透明度
     *
     * @param value 透明度值 (0.0 - 1.0)
     */
    suspend fun setTransparency(value: Float)
    
    /**
     * 设置字体样式
     *
     * @param style 字体样式枚举
     */
    suspend fun setFontStyle(style: AppFontStyle)
    
    /**
     * 设置分割线类型
     *
     * @param type 分割线类型枚举
     */
    suspend fun setDividerType(type: com.dawncourse.core.domain.model.DividerType)

    /**
     * 设置分割线宽度
     *
     * @param width 宽度 (dp)
     */
    suspend fun setDividerWidth(width: Float)

    /**
     * 设置分割线颜色
     *
     * @param color 颜色 Hex 字符串
     */
    suspend fun setDividerColor(color: String)

    /**
     * 设置分割线透明度
     *
     * @param alpha 透明度 (0.0 - 1.0)
     */
    suspend fun setDividerAlpha(alpha: Float)

    /**
     * 设置每日最大节数
     *
     * @param count 节数
     */
    suspend fun setMaxDailySections(count: Int)

    /**
     * 设置课程项高度
     *
     * @param height 高度 (dp)
     */
    suspend fun setCourseItemHeight(height: Int)

    /**
     * 设置默认课程时长
     *
     * @param duration 节数
     */
    suspend fun setDefaultCourseDuration(duration: Int)
    
    /**
     * 设置每节课的具体时间
     *
     * @param times 时间列表
     */
    suspend fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>)

    /**
     * 设置课程卡片圆角半径
     *
     * @param radius 半径 (dp)
     */
    suspend fun setCardCornerRadius(radius: Int)

    /**
     * 设置课程卡片透明度
     *
     * @param alpha 透明度 (0.0 - 1.0)
     */
    suspend fun setCardAlpha(alpha: Float)

    /**
     * 设置是否显示课程图标
     *
     * @param show 是否显示
     */
    suspend fun setShowCourseIcons(show: Boolean)

    /**
     * 设置壁纸缩放模式
     *
     * @param mode 缩放模式枚举
     */
    suspend fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode)

    /**
     * 设置应用主题模式
     *
     * @param mode 主题模式枚举
     */
    suspend fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode)

    /**
     * 设置是否显示周末
     *
     * @param show 是否显示
     */
    suspend fun setShowWeekend(show: Boolean)

    /**
     * 设置背景模糊半径
     *
     * @param blur 半径 (dp)
     */
    suspend fun setBackgroundBlur(blur: Float)

    /**
     * 设置背景亮度
     *
     * @param brightness 亮度 (0.0 - 1.0)
     */
    suspend fun setBackgroundBrightness(brightness: Float)

    /**
     * 设置是否在侧边栏显示时间
     *
     * @param show 是否显示
     */
    suspend fun setShowSidebarTime(show: Boolean)

    /**
     * 设置是否在侧边栏显示节次索引
     *
     * @param show 是否显示
     */
    suspend fun setShowSidebarIndex(show: Boolean)

    /**
     * 设置是否隐藏非本周课程
     *
     * @param hide 是否隐藏
     */
    suspend fun setHideNonThisWeek(hide: Boolean)

    /**
     * 设置是否在表头显示日期
     *
     * @param show 是否显示
     */
    suspend fun setShowDateInHeader(show: Boolean)

    /**
     * 设置当前学期名称（缓存）
     *
     * @param name 学期名称
     */
    suspend fun setCurrentSemesterName(name: String)

    /**
     * 设置当前学期总周数（缓存）
     *
     * @param weeks 总周数
     */
    suspend fun setTotalWeeks(weeks: Int)

    /**
     * 设置当前学期开始时间戳（缓存）
     *
     * @param timestamp 时间戳
     */
    suspend fun setStartDateTimestamp(timestamp: Long)

    /**
     * 设置是否启用上课提醒
     *
     * @param enable 是否启用
     */
    suspend fun setEnableClassReminder(enable: Boolean)

    /**
     * 设置提前提醒时间
     *
     * @param minutes 分钟数
     */
    suspend fun setReminderMinutes(minutes: Int)

    /**
     * 设置是否启用常驻通知
     *
     * @param enable 是否启用
     */
    suspend fun setEnablePersistentNotification(enable: Boolean)

    /**
     * 设置是否启用自动静音
     *
     * @param enable 是否启用
     */
    suspend fun setEnableAutoMute(enable: Boolean)
    
    /**
     * 设置模糊处理后的壁纸 URI
     *
     * @param uri URI 字符串
     */
    suspend fun setBlurredWallpaperUri(uri: String?)
}
