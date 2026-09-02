package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotProfile
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.StartupSnapshotThemeMode
import com.dawncourse.core.domain.model.StartupSnapshotVisualSettings
import com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode
import com.dawncourse.core.domain.repository.StartupSnapshotRepository
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime 只消费 DataStore 选择事实和快照仓库，绝不需要 AppDatabase/DAO。 */
class StartupSnapshotRuntimeTest {
    @Test
    fun readsUsingDurableProfileSelectionAndPublishesAvailableState() = runBlocking {
        val selectionStore = ActiveProfileSelectionStore(InMemoryPreferencesDataStore())
        selectionStore.selectProfile(7L)
        val repository = RecordingRepository(StartupSnapshotReadResult.Available(signedSnapshot()))
        val runtime = StartupSnapshotRuntime(
            repository = repository,
            activeProfileSelectionStore = selectionStore,
            nowEpochMillis = { 1_750_000_000_000L },
            zoneId = { "Asia/Shanghai" },
        )

        runtime.start()

        val state = runtime.state.first { it is StartupSnapshotRuntimeState.Available }
        assertEquals(7L, repository.lastExpectedProfileId)
        assertEquals(StartupSnapshotRuntimeState.Available(signedSnapshot()), state)
    }

    @Test
    fun invalidationRemovesVisibleSnapshotBeforeBestEffortStorageCleanup() = runBlocking {
        val runtime = StartupSnapshotRuntime(
            repository = RecordingRepository(StartupSnapshotReadResult.Missing),
            activeProfileSelectionStore = ActiveProfileSelectionStore(InMemoryPreferencesDataStore()),
            nowEpochMillis = { 1L },
            zoneId = { "UTC" },
        )

        runtime.invalidate()

        assertEquals(StartupSnapshotRuntimeState.Missing, runtime.state.value)
    }

    @Test
    fun invalidationCannotBeOverwrittenByAnInFlightRead() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val readFinished = CompletableDeferred<Unit>()
        val runtime = StartupSnapshotRuntime(
            repository = object : StartupSnapshotRepository {
                override suspend fun read(
                    expectedProfileId: Long?,
                    nowEpochMillis: Long,
                    expectedZoneId: String,
                ): StartupSnapshotReadResult {
                    readStarted.complete(Unit)
                    return withContext(NonCancellable) {
                        releaseRead.await()
                        readFinished.complete(Unit)
                        StartupSnapshotReadResult.Available(signedSnapshot())
                    }
                }

                override suspend fun replace(snapshot: StartupSnapshot): Boolean = true
                override suspend fun invalidate() = Unit
            },
            activeProfileSelectionStore = ActiveProfileSelectionStore(InMemoryPreferencesDataStore()),
            nowEpochMillis = { 1L },
            zoneId = { "UTC" },
        )

        runtime.start()
        readStarted.await()
        runtime.invalidate()
        releaseRead.complete(Unit)
        readFinished.await()
        // repository.read 已返回后再让 Runtime 协程取得执行机会，覆盖“失效后晚到结果”的竞态。
        delay(100)

        assertEquals(StartupSnapshotRuntimeState.Missing, runtime.state.value)
    }

    private fun signedSnapshot(): StartupSnapshot {
        val unsigned = StartupSnapshot(
            protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
            profile = StartupSnapshotProfile(7L, "profile-seven"),
            semester = null,
            courses = emptyList(),
            visualSettings = StartupSnapshotVisualSettings(
                dynamicColor = false,
                wallpaperUri = null,
                transparency = 0f,
                fontStyle = StartupSnapshotFontStyle.SYSTEM,
                dividerType = StartupSnapshotDividerType.SOLID,
                dividerWidthDp = 1f,
                dividerColor = "#ffffff",
                dividerAlpha = 1f,
                courseItemHeightDp = 64,
                maxDailySections = 12,
                sectionTimes = emptyList(),
                cardCornerRadius = 16,
                cardAlpha = 1f,
                showCourseIcons = true,
                wallpaperMode = StartupSnapshotWallpaperMode.CROP,
                themeMode = StartupSnapshotThemeMode.SYSTEM,
                showWeekend = true,
                showSidebarTime = true,
                showSidebarIndex = true,
                hideNonThisWeek = false,
                showDateInHeader = false,
                backgroundBlur = 0f,
                backgroundBrightness = 1f,
            ),
            createdAtEpochMillis = 1_749_999_999_999L,
            expiresAtEpochMillis = 1_750_604_800_000L,
            zoneId = "Asia/Shanghai",
            revision = StartupSnapshotRevision("pending"),
        )
        return unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))
    }

    private class RecordingRepository(
        private val result: StartupSnapshotReadResult,
    ) : StartupSnapshotRepository {
        var lastExpectedProfileId: Long? = null

        override suspend fun read(expectedProfileId: Long?, nowEpochMillis: Long, expectedZoneId: String): StartupSnapshotReadResult {
            lastExpectedProfileId = expectedProfileId
            return result
        }

        override suspend fun replace(snapshot: StartupSnapshot): Boolean = true
        override suspend fun invalidate() = Unit
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
}
