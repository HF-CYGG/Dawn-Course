package com.dawncourse.feature.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val updateInfo: UpdateInfo) : UpdateUiState()
    data class VersionInfo(val updateInfo: UpdateInfo) : UpdateUiState()
    data class NoUpdate(val currentVersion: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

sealed interface UpdateEvent {
    data class ShowToast(val message: String) : UpdateEvent
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<UpdateEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    fun checkUpdate(isManual: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            val result = repository.checkUpdate()
            val settings = settingsRepository.settings.first()
            val ignoredVersion = settings.ignoredUpdateVersion
            
            result.onSuccess { info ->
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                    
                    // 判定逻辑：
                    // 1. 远程版本 > 本地版本
                    // 2. 且 (是手动检查 OR (不是手动检查 且 没被用户跳过))
                    // 3. 强制更新会无视跳过逻辑
                    val shouldShow = info.versionCode > currentVersionCode && 
                                     (isManual || info.isForce || info.versionCode != ignoredVersion)

                    if (shouldShow) {
                        _uiState.value = UpdateUiState.Available(info)
                    } else if (isManual) {
                        // 手动检查但没更新，显示版本详情弹窗
                        _uiState.value = UpdateUiState.VersionInfo(info)
                    } else {
                        _uiState.value = UpdateUiState.Idle
                    }
                } catch (e: Exception) {
                    _uiState.value = UpdateUiState.Error(e.message ?: "Unknown error")
                }
            }.onFailure {
                val errorMsg = when {
                    it.message?.contains("Cleartext HTTP traffic") == true -> 
                        "系统限制了明文流量，请联系开发者适配 (Cleartext Error)"
                    it is java.net.UnknownHostException -> 
                        "无法连接服务器，请检查网络 (DNS Error)"
                    it is java.net.SocketTimeoutException -> 
                        "连接超时，请重试 (Timeout)"
                    else -> "检查失败: ${it.message}"
                }
                
                if (isManual) {
                    _eventFlow.emit(UpdateEvent.ShowToast(errorMsg))
                }
                _uiState.value = UpdateUiState.Error(errorMsg)
            }
        }
    }

    fun ignoreVersion(versionCode: Int) {
        viewModelScope.launch {
            settingsRepository.setIgnoredUpdateVersion(versionCode)
            _uiState.value = UpdateUiState.Idle
        }
    }

    fun dismissDialog() {
        _uiState.value = UpdateUiState.Idle
    }
}
