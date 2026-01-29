package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setWallpaperUri(uri: String?)
    suspend fun setTransparency(value: Float)
    suspend fun setFontStyle(style: AppFontStyle)
}
