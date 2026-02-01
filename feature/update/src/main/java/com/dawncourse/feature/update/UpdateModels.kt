package com.dawncourse.feature.update

import androidx.compose.ui.graphics.Color
import com.google.gson.annotations.SerializedName

/**
 * 更新类型枚举
 */
enum class UpdateType(val label: String, val colorHex: Long) {
    @SerializedName("standard")
    STANDARD("标准更新", 0xFF2196F3), // Blue

    @SerializedName("bugfix")
    BUG_FIX("问题修复", 0xFF4CAF50), // Green

    @SerializedName("security")
    SECURITY("漏洞修复", 0xFFF44336), // Red

    @SerializedName("feature")
    FEATURE("功能更新", 0xFF9C27B0), // Purple

    @SerializedName("major")
    MAJOR("重要更新", 0xFFFF9800); // Orange

    fun getColor(): Color = Color(colorHex)
}

/**
 * 更新信息实体类
 */
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    
    // 映射 JSON 中的 forceUpdate
    @SerializedName("forceUpdate") val isForce: Boolean = false,
    
    @SerializedName("title") val title: String?,
    
    // 映射 JSON 中的 updateContent
    @SerializedName("updateContent") val content: String?,
    
    @SerializedName("downloadUrl") val downloadUrl: String,
    
    // 映射 JSON 中的 date
    @SerializedName("date") val releaseDate: String?,
    
    @SerializedName("sha256") val sha256: String? = null,
    
    // 新增更新类型，默认为标准更新
    @SerializedName("type") val type: UpdateType = UpdateType.STANDARD
)
