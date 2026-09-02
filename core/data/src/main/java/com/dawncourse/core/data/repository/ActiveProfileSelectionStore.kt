package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** active_profile_id 的唯一读写入口；0 表示显式未选择。 */
class ActiveProfileSelectionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** 原始键值；null 仅表示尚未执行旧选择桥接。 */
    internal val rawActiveProfileId: Flow<Long?> = dataStore.data
        // 单次可恢复的存储读取异常不得终止此流：否则 ProfileSelectionCoordinator
        // .observeActiveContext() 的 first() 永不发射，MainViewModel 会一直卡在
        // Loading、Splash 不退出。与 SemesterSelectionStore / SettingsRepositoryImpl
        // 一致，用空偏好回退（等价于“键缺失”，随后按未选择处理）。
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[ACTIVE_PROFILE_ID_KEY] }
        .distinctUntilChanged()

    /** 当前有效 Profile ID；非法、0 与缺失均作为未选择暴露。 */
    val activeProfileId: Flow<Long?> = rawActiveProfileId
        .map { it?.takeIf { selectedId -> selectedId > NO_SELECTION_ID } }
        .distinctUntilChanged()

    /** 写入当前有效 Profile ID。 */
    suspend fun selectProfile(profileId: Long) {
        require(profileId > NO_SELECTION_ID) { "profile id must be positive" }
        dataStore.edit { preferences -> preferences[ACTIVE_PROFILE_ID_KEY] = profileId }
    }

    /** 写入显式空选择，避免再次回退到遗留学期键。 */
    suspend fun clearSelection() {
        dataStore.edit { preferences -> preferences[ACTIVE_PROFILE_ID_KEY] = NO_SELECTION_ID }
    }

    /**
     * 精确恢复写入前的原始键状态。
     *
     * `null` 表示键原本不存在，不能用 0 代替；否则失败补偿会意外关闭首次旧选择桥接。
     */
    internal suspend fun restoreRawSelection(profileId: Long?) {
        dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(ACTIVE_PROFILE_ID_KEY)
            } else {
                preferences[ACTIVE_PROFILE_ID_KEY] = profileId
            }
        }
    }

    /** 缺失时以遗留 selected_semester_id 反查出的 Profile 完成一次性桥接。 */
    suspend fun initializeIfUnset(legacySemesterProfileId: Long?) {
        val normalized = legacySemesterProfileId?.takeIf { it > NO_SELECTION_ID } ?: NO_SELECTION_ID
        dataStore.edit { preferences ->
            if (preferences[ACTIVE_PROFILE_ID_KEY] == null) {
                preferences[ACTIVE_PROFILE_ID_KEY] = normalized
            }
        }
    }

    private companion object {
        /** 新运行时唯一选择键。 */
        val ACTIVE_PROFILE_ID_KEY = longPreferencesKey("active_profile_id")
        const val NO_SELECTION_ID = 0L
    }
}
