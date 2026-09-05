package com.dawncourse.feature.settings

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.util.runSuspendCatching
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.SyncSourceBindingRepository
import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import com.dawncourse.core.domain.repository.SyncStateRepository
import com.dawncourse.core.domain.repository.WebDavCredentialsRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import com.dawncourse.core.domain.repository.CalendarExportRepository
import com.dawncourse.core.domain.usecase.FetchWebDavRemoteInfoUseCase
import com.dawncourse.core.domain.usecase.UploadWebDavBackupUseCase
import com.dawncourse.core.domain.usecase.DownloadWebDavBackupUseCase
import com.dawncourse.core.domain.usecase.ExportLocalBackupUseCase
import com.dawncourse.core.domain.usecase.ImportLocalBackupUseCase
import com.dawncourse.core.domain.usecase.ReadLocalBackupPreviewUseCase
import com.dawncourse.core.domain.usecase.GenerateIcsUseCase
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.WebDavAutoSyncIntervalUnit
import com.dawncourse.core.domain.model.WebDavAutoSyncMode
import com.dawncourse.core.domain.model.WebDavCredentials
import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.model.LocalBackupPreview
import com.dawncourse.core.domain.model.LocalBackupPreviewResult
import com.dawncourse.core.domain.model.LocalBackupResult
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.ActiveTimetableContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页面的 ViewModel
 *
 * 负责管理和持久化应用程序的设置选项。
 * 通过 [SettingsRepository] 与数据层交互，使用 StateFlow 暴露当前的设置状态。
 *
 * @property settingsRepository 设置数据仓库，用于存取设置数据
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val timetableProfileRepository: TimetableProfileRepository,
    private val credentialsRepository: CredentialsRepository,
    private val syncSourceBindingRepository: SyncSourceBindingRepository,
    private val syncStateRepository: SyncStateRepository,
    private val webDavCredentialsRepository: WebDavCredentialsRepository,
    private val fetchWebDavRemoteInfoUseCase: FetchWebDavRemoteInfoUseCase,
    private val uploadWebDavBackupUseCase: UploadWebDavBackupUseCase,
    private val downloadWebDavBackupUseCase: DownloadWebDavBackupUseCase,
    private val exportLocalBackupUseCase: ExportLocalBackupUseCase,
    private val importLocalBackupUseCase: ImportLocalBackupUseCase,
    private val readLocalBackupPreviewUseCase: ReadLocalBackupPreviewUseCase,
    private val widgetUpdateRepository: WidgetUpdateRepository,
    private val generateIcsUseCase: GenerateIcsUseCase,
    private val calendarExportRepository: CalendarExportRepository,
    private val autoMuteDndAvailabilityReader: AutoMuteDndAvailabilityReader,
    private val scheduleReliabilityAvailabilityReader: ScheduleReliabilityAvailabilityReader,
) : ViewModel() {

    private val _credentialBindingEvents = MutableSharedFlow<CredentialBindingUiEvent>(extraBufferCapacity = 1)
    val credentialBindingEvents: SharedFlow<CredentialBindingUiEvent> = _credentialBindingEvents.asSharedFlow()
    private val uiEventChannel = Channel<SettingsUiEvent>(Channel.BUFFERED)
    /** 普通写入和根流失败的独立事件，不能伪装成凭据绑定结果。 */
    val uiEvents = settingsUiEventFlow(uiEventChannel)

    /** 普通设置写入统一收敛为语义失败事件，取消语义由 runSuspendCatching 保留。 */
    private fun launchSettingsOperation(operation: suspend () -> Unit) {
        viewModelScope.launch {
            if (runSuspendCatching(operation).isFailure) {
                uiEventChannel.send(SettingsUiEvent.OperationFailed)
            }
        }
    }

    /**
     * 当前的应用设置状态流
     *
     * 包含所有个性化配置项（如动态取色、透明度、壁纸等）。
     * 初始值为默认配置，后续会根据 DataStore 中的数据自动更新。
     */
    val settings: StateFlow<AppSettings> = recoverSettingsFlow(
        upstream = settingsRepository.settings,
        fallback = AppSettings(),
        onFailure = { uiEventChannel.send(SettingsUiEvent.OperationFailed) },
    )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    private val _autoMuteDndCapability = MutableStateFlow(
        autoMuteDndAvailabilityReader.readCapability(),
    )
    val autoMuteDndCapability: StateFlow<AutoMuteDndCapability> =
        _autoMuteDndCapability.asStateFlow()

    /** 系统设置返回前后以 ON_RESUME 实时权限为准，但绝不清除 enableAutoMute 期望。 */
    fun refreshAutoMuteDndAvailability() {
        _autoMuteDndCapability.value = autoMuteDndAvailabilityReader.readCapability()
    }

    private val _scheduleReliabilityAvailability = MutableStateFlow(
        scheduleReliabilityAvailabilityReader.read(),
    )
    val scheduleReliabilityAvailability: StateFlow<ScheduleReliabilityAvailability> =
        _scheduleReliabilityAvailability.asStateFlow()

    /** 返回设置页后重新读取系统调度能力；读取结果不覆盖任何用户偏好。 */
    fun refreshScheduleReliabilityAvailability() {
        _scheduleReliabilityAvailability.value = scheduleReliabilityAvailabilityReader.read()
    }

    /** Room 中由 selected_semester_id 指向的当前学期。 */
    val currentSemester: StateFlow<Semester?> = semesterRepository.getCurrentSemester()
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { uiEventChannel.send(SettingsUiEvent.OperationFailed); emit(null) } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 当前 Profile 与其活动学期，供设置页顶部展示管理入口。 */
    val activeTimetableContext: StateFlow<ActiveTimetableContext?> =
        timetableProfileRepository.observeActiveContext()
            .catch { failure ->
                if (failure is CancellationException) throw failure
                if (failure is Exception) { uiEventChannel.send(SettingsUiEvent.OperationFailed); emit(null) } else throw failure
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    /**
     * 已绑定的自动更新来源（例如 正方教务）
     */
    val boundProvider: StateFlow<SyncProviderType?> = timetableProfileRepository
        .observeActiveContext()
        .flatMapLatest { context ->
            context?.profile?.id?.let(credentialsRepository::observeBoundProvider) ?: flowOf(null)
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { uiEventChannel.send(SettingsUiEvent.OperationFailed); emit(null) } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * 最近一次同步信息（格式化描述）
     */
    val lastSyncDescription: StateFlow<String> = syncStateRepository.lastSyncInfo
        .map { info ->
            if (info.timestamp <= 0L) return@map "尚未同步"
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(info.timestamp))
            val status = if (info.success) "成功" else "失败"
            "$dateStr · $status · ${info.message.ifBlank { "" }}"
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { uiEventChannel.send(SettingsUiEvent.OperationFailed); emit("尚未同步") } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "尚未同步"
        )

    /**
     * WebDAV 账号信息状态
     *
     * 用于控制 WebDAV 弹窗的“已绑定/未绑定”展示。
     */
    val webDavCredentials: StateFlow<WebDavCredentials?> = webDavCredentialsRepository.credentials
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) { uiEventChannel.send(SettingsUiEvent.OperationFailed); emit(null) } else throw failure
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * WebDAV 云端信息查询结果
     */
    private val _webDavRemoteInfo = MutableStateFlow<WebDavSyncResult?>(null)
    val webDavRemoteInfo: StateFlow<WebDavSyncResult?> = _webDavRemoteInfo.asStateFlow()

    /**
     * WebDAV 上传/下载操作结果
     */
    private val _webDavActionResult = MutableStateFlow<WebDavSyncResult?>(null)
    val webDavActionResult: StateFlow<WebDavSyncResult?> = _webDavActionResult.asStateFlow()

    /**
     * 本地备份与还原状态
     *
     * 用于控制 UI 的进度遮罩与提示文案。
     */
    private val _localBackupState = MutableStateFlow(LocalBackupUiState())
    val localBackupState: StateFlow<LocalBackupUiState> = _localBackupState.asStateFlow()

    /**
     * 本地备份预览状态
     *
     * 用于在还原前展示备份元数据并确认操作。
     */
    private val _localBackupPreviewState = MutableStateFlow(LocalBackupPreviewUiState())
    val localBackupPreviewState: StateFlow<LocalBackupPreviewUiState> = _localBackupPreviewState.asStateFlow()

    /**
     * 日历导出状态
     */
    private val _calendarExportState = MutableStateFlow(CalendarExportUiState())
    val calendarExportState: StateFlow<CalendarExportUiState> = _calendarExportState.asStateFlow()

    /**
     * 绑定 WakeUp 口令作为自动更新凭据
     *
     * @param token WakeUp 分享口令
     */
    fun bindWakeUpToken(token: String) {
        viewModelScope.launch {
            val profileId = runSuspendCatching { activeProfileId() }.getOrElse {
                uiEventChannel.send(SettingsUiEvent.OperationFailed); return@launch
            } ?: return@launch
            // 仅保存口令，用户名留空
            val creds = SyncCredentials(
                provider = SyncProviderType.WAKEUP,
                type = SyncCredentialType.TOKEN,
                username = null,
                secret = token,
                endpointUrl = null
            )
            val result = runSuspendCatching { publishCredentialBindingResult(
                syncSourceBindingRepository.saveCredentialsAndRebindIfActive(profileId, creds),
                CredentialBindingUiEvent.Saved,
            ) }
            if (result.isFailure) uiEventChannel.send(SettingsUiEvent.OperationFailed)
        }
    }

    /**
     * 清除已绑定凭据
     */
    fun clearSyncCredentials() {
        viewModelScope.launch {
            val profileId = runSuspendCatching { activeProfileId() }.getOrElse {
                uiEventChannel.send(SettingsUiEvent.OperationFailed); return@launch
            } ?: return@launch
            val result = runSuspendCatching { publishCredentialBindingResult(
                syncSourceBindingRepository.clearCredentialsAndUnbindIfActive(profileId),
                CredentialBindingUiEvent.Cleared,
            ) }
            if (result.isFailure) uiEventChannel.send(SettingsUiEvent.OperationFailed)
        }
    }

    /**
     * 绑定起迪教务账号（已弃用）
     *
     * @param endpoint 教务系统入口地址（形如 https://jw.example.edu.cn）
     * @param username 用户名
     * @param password 密码
     */
    fun bindQidiCredentials(endpoint: String, username: String, password: String) {
        viewModelScope.launch {
            val profileId = runSuspendCatching { activeProfileId() }.getOrElse {
                uiEventChannel.send(SettingsUiEvent.OperationFailed); return@launch
            } ?: return@launch
            val normalized = normalizeEndpointInput(endpoint)
            val creds = SyncCredentials(
                provider = SyncProviderType.QIDI,
                type = SyncCredentialType.PASSWORD,
                username = username.trim(),
                secret = password,
                endpointUrl = normalized
            )
            val result = runSuspendCatching { publishCredentialBindingResult(
                syncSourceBindingRepository.saveCredentialsAndRebindIfActive(profileId, creds),
                CredentialBindingUiEvent.Saved,
            ) }
            if (result.isFailure) uiEventChannel.send(SettingsUiEvent.OperationFailed)
        }
    }

    /**
     * 绑定正方教务账号（用户名+密码+入口地址）
     */
    fun bindZfCredentials(endpoint: String, username: String, password: String) {
        viewModelScope.launch {
            val profileId = runSuspendCatching { activeProfileId() }.getOrElse {
                uiEventChannel.send(SettingsUiEvent.OperationFailed); return@launch
            } ?: return@launch
            val normalized = normalizeEndpointInput(endpoint)
            val creds = SyncCredentials(
                provider = SyncProviderType.ZF,
                type = SyncCredentialType.PASSWORD,
                username = username.trim(),
                secret = password,
                endpointUrl = normalized
            )
            val result = runSuspendCatching { publishCredentialBindingResult(
                syncSourceBindingRepository.saveCredentialsAndRebindIfActive(profileId, creds),
                CredentialBindingUiEvent.Saved,
            ) }
            if (result.isFailure) uiEventChannel.send(SettingsUiEvent.OperationFailed)
        }
    }

    /**
     * 重置本地备份 UI 状态
     */
    fun resetLocalBackupState() {
        _localBackupState.value = LocalBackupUiState()
        _localBackupPreviewState.value = LocalBackupPreviewUiState()
    }

    /**
     * 导出本地备份
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    fun exportLocalBackup(uri: String) {
        viewModelScope.launch {
            _localBackupState.value = LocalBackupUiState(isProcessing = true)
            _localBackupState.value = runSuspendCatching {
                exportLocalBackupUseCase(uri).toUiState()
            }.getOrElse {
                LocalBackupUiState(success = false, message = "备份导出未完成，请稍后重试")
            }
        }
    }

    /**
     * 导入本地备份
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    fun importLocalBackup(uri: String) {
        viewModelScope.launch {
            _localBackupState.value = LocalBackupUiState(isProcessing = true)
            _localBackupState.value = runSuspendCatching {
                importLocalBackupUseCase(uri).toUiState()
            }.getOrElse {
                LocalBackupUiState(success = false, message = "备份还原未完成，请稍后重试")
            }
        }
    }

    /**
     * 导出课程为 ICS 日历文件
     *
     * @param uri SAF 返回的文件 URI
     */
    fun exportIcs(uri: String) {
        viewModelScope.launch {
            _calendarExportState.value = CalendarExportUiState(isProcessing = true)
            try {
                val currentSemester = semesterRepository.getCurrentSemester().first()
                if (currentSemester == null) {
                    _calendarExportState.value = CalendarExportUiState(success = false, message = "未找到当前学期")
                    return@launch
                }
                val courses = courseRepository.getCoursesBySemester(currentSemester.id).first()
                if (courses.isEmpty()) {
                    _calendarExportState.value = CalendarExportUiState(success = false, message = "当前学期没有课程")
                    return@launch
                }
                val appSettings = settingsRepository.settings.first()
                val icsContent = generateIcsUseCase(courses, currentSemester, appSettings.sectionTimes)
                val success = calendarExportRepository.exportIcsToUri(uri, icsContent)
                
                _calendarExportState.value = CalendarExportUiState(
                    success = success,
                    message = if (success) "导出日历成功" else "导出日历失败"
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _calendarExportState.value = CalendarExportUiState(success = false, message = "导出日历未完成，请稍后重试")
            }
        }
    }

    /**
     * 重置日历导出状态
     */
    fun resetCalendarExportState() {
        _calendarExportState.value = CalendarExportUiState()
    }

    /**
     * 读取备份预览信息
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    fun loadLocalBackupPreview(uri: String) {
        viewModelScope.launch {
            _localBackupPreviewState.value = LocalBackupPreviewUiState(
                isLoading = true,
                pendingUri = uri
            )
            _localBackupPreviewState.value = runSuspendCatching {
                readLocalBackupPreviewUseCase(uri).toUiState(uri)
            }.getOrElse {
                LocalBackupPreviewUiState(
                    success = false,
                    message = "备份预览未完成，请稍后重试",
                    pendingUri = uri,
                )
            }
        }
    }

    /**
     * 确认执行备份还原
     *
     * 依赖之前的预览结果保存的 URI。
     */
    fun confirmImportFromPreview() {
        val pendingUri = _localBackupPreviewState.value.pendingUri
        if (pendingUri.isNullOrBlank()) {
            _localBackupState.value = LocalBackupUiState(
                isProcessing = false,
                success = false,
                message = "未选择备份文件"
            )
            return
        }
        importLocalBackup(pendingUri)
    }

    /**
     * 设置是否启用动态取色 (Material You)
     *
     * @param enabled true 表示启用，false 表示禁用
     */
    fun setDynamicColor(enabled: Boolean) {
        launchSettingsOperation { settingsRepository.setDynamicColor(enabled) }
    }

    /**
     * 设置背景透明度
     *
     * @param value 透明度值，范围 0.0 - 1.0
     */
    fun setTransparency(value: Float) {
        launchSettingsOperation { settingsRepository.setTransparency(value) }
    }

    fun setBackgroundBlur(value: Float) {
        launchSettingsOperation { settingsRepository.setBackgroundBlur(value) }
    }

    fun setBackgroundBrightness(value: Float) {
        launchSettingsOperation { settingsRepository.setBackgroundBrightness(value) }
    }

    private fun normalizeEndpointInput(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        if (URLUtil.isNetworkUrl(withScheme)) {
            return withScheme
        }
        val guessed = URLUtil.guessUrl(withScheme)
        return if (URLUtil.isNetworkUrl(guessed)) guessed else ""
    }

    private fun normalizeWebDavUrl(raw: String): String {
        val normalized = normalizeEndpointInput(raw)
        if (normalized.isBlank()) return ""
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    /**
     * 设置应用字体样式
     *
     * @param style 选定的字体样式枚举 [AppFontStyle]
     */
    fun setFontStyle(style: AppFontStyle) {
        launchSettingsOperation { settingsRepository.setFontStyle(style) }
    }

    /**
     * 设置自定义壁纸 URI
     *
     * @param uri 壁纸图片的 URI 字符串，若为 null 则清除壁纸
     */
    fun setWallpaperUri(uri: String?) {
        launchSettingsOperation {
            settingsRepository.setWallpaperUri(uri)
            settingsRepository.generateBlurredWallpaper(uri)
            if (uri != null) {
                settingsRepository.setBackgroundBlur(0f)
                settingsRepository.setTransparency(0f)
            }
        }
    }

    /**
     * 设置课表分割线样式
     *
     * @param type 分割线样式 [DividerType]
     */
    fun setDividerType(type: DividerType) {
        launchSettingsOperation { settingsRepository.setDividerType(type) }
    }

    /**
     * 设置课表分割线宽度
     *
     * @param width 宽度值 (dp)
     */
    fun setDividerWidth(width: Float) {
        launchSettingsOperation { settingsRepository.setDividerWidth(width) }
    }

    /**
     * 设置课表分割线颜色
     *
     * @param color 颜色 Hex 字符串
     */
    fun setDividerColor(color: String) {
        launchSettingsOperation { settingsRepository.setDividerColor(color) }
    }

    /**
     * 设置课表分割线不透明度
     *
     * @param alpha 不透明度 (0.0 - 1.0)
     */
    fun setDividerAlpha(alpha: Float) {
        launchSettingsOperation { settingsRepository.setDividerAlpha(alpha) }
    }

    /**
     * 设置每天最大节数
     *
     * @param count 节数 (8-16)
     */
    fun setMaxDailySections(count: Int) {
        launchSettingsOperation { settingsRepository.setMaxDailySections(count) }
    }

    fun setCourseItemHeight(height: Int) {
        launchSettingsOperation { settingsRepository.setCourseItemHeight(height) }
    }

    /**
     * 设置默认课程时长
     *
     * @param duration 节数 (1-4)
     */
    fun setDefaultCourseDuration(duration: Int) {
        launchSettingsOperation { settingsRepository.setDefaultCourseDuration(duration) }
    }

    /**
     * 批量更新所有课程的时长
     *
     * @param duration 新的时长（节数）
     */
    fun updateAllCoursesDuration(duration: Int) {
        launchSettingsOperation { courseRepository.updateAllCoursesDuration(duration) }
    }

    fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        launchSettingsOperation { settingsRepository.setSectionTimes(times) }
    }

    fun setCardCornerRadius(radius: Int) {
        launchSettingsOperation { settingsRepository.setCardCornerRadius(radius) }
    }

    fun setCardAlpha(alpha: Float) {
        launchSettingsOperation { settingsRepository.setCardAlpha(alpha) }
    }

    fun setShowCourseIcons(show: Boolean) {
        launchSettingsOperation { settingsRepository.setShowCourseIcons(show) }
    }

    fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode) {
        launchSettingsOperation { settingsRepository.setWallpaperMode(mode) }
    }

    fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode) {
        launchSettingsOperation { settingsRepository.setThemeMode(mode) }
    }

    fun setShowWeekend(show: Boolean) {
        launchSettingsOperation { settingsRepository.setShowWeekend(show) }
    }

    fun setShowSidebarTime(show: Boolean) {
        launchSettingsOperation { settingsRepository.setShowSidebarTime(show) }
    }

    fun setShowSidebarIndex(show: Boolean) {
        launchSettingsOperation { settingsRepository.setShowSidebarIndex(show) }
    }

    fun setHideNonThisWeek(hide: Boolean) {
        launchSettingsOperation { settingsRepository.setHideNonThisWeek(hide) }
    }

    fun setShowDateInHeader(show: Boolean) {
        launchSettingsOperation { settingsRepository.setShowDateInHeader(show) }
    }

    /**
     * 更新当前学期元数据。
     *
     * 学期名称、周数和开学日期只写 Room，不再镜像到 AppSettings。
     */
    fun updateCurrentSemester(name: String, weeks: Int, startDate: Long) {
        launchSettingsOperation {
            val semester = semesterRepository.getCurrentSemester().first() ?: return@launchSettingsOperation
            semesterRepository.updateSemester(
                semester.copy(
                    name = name,
                    weekCount = weeks,
                    startDate = startDate,
                    isCurrent = false
                )
            )
            sendWidgetUpdateBroadcast()
        }
    }

    /**
     * 获取当前学期中课程的最大周次
     *
     * 用于在修改学期总周数时进行校验，防止课程被隐藏。
     * @param onResult 回调函数，参数为最大周次
     */
    fun getMaxCourseWeek(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val currentSemester = runSuspendCatching {
                semesterRepository.getCurrentSemester().first()
            }.getOrElse {
                uiEventChannel.send(SettingsUiEvent.OperationFailed)
                onResult(0)
                return@launch
            }
            if (currentSemester != null) {
                val maxWeek = runSuspendCatching {
                    courseRepository.getMaxWeekInSemester(currentSemester.id)
                }.getOrElse {
                    uiEventChannel.send(SettingsUiEvent.OperationFailed)
                    onResult(0)
                    return@launch
                }
                onResult(maxWeek)
            } else {
                onResult(0)
            }
        }
    }

    private fun sendWidgetUpdateBroadcast() {
        widgetUpdateRepository.triggerUpdate()
    }

    fun setEnableClassReminder(enable: Boolean) {
        launchSettingsOperation { settingsRepository.setEnableClassReminder(enable) }
    }

    fun setReminderMinutes(minutes: Int) {
        launchSettingsOperation { settingsRepository.setReminderMinutes(minutes) }
    }

    fun setEnablePersistentNotification(enable: Boolean) {
        launchSettingsOperation { settingsRepository.setEnablePersistentNotification(enable) }
    }

    fun setEnableAutoMute(enable: Boolean) {
        launchSettingsOperation { settingsRepository.setEnableAutoMute(enable) }
    }

    /**
     * 设置 WebDAV 自动同步开关
     */
    fun setEnableWebDavAutoSync(enable: Boolean) {
        launchSettingsOperation { settingsRepository.setEnableWebDavAutoSync(enable) }
    }

    fun setWebDavAutoSyncMode(mode: WebDavAutoSyncMode) {
        launchSettingsOperation { settingsRepository.setWebDavAutoSyncMode(mode) }
    }

    fun setWebDavAutoSyncFixedAt(timestamp: Long) {
        launchSettingsOperation { settingsRepository.setWebDavAutoSyncFixedAt(timestamp) }
    }

    fun setWebDavAutoSyncIntervalValue(value: Int) {
        launchSettingsOperation { settingsRepository.setWebDavAutoSyncIntervalValue(value) }
    }

    fun setWebDavAutoSyncIntervalUnit(unit: WebDavAutoSyncIntervalUnit) {
        launchSettingsOperation { settingsRepository.setWebDavAutoSyncIntervalUnit(unit) }
    }

    /**
     * 保存 WebDAV 账号信息
     *
     * 会先校验服务器地址与账号密码，再写入加密存储。
     */
    fun saveWebDavCredentials(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val normalizedUrl = normalizeWebDavUrl(serverUrl)
            if (normalizedUrl.isBlank()) {
                _webDavActionResult.emit(
                    WebDavSyncResult(false, "服务器地址无效", SyncErrorCode.UNKNOWN)
                )
                return@launch
            }
            if (username.isBlank() || password.isBlank()) {
                _webDavActionResult.emit(
                    WebDavSyncResult(false, "账号或密码不能为空", SyncErrorCode.UNKNOWN)
                )
                return@launch
            }
            val saved = runSuspendCatching { webDavCredentialsRepository.saveCredentials(
                WebDavCredentials(
                    serverUrl = normalizedUrl,
                    username = username.trim(),
                    password = password
                )
            ) }.isSuccess
            _webDavActionResult.emit(
                if (saved) WebDavSyncResult(true, "已保存 WebDAV 账号") else webDavOperationFailureResult(),
            )
        }
    }

    /**
     * 清除 WebDAV 账号信息
     */
    fun clearWebDavCredentials() {
        viewModelScope.launch {
            val cleared = runSuspendCatching {
                webDavCredentialsRepository.clearCredentials()
            }.isSuccess
            _webDavActionResult.emit(
                if (cleared) WebDavSyncResult(true, "已清除 WebDAV 账号") else webDavOperationFailureResult(),
            )
        }
    }

    /**
     * 刷新云端备份信息
     */
    fun refreshWebDavRemoteInfo() {
        viewModelScope.launch {
            _webDavRemoteInfo.emit(runSuspendCatching {
                fetchWebDavRemoteInfoUseCase()
            }.getOrElse { webDavOperationFailureResult() })
        }
    }

    /**
     * 上传本地备份到 WebDAV
     *
     * @param forceUpload 是否强制覆盖云端
     */
    fun uploadWebDavBackup(forceUpload: Boolean) {
        viewModelScope.launch {
            _webDavActionResult.emit(runSuspendCatching {
                uploadWebDavBackupUseCase(forceUpload)
            }.getOrElse { webDavOperationFailureResult() })
        }
    }

    /**
     * 下载 WebDAV 备份并恢复
     */
    fun downloadWebDavBackup() {
        viewModelScope.launch {
            _webDavActionResult.emit(runSuspendCatching {
                downloadWebDavBackupUseCase()
            }.getOrElse { webDavOperationFailureResult() })
        }
    }

    /**
     * 清空所有数据
     *
     * 包括：
     * 1. 删除所有课程
     * 2. 删除所有学期
     * 3. 清除所有绑定凭据
     * 4. 恢复所有设置到默认值
     */
    fun clearAllData() {
        launchSettingsOperation {
            // 全应用重置必须逐个删除 Profile 作用域凭据，禁止遗留其他课表账号。
            timetableProfileRepository.observeProfiles().first().forEach { profile ->
                credentialsRepository.clearCredentials(profile.id)
            }
            courseRepository.deleteAllCourses()
            semesterRepository.deleteAllSemesters()
            settingsRepository.clearAllSettings()
        }
    }

    /** 获取操作时刻的活动 Profile，避免把凭据写入旧的选择。 */
    private suspend fun activeProfileId(): Long? =
        timetableProfileRepository.getActiveContext()?.profile?.id

    /** WebDAV 外部 I/O 异常的固定安全结果，不携带服务器地址或异常消息。 */
    private fun webDavOperationFailureResult(): WebDavSyncResult = WebDavSyncResult(
        success = false,
        message = "操作未完成，请稍后重试",
        code = SyncErrorCode.UNKNOWN,
    )

    /** ViewModel 只发布语义事件，用户可见文案由 UI 资源层决定。 */
    private suspend fun publishCredentialBindingResult(
        result: CredentialBindingMutationResult,
        successEvent: CredentialBindingUiEvent,
    ) {
        _credentialBindingEvents.emit(credentialBindingUiEvent(result, successEvent))
    }
}

