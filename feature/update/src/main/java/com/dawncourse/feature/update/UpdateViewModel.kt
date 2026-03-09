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
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
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
     */
    fun checkUpdate(isManual: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            val result = repository.checkUpdate()
            val settings = settingsRepository.settings.first()
            val ignoredVersion = settings.ignoredUpdateVersion
            
            result.onSuccess { info ->
                try {
                    // 获取当前应用版本号
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                    
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
                } catch (e: Exception) {
                    _uiState.value = UpdateUiState.Error(e.message ?: "Unknown error")
                }
            }.onFailure {
                // 错误处理逻辑
                val errorMsg = when {
                    it.message?.contains("Cleartext HTTP traffic") == true -> 
                        "系统限制了明文流量，请联系开发者适配 (Cleartext Error)"
                    it is java.net.UnknownHostException -> 
                        "无法连接服务器，请检查网络 (DNS Error)"
                    it is java.net.SocketTimeoutException -> 
                        "连接超时，请重试 (Timeout)"
                    else -> "检查失败: ${it.message}"
                }
                
                // 只有手动检查才显示错误弹窗，自动检查失败静默处理
                if (isManual) {
                    _uiState.value = UpdateUiState.Error(errorMsg)
                } else {
                    _uiState.value = UpdateUiState.Idle
                }
            }
        }
    }

    /**
     * 忽略指定版本
     * 用户点击“忽略此版本”后调用，将该版本号记录到设置中，不再提示
     */
    fun ignoreVersion(versionCode: Int) {
        viewModelScope.launch {
            settingsRepository.setIgnoredUpdateVersion(versionCode)
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
