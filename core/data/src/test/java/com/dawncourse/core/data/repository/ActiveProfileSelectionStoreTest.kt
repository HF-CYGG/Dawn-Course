package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** active_profile_id 与遗留 selected_semester_id 的一次性桥接契约。 */
class ActiveProfileSelectionStoreTest {

    @Test
    fun missingActiveProfileBridgesLegacySemesterToItsProfileExactlyOnce() = runBlocking {
        val store = ActiveProfileSelectionStore(InMemoryPreferencesDataStore())

        store.initializeIfUnset(legacySemesterProfileId = 7L)
        store.initializeIfUnset(legacySemesterProfileId = 8L)

        assertEquals(7L, store.activeProfileId.first())
    }

    @Test
    fun explicitNoSelectionNeverBridgesAgain() = runBlocking {
        val store = ActiveProfileSelectionStore(InMemoryPreferencesDataStore())

        store.clearSelection()
        store.initializeIfUnset(legacySemesterProfileId = 7L)

        assertNull(store.activeProfileId.first())
    }

    @Test
    fun rawSelectionRestoreDistinguishesMissingKeyFromExplicitNoSelection() = runBlocking {
        val store = ActiveProfileSelectionStore(InMemoryPreferencesDataStore())

        store.clearSelection()
        assertEquals(0L, store.rawActiveProfileId.first())
        store.restoreRawSelection(null)

        assertNull(store.rawActiveProfileId.first())
    }

    /**
     * 单次可恢复的 DataStore 读取异常必须回退为“未选择”，且流不能终止。
     *
     * 之前的契约是把异常向上抛，但 ProfileSelectionCoordinator.observeActiveContext()
     * 会先对该流调用 first()，异常会让活动上下文永不发射、MainViewModel 卡在 Loading、
     * Splash 不退出。resolveAndRepairSelectionLocked 对 null（键缺失）已有“回退到遗留
     * 选择或首个 Profile”的安全路径，因此与 SemesterSelectionStore /
     * SettingsRepositoryImpl 一致，用空偏好回退。
     */
    @Test
    fun dataStoreReadFailureFallsBackToNoSelectionWithoutTerminatingFlow() = runBlocking {
        val failure = IllegalStateException("selection store unavailable")
        val store = ActiveProfileSelectionStore(FailingReadPreferencesDataStore(failure))

        assertNull(store.activeProfileId.first())
        assertNull(store.rawActiveProfileId.first())
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FailingReadPreferencesDataStore(
        private val failure: RuntimeException,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw failure
        }
    }
}
