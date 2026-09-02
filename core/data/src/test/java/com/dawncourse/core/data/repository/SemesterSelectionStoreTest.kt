package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当前学期选择键的纯 JVM 契约测试。
 */
class SemesterSelectionStoreTest {

    @Test
    fun missingKeyUsesLegacyIdExactlyOnce() = runBlocking {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SemesterSelectionStore(dataStore)

        store.initializeIfUnset(legacySemesterId = 8L)
        store.initializeIfUnset(legacySemesterId = 3L)

        assertEquals(8L, store.selectedSemesterId.first())
    }

    @Test
    fun missingKeyWithoutLegacyWritesExplicitNoSelection() = runBlocking {
        val store = SemesterSelectionStore(InMemoryPreferencesDataStore())

        store.initializeIfUnset(legacySemesterId = null)

        assertNull(store.selectedSemesterId.first())
        assertEquals(0L, store.rawSelectedSemesterId.first())
    }

    @Test
    fun explicitNoSelectionIsNeverBridgedAgain() = runBlocking {
        val store = SemesterSelectionStore(InMemoryPreferencesDataStore())
        store.clearSelection()

        store.initializeIfUnset(legacySemesterId = 5L)

        assertNull(store.selectedSemesterId.first())
        assertEquals(0L, store.rawSelectedSemesterId.first())
    }

    @Test
    fun selectRejectsNonPositiveIds() = runBlocking {
        val store = SemesterSelectionStore(InMemoryPreferencesDataStore())

        val failure = runCatching { store.selectSemester(0L) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }

    @Test
    fun settingsMutationsCannotChangeOrRemoveSelection() {
        val preferences = mutablePreferencesOf(
            SemesterSelectionStore.SELECTED_SEMESTER_ID_KEY to 12L
        )

        SemesterSelectionStore.preserveSelection(preferences) {
            clear()
            this[stringPreferencesKey("other_setting")] = "restored"
            this[SemesterSelectionStore.SELECTED_SEMESTER_ID_KEY] = 99L
        }

        assertEquals(12L, preferences[SemesterSelectionStore.SELECTED_SEMESTER_ID_KEY])
        assertEquals("restored", preferences[stringPreferencesKey("other_setting")])
    }

    @Test
    fun legacySemesterMetadataKeysAreNoLongerReadOrWritten() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/SettingsRepositoryImpl.kt"
        ).readText()

        assertEquals(false, source.contains("current_semester_name"))
        assertEquals(false, source.contains("total_weeks"))
        assertEquals(false, source.contains("start_date_timestamp"))
    }

    @Test
    fun clearAllAndSetAllBothUseSelectionGuard() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/SettingsRepositoryImpl.kt"
        ).readText()
        val clearAllBody = source.substring(
            source.indexOf("override suspend fun clearAllSettings"),
            source.indexOf("override suspend fun setReminderMinutes")
        )
        val setAllBody = source.substring(
            source.indexOf("override suspend fun setAllSettings"),
            source.indexOf("override suspend fun generateBlurredWallpaper")
        )

        assertEquals(true, clearAllBody.contains("SemesterSelectionStore.preserveSelection"))
        assertEquals(true, setAllBody.contains("SemesterSelectionStore.preserveSelection"))
    }

    @Test
    fun recoveryWritesSettingsAndSelectionInOneDataStoreEdit() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/SettingsRepositoryImpl.kt"
        ).readText()
        val method = source.substringAfter("override suspend fun restoreAllSettingsAndSelection")
            .substringBefore("/** 将完整设置写入")

        assertEquals(1, Regex("dataStore\\.edit").findAll(method).count())
        assertTrue(method.contains("writeAllSettings(settings)"))
        assertTrue(method.contains("SELECTED_SEMESTER_ID_KEY"))
    }

    /**
     * 只实现 DataStore 的原子 updateData 契约，避免 JVM 测试依赖 Android Context。
     */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