/** 设置页凭据操作的语义结果；不携带硬编码用户文案。 */
enum class CredentialBindingUiEvent {
    Saved,
    Cleared,
    Rejected,
    Inconsistent,
}

/** 普通设置或根 Flow 的固定失败语义。 */
enum class SettingsUiEvent { OperationFailed }

internal fun settingsUiEventFlow(channel: Channel<SettingsUiEvent>) = channel.receiveAsFlow()

internal fun <T> recoverSettingsFlow(
    upstream: kotlinx.coroutines.flow.Flow<T>,
    fallback: T,
    onFailure: suspend () -> Unit,
): kotlinx.coroutines.flow.Flow<T> = upstream.catch { failure ->
    if (failure is CancellationException) throw failure
    if (failure is Exception) { onFailure(); emit(fallback) } else throw failure
}

/** 纯映射便于验证失败与不一致状态不会被误报为保存成功。 */
internal fun credentialBindingUiEvent(
    result: CredentialBindingMutationResult,
    successEvent: CredentialBindingUiEvent,
): CredentialBindingUiEvent = when (result) {
    is CredentialBindingMutationResult.Success -> successEvent
    is CredentialBindingMutationResult.Rejected -> CredentialBindingUiEvent.Rejected
    is CredentialBindingMutationResult.Inconsistent -> CredentialBindingUiEvent.Inconsistent
}

