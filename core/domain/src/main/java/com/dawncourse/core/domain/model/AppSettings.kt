package com.dawncourse.core.domain.model

enum class AppFontStyle {
    SYSTEM,
    SERIF,
    MONOSPACE
}

data class AppSettings(
    val dynamicColor: Boolean = true,
    val wallpaperUri: String? = null,
    val transparency: Float = 0f, // 0.0 (opaque) to 1.0 (transparent)
    val fontStyle: AppFontStyle = AppFontStyle.SYSTEM
)
