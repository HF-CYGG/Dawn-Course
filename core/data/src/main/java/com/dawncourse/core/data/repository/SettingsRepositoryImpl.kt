package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
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

        AppSettings(
            dynamicColor = dynamicColor,
            wallpaperUri = wallpaperUri,
            transparency = transparency,
            fontStyle = fontStyle
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
}