/**
 * 本地备份 UI 状态
 *
 * @property isProcessing 是否正在执行导入/导出
 * @property success 结果是否成功（null 表示尚未执行）
 * @property message 当前提示文案
 * @property recoveryRequired 还原失败且补偿也失败，当前数据库内容已不一致，
 *   必须重启进入 Recovery 引导，不能继续使用
 */
data class LocalBackupUiState(
    val isProcessing: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val recoveryRequired: Boolean = false
)

/**
 * 本地备份预览 UI 状态
 *
 * @property isLoading 是否正在读取预览
 * @property success 结果是否成功（null 表示尚未读取）
 * @property message 当前提示文案
 * @property preview 预览数据
 * @property pendingUri 待还原文件 URI
 */
data class LocalBackupPreviewUiState(
    val isLoading: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val preview: LocalBackupPreview? = null,
    val pendingUri: String? = null
)

/**
 * 日历导出 UI 状态
 */
data class CalendarExportUiState(
    val isProcessing: Boolean = false,
    val success: Boolean? = null,
    val message: String = ""
)

/**
 * 将备份结果映射为 UI 状态
 */
private fun LocalBackupResult.toUiState(): LocalBackupUiState {
    return LocalBackupUiState(
        isProcessing = false,
        success = success,
        message = message,
        // 必须透传：补偿失败意味着数据库内容已不一致，UI 需明确要求用户立即重启进入
        // Recovery，而不是只显示一条普通错误文案后让用户继续使用。
        recoveryRequired = recoveryRequired
    )
}

/**
 * 将预览结果映射为 UI 状态
 */
private fun LocalBackupPreviewResult.toUiState(pendingUri: String): LocalBackupPreviewUiState {
    return LocalBackupPreviewUiState(
        isLoading = false,
        success = success,
        message = message,
        preview = preview,
        pendingUri = pendingUri
    )
}
