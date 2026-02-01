package com.dawncourse.core.domain.model

/**
 * 字体样式枚举
 */
enum class AppFontStyle {
    /** 跟随系统默认 */
    SYSTEM,
    /** 衬线字体 */
    SERIF,
    /** 等宽字体 */
    MONOSPACE
}

/**
 * 应用设置数据类
 *
 * 存储应用程序的全局偏好设置。
 *
 * @property dynamicColor 是否启用动态取色 (Material You)
 * @property wallpaperUri 自定义壁纸的 URI 字符串，null 表示未设置
 * @property transparency 背景透明度 (0.0 - 1.0)，0 表示不透明，1 表示全透明
 * @property fontStyle 全局字体样式偏好
 * @property dividerType 分割线类型
 * @property dividerWidthDp 分割线宽度 (dp)
 * @property dividerColor 分割线颜色 (Hex)
 * @property dividerAlpha 分割线透明度 (0.0 - 1.0)
 * @property courseItemHeightDp 课程项高度 (dp)
 * @property maxDailySections 每日最大节数
 * @property defaultCourseDuration 默认课程持续节数
 * @property sectionTimes 每节课的具体起止时间列表
 * @property cardCornerRadius 课程卡片圆角半径 (dp)
 * @property cardAlpha 课程卡片透明度 (0.0 - 1.0)
 * @property showCourseIcons 是否显示课程图标
 * @property wallpaperMode 壁纸缩放模式
 * @property themeMode 应用主题模式 (跟随系统/亮色/暗色)
 * @property showWeekend 是否显示周末
 * @property showSidebarTime 是否在侧边栏显示时间
 * @property showSidebarIndex 是否在侧边栏显示节次索引
 * @property hideNonThisWeek 是否隐藏非本周课程
 * @property showDateInHeader 是否在表头显示日期
 * @property currentSemesterName 当前学期名称
 * @property totalWeeks 当前学期总周数
 * @property startDateTimestamp 当前学期开始时间戳
 * @property enableClassReminder 是否启用上课提醒
 * @property reminderMinutes 提前提醒分钟数
 * @property enablePersistentNotification 是否启用常驻通知
 * @property enableAutoMute 是否启用自动静音
 * @property blurredWallpaperUri 模糊处理后的壁纸 URI（缓存）
 * @property backgroundBlur 背景模糊半径 (0-100dp)
 * @property backgroundBrightness 背景亮度 (0.0-1.0)
 */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val wallpaperUri: String? = null,
    /** 背景透明度 (0.0 - 1.0)，0 表示不透明，1 表示全透明 */
    val transparency: Float = 0f,
    val fontStyle: AppFontStyle = AppFontStyle.SYSTEM,
    
    // 分割线设置
    val dividerType: DividerType = DividerType.SOLID,
    val dividerWidthDp: Float = 1f,
    val dividerColor: String = "#E5E7EB",
    val dividerAlpha: Float = 1.0f,
    
    // 显示设置
    /** 课程项高度 (dp)，默认为 64dp 以获得更好的可见性 */
    val courseItemHeightDp: Int = 64,
    val maxDailySections: Int = 12,
    val defaultCourseDuration: Int = 2,
    
    // 时间设置
    val sectionTimes: List<SectionTime> = emptyList(),

    // 视觉设置
    val cardCornerRadius: Int = 16,
    val cardAlpha: Float = 0.9f,
    val showCourseIcons: Boolean = true,
    val wallpaperMode: WallpaperMode = WallpaperMode.CROP,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,

    // 课表显示设置
    val showWeekend: Boolean = true,
    val showSidebarTime: Boolean = true,
    val showSidebarIndex: Boolean = true,
    val hideNonThisWeek: Boolean = false,
    val showDateInHeader: Boolean = false,

    // 学期信息 (简易版缓存，主要数据在数据库)
    val currentSemesterName: String = "2025年春季学期",
    val totalWeeks: Int = 20,
    val startDateTimestamp: Long = 0L,
    
    // 通知与提醒
    val enableClassReminder: Boolean = false,
    val reminderMinutes: Int = 10,
    val enablePersistentNotification: Boolean = false,
    val enableAutoMute: Boolean = false,

    // 更新设置
    val ignoredUpdateVersion: Int = 0,

    // 性能缓存
    val blurredWallpaperUri: String? = null,

    // 自定义背景增强
    /** 背景模糊半径 (0 - 100 dp) */
    val backgroundBlur: Float = 0f,
    /** 背景亮度 (0.0 - 1.0) */
    val backgroundBrightness: Float = 1.0f
)

/**
 * 节次时间数据类
 *
 * @property startTime 开始时间 (格式 "HH:mm")
 * @property endTime 结束时间 (格式 "HH:mm")
 */
data class SectionTime(
    val startTime: String,
    val endTime: String
)

/**
 * 壁纸缩放模式枚举
 */
enum class WallpaperMode {
    /** 裁剪填充 (CenterCrop) */
    CROP, 
    /** 拉伸填充 (FillXY) */
    FILL
}

/**
 * 应用主题模式枚举
 */
enum class AppThemeMode {
    /** 跟随系统 */
    SYSTEM, 
    /** 强制亮色 */
    LIGHT, 
    /** 强制暗色 */
    DARK
}

/**
 * 分割线类型枚举
 */
enum class DividerType {
    /** 实线 */
    SOLID, 
    /** 虚线 */
    DASHED, 
    /** 点线 */
    DOTTED
}
