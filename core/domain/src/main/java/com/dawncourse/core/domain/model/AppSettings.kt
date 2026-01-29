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
 */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val wallpaperUri: String? = null,
    val transparency: Float = 0f, // 0.0 (opaque) to 1.0 (transparent)
    val fontStyle: AppFontStyle = AppFontStyle.SYSTEM
)
