package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面的 ViewModel
 *
 * 负责管理和持久化应用程序的设置选项。
 * 通过 [SettingsRepository] 与数据层交互，使用 StateFlow 暴露当前的设置状态。
 *
 * @property settingsRepository 设置数据仓库，用于存取设置数据
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * 当前的应用设置状态流
     *
     * 包含所有个性化配置项（如动态取色、透明度、壁纸等）。
     * 初始值为默认配置，后续会根据 DataStore 中的数据自动更新。
     */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /**
     * 设置是否启用动态取色 (Material You)
     *
     * @param enabled true 表示启用，false 表示禁用
     */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    /**
     * 设置背景透明度
     *
     * @param value 透明度值，范围 0.0 - 1.0
     */
    fun setTransparency(value: Float) {
        viewModelScope.launch {
            settingsRepository.setTransparency(value)
        }
    }

    /**
     * 设置应用字体样式
     *
     * @param style 选定的字体样式枚举 [AppFontStyle]
     */
    fun setFontStyle(style: AppFontStyle) {
        viewModelScope.launch {
            settingsRepository.setFontStyle(style)
        }
    }

    /**
     * 设置自定义壁纸 URI
     *
     * @param uri 壁纸图片的 URI 字符串，若为 null 则清除壁纸
     */
    fun setWallpaperUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setWallpaperUri(uri)
        }
    }
}
