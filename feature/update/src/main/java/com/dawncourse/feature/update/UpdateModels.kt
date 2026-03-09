package com.dawncourse.feature.update

import androidx.compose.ui.graphics.Color
import com.google.gson.annotations.SerializedName

/**
 * 更新类型枚举
 * 用于区分不同类型的应用更新，并在 UI 上显示不同的标签和颜色
 * @property label 显示在 UI 上的标签文本
 * @property colorHex 对应的颜色十六进制值
 */
enum class UpdateType(val label: String, val colorHex: Long) {
    /** 标准更新：常规的功能迭代和优化 */
    @SerializedName("standard")
    STANDARD("标准更新", 0xFF2196F3), // Blue

    /** 问题修复：主要修复已知 Bug */
    @SerializedName("bugfix")
    BUG_FIX("问题修复", 0xFF4CAF50), // Green

    /** 安全更新：修复安全漏洞，建议用户尽快更新 */
    @SerializedName("security")
    SECURITY("漏洞修复", 0xFFF44336), // Red

    /** 功能更新：引入了新的功能特性 */
    @SerializedName("feature")
    FEATURE("功能更新", 0xFF9C27B0), // Purple

    /** 重大更新：包含架构调整或大量新功能 */
    @SerializedName("major")
    MAJOR("重要更新", 0xFFFF9800); // Orange

    /**
     * 获取对应的 Compose Color 对象
     * @return Color 对象
     */
    fun getColor(): Color = Color(colorHex)
}

/**
 * 更新信息实体类
 * 用于解析服务端返回的版本信息 JSON
 *
 * @property versionCode 版本号（整数），用于比较版本新旧
 * @property versionName 版本名称（字符串），用于展示
 * @property isForce 是否强制更新，如果是，用户无法跳过
 * @property title 更新标题
 * @property content 更新内容详情
 * @property downloadUrl 安装包下载地址
 * @property releaseDate 发布日期
 * @property sha256 文件校验值（可选）
 * @property type 更新类型，默认为标准更新
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
