package com.dawncourse.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 主界面 UI 状态
 */
sealed interface MainUiState {
    data object Loading : MainUiState
    data class Success(val settings: AppSettings) : MainUiState
}

/**
 * 主 Activity 的 ViewModel
 *
 * 负责为 MainActivity 提供应用级别的状态，例如全局主题设置。
 *
 * @property settingsRepository 设置仓库，用于获取应用设置
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    /**
     * 全局应用设置状态流
     *
     * 用于在应用启动时应用用户偏好的主题（如动态取色、字体等）。
     * 使用 MainUiState 包装，以便在数据加载完成前保持启动画面。
     */
    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MainUiState.Loading
        )
}
