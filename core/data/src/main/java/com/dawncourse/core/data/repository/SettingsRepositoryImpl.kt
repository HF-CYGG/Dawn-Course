package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置数据仓库的实现类
 *
 * 使用 Jetpack DataStore (Preferences) 来持久化存储简单的键值对配置。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val dataStore = context.dataStore

    private object PreferencesKeys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        val TRANSPARENCY = floatPreferencesKey("transparency")
        val FONT_STYLE = stringPreferencesKey("font_style")
        val DIVIDER_TYPE = stringPreferencesKey("divider_type")
        val DIVIDER_WIDTH = intPreferencesKey("divider_width")
        val DIVIDER_COLOR = stringPreferencesKey("divider_color")
        val DIVIDER_ALPHA = floatPreferencesKey("divider_alpha")
        val MAX_DAILY_SECTIONS = intPreferencesKey("max_daily_sections")
        val DEFAULT_COURSE_DURATION = intPreferencesKey("default_course_duration")
        val SECTION_TIMES = stringPreferencesKey("section_times")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true
        val wallpaperUri = preferences[PreferencesKeys.WALLPAPER_URI]
        val transparency = preferences[PreferencesKeys.TRANSPARENCY] ?: 0f
        val fontStyleName = preferences[PreferencesKeys.FONT_STYLE] ?: AppFontStyle.SYSTEM.name
        val fontStyle = try {
            AppFontStyle.valueOf(fontStyleName)
        } catch (e: IllegalArgumentException) {
            AppFontStyle.SYSTEM
        }
        
        val dividerTypeName = preferences[PreferencesKeys.DIVIDER_TYPE] ?: DividerType.SOLID.name
        val dividerType = try {
            DividerType.valueOf(dividerTypeName)
        } catch (e: Exception) { DividerType.SOLID }
        val dividerWidth = preferences[PreferencesKeys.DIVIDER_WIDTH] ?: 1
        val dividerColor = preferences[PreferencesKeys.DIVIDER_COLOR] ?: "#E5E7EB"
        val dividerAlpha = preferences[PreferencesKeys.DIVIDER_ALPHA] ?: 1.0f
        val maxDailySections = preferences[PreferencesKeys.MAX_DAILY_SECTIONS] ?: 12
        val defaultCourseDuration = preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] ?: 2
        
        val sectionTimesString = preferences[PreferencesKeys.SECTION_TIMES] ?: ""
        val sectionTimes = if (sectionTimesString.isNotEmpty()) {
            sectionTimesString.split("|").mapNotNull { pair ->
                val parts = pair.split(",")
                if (parts.size == 2) {
                    com.dawncourse.core.domain.model.SectionTime(parts[0], parts[1])
                } else null
            }
        } else {
            emptyList()
        }

        AppSettings(
            dynamicColor = dynamicColor,
            wallpaperUri = wallpaperUri,
            transparency = transparency,
            fontStyle = fontStyle,
            dividerType = dividerType,
            dividerWidth = dividerWidth,
            dividerColor = dividerColor,
            dividerAlpha = dividerAlpha,
            maxDailySections = maxDailySections,
            defaultCourseDuration = defaultCourseDuration,
            sectionTimes = sectionTimes
        )
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun setWallpaperUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(PreferencesKeys.WALLPAPER_URI)
            } else {
                preferences[PreferencesKeys.WALLPAPER_URI] = uri
            }
        }
    }

    override suspend fun setTransparency(value: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSPARENCY] = value
        }
    }

    override suspend fun setFontStyle(style: AppFontStyle) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_STYLE] = style.name
        }
    }

    override suspend fun setDividerType(type: DividerType) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_TYPE] = type.name
        }
    }

    override suspend fun setDividerWidth(width: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_WIDTH] = width
        }
    }

    override suspend fun setDividerColor(color: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_COLOR] = color
        }
    }

    override suspend fun setDividerAlpha(alpha: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DIVIDER_ALPHA] = alpha
        }
    }

    override suspend fun setMaxDailySections(count: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DAILY_SECTIONS] = count
        }
    }

    override suspend fun setDefaultCourseDuration(duration: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_COURSE_DURATION] = duration
        }
    }

    override suspend fun setSectionTimes(times: List<com.dawncourse.core.domain.model.SectionTime>) {
        dataStore.edit { preferences ->
            val serialized = times.joinToString("|") { "${it.startTime},${it.endTime}" }
            preferences[PreferencesKeys.SECTION_TIMES] = serialized
        }
    }
}
