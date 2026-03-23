package com.dawncourse.feature.settings

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.CredentialsRepository
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.map
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
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val credentialsRepository: CredentialsRepository,
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
    private val calendarExportRepository: CalendarExportRepository
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
     * 已绑定的自动更新来源（例如 正方教务）
     */
    val boundProvider: StateFlow<SyncProviderType?> = credentialsRepository.boundProvider
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

    init {
        // 同步 DataStore 设置与数据库中的当前学期数据
        // 防止因导入或其他操作导致 DataStore 中的缓存数据（如开学日期）滞后
        viewModelScope.launch {
            try {
                val currentSemester = semesterRepository.getCurrentSemester().first()
                if (currentSemester != null) {
                    // 获取当前 DataStore 中的值（不阻塞主线程）
                    val cachedSettings = settingsRepository.settings.first()
                    
                    // 检查是否有不一致，如果有则更新 DataStore
                    if (cachedSettings.currentSemesterName != currentSemester.name ||
                        cachedSettings.startDateTimestamp != currentSemester.startDate ||
                        cachedSettings.totalWeeks != currentSemester.weekCount
                    ) {
                        settingsRepository.setCurrentSemesterName(currentSemester.name)
                        settingsRepository.setStartDateTimestamp(currentSemester.startDate)
                        settingsRepository.setTotalWeeks(currentSemester.weekCount)
                    }
                }
            } catch (_: Throwable) {
                // 启动阶段的“同步设置与学期”属于一致性优化：
                // - 失败不会影响 Settings 页面正常展示（仍会使用 DataStore 的缓存值）
                // - 失败通常来自数据库未初始化/迁移中、短暂 IO 问题等，可在后续自然恢复
                //
                // 因此这里选择“安全忽略”，避免打印堆栈造成噪音与潜在隐私风险。
            }
        }

    }

    /**
     * 绑定 WakeUp 口令作为自动更新凭据
     *
     * @param token WakeUp 分享口令
     */
    fun bindWakeUpToken(token: String) {
        viewModelScope.launch {
            // 仅保存口令，用户名留空
            val creds = SyncCredentials(
                provider = SyncProviderType.WAKEUP,
                type = SyncCredentialType.TOKEN,
                username = null,
                secret = token,
                endpointUrl = null
            )
            credentialsRepository.saveCredentials(creds)
        }
    }

    /**
     * 清除已绑定凭据
     */
    fun clearSyncCredentials() {
        viewModelScope.launch {
            credentialsRepository.clearCredentials()
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
            val normalized = normalizeEndpointInput(endpoint)
            val creds = SyncCredentials(
                provider = SyncProviderType.QIDI,
                type = SyncCredentialType.PASSWORD,
                username = username.trim(),
                secret = password,
                endpointUrl = normalized
            )
            credentialsRepository.saveCredentials(creds)
        }
    }

    /**
     * 绑定正方教务账号（用户名+密码+入口地址）
     */
    fun bindZfCredentials(endpoint: String, username: String, password: String) {
        viewModelScope.launch {
            val normalized = normalizeEndpointInput(endpoint)
            val creds = SyncCredentials(
                provider = SyncProviderType.ZF,
                type = SyncCredentialType.PASSWORD,
                username = username.trim(),
                secret = password,
                endpointUrl = normalized
            )
            credentialsRepository.saveCredentials(creds)
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
            _localBackupState.value = exportLocalBackupUseCase(uri).toUiState()
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
            _localBackupState.value = importLocalBackupUseCase(uri).toUiState()
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
            } catch (e: Exception) {
                _calendarExportState.value = CalendarExportUiState(success = false, message = "导出日历发生错误：${e.message}")
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
            _localBackupPreviewState.value = readLocalBackupPreviewUseCase(uri).toUiState(uri)
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

    fun setBackgroundBlur(value: Float) {
        viewModelScope.launch {
            settingsRepository.setBackgroundBlur(value)
        }
    }

    fun setBackgroundBrightness(value: Float) {
        viewModelScope.launch {
            settingsRepository.setBackgroundBrightness(value)
        }
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
        viewModelScope.launch {
            settingsRepository.setDividerType(type)
        }
    }

    /**
     * 设置课表分割线宽度
     *
     * @param width 宽度值 (dp)
     */
    fun setDividerWidth(width: Float) {
        viewModelScope.launch {
            settingsRepository.setDividerWidth(width)
        }
    }

    /**
     * 设置课表分割线颜色
     *
     * @param color 颜色 Hex 字符串
     */
    fun setDividerColor(color: String) {
        viewModelScope.launch {
            settingsRepository.setDividerColor(color)
        }
    }

    /**
     * 设置课表分割线不透明度
     *
     * @param alpha 不透明度 (0.0 - 1.0)
     */
    fun setDividerAlpha(alpha: Float) {
        viewModelScope.launch {
            settingsRepository.setDividerAlpha(alpha)
        }
    }

    /**
     * 设置每天最大节数
     *
     * @param count 节数 (8-16)
     */
    fun setMaxDailySections(count: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxDailySections(count)
        }
    }

    fun setCourseItemHeight(height: Int) {
        viewModelScope.launch {
            settingsRepository.setCourseItemHeight(height)
        }
    }

    /**
     * 设置默认课程时长
     *
     * @param duration 节数 (1-4)
     */
    fun setDefaultCourseDuration(duration: Int) {
        viewModelScope.launch {
            settingsRepository.setDefaultCourseDuration(duration)
        }
    }

    /**
     * 批量更新所有课程的时长
     *
     * @param duration 新的时长（节数）
     */
    fun updateAllCoursesDuration(duration: Int) {
        viewModelScope.launch {
            courseRepository.updateAllCoursesDuration(duration)
        }
    }

    fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        viewModelScope.launch {
            settingsRepository.setSectionTimes(times)
        }
    }

    fun setCardCornerRadius(radius: Int) {
        viewModelScope.launch { settingsRepository.setCardCornerRadius(radius) }
    }

    fun setCardAlpha(alpha: Float) {
        viewModelScope.launch { settingsRepository.setCardAlpha(alpha) }
    }

    fun setShowCourseIcons(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCourseIcons(show) }
    }

    fun setWallpaperMode(mode: com.dawncourse.core.domain.model.WallpaperMode) {
        viewModelScope.launch { settingsRepository.setWallpaperMode(mode) }
    }

    fun setThemeMode(mode: com.dawncourse.core.domain.model.AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setShowWeekend(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowWeekend(show) }
    }

    fun setShowSidebarTime(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowSidebarTime(show) }
    }

    fun setShowSidebarIndex(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowSidebarIndex(show) }
    }

    fun setHideNonThisWeek(hide: Boolean) {
        viewModelScope.launch { settingsRepository.setHideNonThisWeek(hide) }
    }

    fun setShowDateInHeader(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowDateInHeader(show) }
    }

    fun setCurrentSemesterName(name: String) {
        viewModelScope.launch { 
            settingsRepository.setCurrentSemesterName(name)
            val currentSemester = semesterRepository.getCurrentSemester().first()
            if (currentSemester != null) {
                semesterRepository.updateSemester(currentSemester.copy(name = name))
            }
            sendWidgetUpdateBroadcast()
        }
    }

    fun setTotalWeeks(weeks: Int) {
        viewModelScope.launch { 
            settingsRepository.setTotalWeeks(weeks) 
            val currentSemester = semesterRepository.getCurrentSemester().first()
            if (currentSemester != null) {
                semesterRepository.updateSemester(currentSemester.copy(weekCount = weeks))
            }
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
            val currentSemester = semesterRepository.getCurrentSemester().first()
            if (currentSemester != null) {
                val maxWeek = courseRepository.getMaxWeekInSemester(currentSemester.id)
                onResult(maxWeek)
            } else {
                onResult(0)
            }
        }
    }

    fun setStartDateTimestamp(timestamp: Long) {
        viewModelScope.launch { 
            settingsRepository.setStartDateTimestamp(timestamp)
            val currentSemester = semesterRepository.getCurrentSemester().first()
            if (currentSemester != null) {
                semesterRepository.updateSemester(currentSemester.copy(startDate = timestamp))
            }
            sendWidgetUpdateBroadcast()
        }
    }

    private fun sendWidgetUpdateBroadcast() {
        widgetUpdateRepository.triggerUpdate()
    }

    fun setEnableClassReminder(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableClassReminder(enable) }
    }

    fun setReminderMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setReminderMinutes(minutes) }
    }

    fun setEnablePersistentNotification(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnablePersistentNotification(enable) }
    }

    fun setEnableAutoMute(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableAutoMute(enable) }
    }

    /**
     * 设置 WebDAV 自动同步开关
     */
    fun setEnableWebDavAutoSync(enable: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableWebDavAutoSync(enable) }
    }

    fun setWebDavAutoSyncMode(mode: WebDavAutoSyncMode) {
        viewModelScope.launch { settingsRepository.setWebDavAutoSyncMode(mode) }
    }

    fun setWebDavAutoSyncFixedAt(timestamp: Long) {
        viewModelScope.launch { settingsRepository.setWebDavAutoSyncFixedAt(timestamp) }
    }

    fun setWebDavAutoSyncIntervalValue(value: Int) {
        viewModelScope.launch { settingsRepository.setWebDavAutoSyncIntervalValue(value) }
    }

    fun setWebDavAutoSyncIntervalUnit(unit: WebDavAutoSyncIntervalUnit) {
        viewModelScope.launch { settingsRepository.setWebDavAutoSyncIntervalUnit(unit) }
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
            webDavCredentialsRepository.saveCredentials(
                WebDavCredentials(
                    serverUrl = normalizedUrl,
                    username = username.trim(),
                    password = password
                )
            )
            _webDavActionResult.emit(WebDavSyncResult(true, "已保存 WebDAV 账号"))
        }
    }

    /**
     * 清除 WebDAV 账号信息
     */
    fun clearWebDavCredentials() {
        viewModelScope.launch {
            webDavCredentialsRepository.clearCredentials()
            _webDavActionResult.emit(WebDavSyncResult(true, "已清除 WebDAV 账号"))
        }
    }

    /**
     * 刷新云端备份信息
     */
    fun refreshWebDavRemoteInfo() {
        viewModelScope.launch {
            _webDavRemoteInfo.emit(fetchWebDavRemoteInfoUseCase())
        }
    }

    /**
     * 上传本地备份到 WebDAV
     *
     * @param forceUpload 是否强制覆盖云端
     */
    fun uploadWebDavBackup(forceUpload: Boolean) {
        viewModelScope.launch {
            _webDavActionResult.emit(uploadWebDavBackupUseCase(forceUpload))
        }
    }

    /**
     * 下载 WebDAV 备份并恢复
     */
    fun downloadWebDavBackup() {
        viewModelScope.launch {
            _webDavActionResult.emit(downloadWebDavBackupUseCase())
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
        viewModelScope.launch {
            courseRepository.deleteAllCourses()
            semesterRepository.deleteAllSemesters()
            credentialsRepository.clearCredentials()
            settingsRepository.clearAllSettings()
        }
    }
}

/**
 * 本地备份 UI 状态
 *
 * @property isProcessing 是否正在执行导入/导出
 * @property success 结果是否成功（null 表示尚未执行）
 * @property message 当前提示文案
 */
data class LocalBackupUiState(
    val isProcessing: Boolean = false,
    val success: Boolean? = null,
    val message: String = ""
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
        message = message
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
