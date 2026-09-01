package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * DataStore 中当前学期单键协议的唯一实现。
 *
 * 缺键表示尚未执行旧数据桥接；正数表示明确选择；0 表示明确无选择。
 */
class SemesterSelectionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** DataStore 中的原始值；仅供同模块原子保留与测试协议使用。 */
    internal val rawSelectedSemesterId: Flow<Long?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[SELECTED_SEMESTER_ID_KEY] }
        .distinctUntilChanged()

    /** 对领域层暴露的选择流，0 和非法负数统一视为未选择。 */
    val selectedSemesterId: Flow<Long?> = rawSelectedSemesterId
        .map { selectedId -> selectedId?.takeIf { it > NO_SELECTION_ID } }
        .distinctUntilChanged()

    /** 写入一个有效的当前学期 ID。 */
    suspend fun selectSemester(id: Long) {
        require(id > NO_SELECTION_ID) { "semester id must be positive" }
        dataStore.edit { preferences ->
            preferences[SELECTED_SEMESTER_ID_KEY] = id
        }
    }

    /** 写入显式无选择，防止后续 collector 再次从旧 Room 标记桥接。 */
    suspend fun clearSelection() {
        dataStore.edit { preferences ->
            preferences[SELECTED_SEMESTER_ID_KEY] = NO_SELECTION_ID
        }
    }

    /**
     * 原子执行一次性桥接。
     *
     * DataStore 的 edit 在同一文件内串行化，多个首个 collector 并发进入时也只有第一个能写入。
     */
    suspend fun initializeIfUnset(legacySemesterId: Long?) {
        val normalizedLegacyId = legacySemesterId?.takeIf { it > NO_SELECTION_ID } ?: NO_SELECTION_ID
        dataStore.edit { preferences ->
            if (preferences[SELECTED_SEMESTER_ID_KEY] == null) {
                preferences[SELECTED_SEMESTER_ID_KEY] = normalizedLegacyId
            }
        }
    }

    companion object {
        /** 当前学期选择的唯一 DataStore 键。 */
        internal val SELECTED_SEMESTER_ID_KEY = longPreferencesKey("selected_semester_id")

        /** DataStore 中显式无选择的哨兵值。 */
        internal const val NO_SELECTION_ID = 0L

        /**
         * 执行批量设置变更时保护选择键，避免恢复设置或重置偏好误改当前学期。
         */
        internal inline fun preserveSelection(
            preferences: MutablePreferences,
            mutation: MutablePreferences.() -> Unit
        ) {
            val selectedSemesterId = preferences[SELECTED_SEMESTER_ID_KEY]
            preferences.mutation()
            if (selectedSemesterId == null) {
                preferences.remove(SELECTED_SEMESTER_ID_KEY)
            } else {
                preferences[SELECTED_SEMESTER_ID_KEY] = selectedSemesterId
            }
        }
    }
}
