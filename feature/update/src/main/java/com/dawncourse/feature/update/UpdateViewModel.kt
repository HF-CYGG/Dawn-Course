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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    /** 正在应用内下载更新包 */
    data class Downloading(
        val updateInfo: UpdateInfo,
        val progressPercent: Int?
    ) : UpdateUiState()
    /** 更新包已完成全部校验，可以交给系统安装器 */
    data class ReadyToInstall(
        val updateInfo: UpdateInfo,
        val updatePackage: DownloadedUpdatePackage
    ) : UpdateUiState()
    /** APK 已交给未知来源授权页或系统安装器，防止配置变化重复拉起。 */
    data class InstallHandoff(
        val updateInfo: UpdateInfo,
        val updatePackage: DownloadedUpdatePackage,
        val attemptId: Long,
        val phase: InstallHandoffPhase
    ) : UpdateUiState()
    /** 已经是最新版本（仅在手动检查时显示详情） */
    data class VersionInfo(val updateInfo: UpdateInfo) : UpdateUiState()
    /** 无可用更新（保留状态，暂未使用） */
    data class NoUpdate(val currentVersion: String) : UpdateUiState()
    /** 检查过程出错 */
    data class Error(val message: String) : UpdateUiState()
}

/** 外部安装流程阶段；由 attempt ID 保证过期回调不能推进新流程。 */
enum class InstallHandoffPhase {
    AWAITING_PERMISSION,
    INSTALLER_PROMPT_LAUNCHED
}

internal fun beginInstallHandoff(
    state: UpdateUiState,
    updatePackage: DownloadedUpdatePackage,
    attemptId: Long,
    phase: InstallHandoffPhase
): UpdateUiState.InstallHandoff? {
    val readyState = state as? UpdateUiState.ReadyToInstall ?: return null
    if (readyState.updatePackage != updatePackage) return null
    return UpdateUiState.InstallHandoff(
        updateInfo = readyState.updateInfo,
        updatePackage = updatePackage,
        attemptId = attemptId,
        phase = phase
    )
}

internal fun markInstallerPromptLaunched(
    state: UpdateUiState,
    attemptId: Long
): UpdateUiState.InstallHandoff? {
    val handoff = state as? UpdateUiState.InstallHandoff ?: return null
    if (handoff.attemptId != attemptId ||
        handoff.phase != InstallHandoffPhase.AWAITING_PERMISSION
    ) {
        return null
    }
    return handoff.copy(phase = InstallHandoffPhase.INSTALLER_PROMPT_LAUNCHED)
}

internal fun restoreAvailableUpdate(
    state: UpdateUiState,
    expectedAttemptId: Long?
): UpdateUiState.Available? {
    val updateInfo = when (state) {
        is UpdateUiState.ReadyToInstall -> {
            if (expectedAttemptId != null) return null
            state.updateInfo
        }
        is UpdateUiState.InstallHandoff -> {
            if (expectedAttemptId != state.attemptId) return null
            state.updateInfo
        }
        else -> return null
    }
    return UpdateUiState.Available(updateInfo)
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

    /** 当前下载任务；用于阻止重复下载并支持用户取消。 */
    private var downloadJob: Job? = null

    /** 单调递增的外部安装交接 ID；只在 ViewModel 主线程访问。 */
    private var nextInstallAttemptId = 1L

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

    /** 在应用内下载并验证更新包。 */
    fun downloadUpdate(updateInfo: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            val activeDownloadJob = coroutineContext[Job]
            _uiState.value = UpdateUiState.Downloading(updateInfo, progressPercent = 0)
            try {
                repository.downloadUpdate(updateInfo) { progressPercent ->
                    // OkHttp 回调位于工作线程；统一投递回 ViewModel 主线程后再读取状态。
                    viewModelScope.launch {
                        if (downloadJob === activeDownloadJob &&
                            _uiState.value is UpdateUiState.Downloading
                        ) {
                            _uiState.value = UpdateUiState.Downloading(updateInfo, progressPercent)
                        }
                    }
                }.onSuccess { updatePackage ->
                    if (downloadJob === activeDownloadJob) {
                        _uiState.value = UpdateUiState.ReadyToInstall(updateInfo, updatePackage)
                    }
                }.onFailure { failure ->
                    if (downloadJob === activeDownloadJob) {
                        val message = (failure as? UpdatePackageException)?.userMessage
                            ?: "安装包下载失败，请稍后重试"
                        _uiState.value = UpdateUiState.Error(message)
                    }
                }
            } catch (_: CancellationException) {
                // 用户关闭更新弹窗时由 dismissDialog() 明确回到 Idle，无需再覆盖状态。
            } finally {
                if (downloadJob === activeDownloadJob) {
                    downloadJob = null
                }
            }
        }
    }

    /** 在启动外部授权页或安装器前原子消费 Ready 状态，避免重组或旋转重复启动。 */
    fun markInstallHandoffStarted(
        updatePackage: DownloadedUpdatePackage,
        phase: InstallHandoffPhase
    ): Long? {
        val attemptId = nextInstallAttemptId
        val handoff = beginInstallHandoff(
            state = _uiState.value,
            updatePackage = updatePackage,
            attemptId = attemptId,
            phase = phase
        ) ?: return null
        nextInstallAttemptId += 1L
        _uiState.value = handoff
        return attemptId
    }

    /** 未知来源授权成功后，仅允许当前 attempt 进入系统安装页。 */
    fun markInstallerPromptLaunched(attemptId: Long): Boolean {
        val nextState = markInstallerPromptLaunched(_uiState.value, attemptId) ?: return false
        _uiState.value = nextState
        return true
    }

    /** 未取得安装授权或未能打开安装器时，恢复可重试的更新弹窗。 */
    fun restoreAvailableUpdate(expectedAttemptId: Long? = null) {
        val availableState = restoreAvailableUpdate(
            state = _uiState.value,
            expectedAttemptId = expectedAttemptId
        ) ?: return
        _uiState.value = availableState
    }

    /**
     * 关闭弹窗
     * 重置状态为空闲
     */
    fun dismissDialog() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = UpdateUiState.Idle
    }
}
