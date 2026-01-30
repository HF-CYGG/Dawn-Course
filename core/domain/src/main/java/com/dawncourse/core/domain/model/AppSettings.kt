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
 * @property dividerWidth 分割线宽度 (px)
 * @property dividerColor 分割线颜色 (Hex)
 * @property dividerAlpha 分割线透明度 (0.0 - 1.0)
 */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val wallpaperUri: String? = null,
    val transparency: Float = 0f, // 0.0 (opaque) to 1.0 (transparent)
    val fontStyle: AppFontStyle = AppFontStyle.SYSTEM,
    
    // Divider Settings
    val dividerType: DividerType = DividerType.SOLID,
    val dividerWidth: Int = 1,
    val dividerColor: String = "#E5E7EB",
    val dividerAlpha: Float = 1.0f,
    
    // Course Settings
    val maxDailySections: Int = 12,
    val defaultCourseDuration: Int = 2,
    
    // Time Settings
    val sectionTimes: List<SectionTime> = emptyList()
)

data class SectionTime(
    val startTime: String, // "HH:mm"
    val endTime: String    // "HH:mm"
)

enum class DividerType {
    SOLID, DASHED, DOTTED
}
