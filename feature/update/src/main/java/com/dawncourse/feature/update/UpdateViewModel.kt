package com.dawncourse.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.util.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 更新检查 UI 状态
 * 描述更新检查过程中的各种状态
 */
sealed class UpdateUiState {
    /** 空闲状态，未开始检查或检查已结束 */
    data object Idle : UpdateUiState()
    /** 正在检查更新 */
    data object Checking : UpdateUiState()
    /** 发现新版本可用 */
    data class Available(val updateInfo: UpdateInfo) : UpdateUiState()
    /** 已经是最新版本（仅在手动检查时显示详情） */
    data class VersionInfo(val updateInfo: UpdateInfo) : UpdateUiState()
    /** 无可用更新（保留状态，暂未使用） */
    data class NoUpdate(val currentVersion: String) : UpdateUiState()
    /** 检查过程出错 */
    data class Error(val message: String) : UpdateUiState()
}

/**
 * 更新相关的一次性事件
 * 用于 UI 显示 Toast 等提示
 */
sealed interface UpdateEvent {
    data class ShowToast(val message: String) : UpdateEvent
}

/**
 * 根据入口决定更新检查失败后的安全 UI 状态。
 * 自动检查不能阻塞首页，手动检查只显示固定文案，避免泄露网络异常细节。
 */
internal fun updateFailureState(isManual: Boolean): UpdateUiState =
    if (isManual) {
        UpdateUiState.Error(UPDATE_CHECK_FAILURE_MESSAGE)
    } else {
        UpdateUiState.Idle
    }

/** 更新检查失败的通用文案，不携带 Throwable、地址或凭据。 */
private const val UPDATE_CHECK_FAILURE_MESSAGE = "检查更新失败，请检查网络或稍后重试"

/** 忽略版本设置写入失败的通用提示。 */
private const val IGNORE_VERSION_FAILURE_MESSAGE = "忽略版本设置未保存，请稍后重试"

/**
 * 更新模块 ViewModel
 * 负责管理更新检查的逻辑和 UI 状态
 *
 * 主要职责：
 * 1. 调用 Repository 检查更新
 * 2. 结合本地版本号和配置（忽略版本），判断是否显示更新弹窗
 * 3. 处理手动检查和自动检查的不同逻辑
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // UI 状态流
    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    // 一次性事件流
    private val _eventFlow = MutableSharedFlow<UpdateEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    /**
     * 检查更新
     * @param isManual 是否为手动触发检查（手动触发时，即使无更新也会提示）
     * @param currentVersionCode 当前应用版本号
     */
    fun checkUpdate(isManual: Boolean = false, currentVersionCode: Long) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            val result = runSuspendCatching { repository.checkUpdate() }.getOrElse {
                _uiState.value = updateFailureState(isManual)
                return@launch
            }
            val settings = runSuspendCatching { settingsRepository.settings.first() }.getOrElse {
                _uiState.value = updateFailureState(isManual)
                return@launch
            }
            val ignoredVersion = settings.ignoredUpdateVersion

            result.onSuccess { info ->
                // 判定逻辑：
                // 1. 远程版本 > 本地版本
                // 2. 且 (是手动检查 OR (不是手动检查 且 没被用户跳过))
                // 3. 强制更新会无视跳过逻辑
                val shouldShow = info.versionCode > currentVersionCode &&
                    (isManual || info.isForce || info.versionCode != ignoredVersion)

                if (shouldShow) {
                    // 发现新版本，显示更新弹窗
                    _uiState.value = UpdateUiState.Available(info)
                } else if (isManual) {
                    // 手动检查但没更新，显示版本详情弹窗
                    _uiState.value = UpdateUiState.VersionInfo(info)
                } else {
                    // 自动检查且无更新（或已忽略），保持空闲
                    _uiState.value = UpdateUiState.Idle
                }
            }.onFailure {
                // Repository 失败仅显示固定提示，不将异常细节传入 UI。
                _uiState.value = updateFailureState(isManual)
            }
        }
    }

    /**
     * 忽略指定版本
     * 用户点击“忽略此版本”后调用，将该版本号记录到设置中，不再提示
     */
    fun ignoreVersion(versionCode: Int) {
        viewModelScope.launch {
            val result = runSuspendCatching {
                settingsRepository.setIgnoredUpdateVersion(versionCode)
            }
            if (result.isFailure) {
                _eventFlow.emit(UpdateEvent.ShowToast(IGNORE_VERSION_FAILURE_MESSAGE))
            }
            _uiState.value = UpdateUiState.Idle
        }
    }

    /**
     * 关闭弹窗
     * 重置状态为空闲
     */
    fun dismissDialog() {
        _uiState.value = UpdateUiState.Idle
    }
}
