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
     */
    suspend fun setDynamicColor(enabled: Boolean)
    
    /**
     * 设置自定义壁纸 URI
     */
    suspend fun setWallpaperUri(uri: String?)
    
    /**
     * 设置背景透明度
     */
    suspend fun setTransparency(value: Float)
    
    /**
     * 设置字体样式
     */
    suspend fun setFontStyle(style: AppFontStyle)
    
    suspend fun setDividerType(type: com.dawncourse.core.domain.model.DividerType)
    suspend fun setDividerWidth(width: Int)
    suspend fun setDividerColor(color: String)
    suspend fun setDividerAlpha(alpha: Float)
}
