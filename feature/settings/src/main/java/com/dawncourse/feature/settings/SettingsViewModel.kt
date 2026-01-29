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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    fun setTransparency(value: Float) {
        viewModelScope.launch {
            settingsRepository.setTransparency(value)
        }
    }

    fun setFontStyle(style: AppFontStyle) {
        viewModelScope.launch {
            settingsRepository.setFontStyle(style)
        }
    }

    fun setWallpaperUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setWallpaperUri(uri)
        }
    }
}
