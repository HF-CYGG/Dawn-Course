package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.content.Intent

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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
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
        val intent = Intent("com.dawncourse.widget.FORCE_UPDATE")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
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
}
