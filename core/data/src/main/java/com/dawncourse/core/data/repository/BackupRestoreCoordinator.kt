package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncSourceBinding
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 采集 pre-image、应用新状态并在必要时执行补偿恢复。 */
@Singleton
internal class BackupRestoreCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val activeProfileSelectionStore: ActiveProfileSelectionStore,
    private val profileSelectionCoordinator: ProfileSelectionCoordinator,
    private val recoveryRequiredStore: BackupRecoveryRequiredStore
) {
    suspend fun restore(replacement: ValidatedBackupRestore): Result<Unit> =
        profileSelectionCoordinator.withProfileMutationLock {
            val selected = settingsRepository.selectedSemesterId.first()
            val activeProfileId = activeProfileSelectionStore.activeProfileId.first()
            val settings = settingsRepository.settings.first()
            val roomData = database.withTransaction {
                RoomRestoreSnapshot(
                    profiles = database.timetableProfileDao().getAllProfilesOnce().map { it.toDomain() },
                    semesters = database.semesterDao().getAllSemestersOnce().map { it.toDomain() },
                    courses = database.courseDao().getAllCoursesOnce().map { it.toDomain() },
                    sourceBindings = database.syncSourceBindingDao().getAllOnce().map { it.toDomain() },
                )
            }
            val preImage = BackupRestorePreImage(
                settings = settings,
                semesters = roomData.semesters,
                courses = roomData.courses,
                selectedSemesterId = selected?.takeIf { it > 0L && idExists(it, roomData.semesters) },
                activeProfileId = activeProfileId?.takeIf { id -> roomData.profiles.any { it.id == id } },
                profiles = roomData.profiles,
                sourceBindings = roomData.sourceBindings,
            )
            CompensatingBackupRestore.execute(
                preImage = preImage,
                replacement = replacement,
                replaceRoom = ::replaceRoom,
                replaceSettingsAndSelection = ::restoreSettingsAndSelection,
                persistRecoveryRequired = recoveryRequiredStore::markRequired
            )
        }

    /**
     * v1/v2 继续保留 legacy selectedSemesterId，v3/v4 则必须额外恢复 active_profile_id。
     * 两个键共用同一 DataStore；失败时由补偿协议恢复前一快照。
     */
    private suspend fun restoreSettingsAndSelection(
        settings: com.dawncourse.core.domain.model.AppSettings,
        selectedSemesterId: Long?,
        activeProfileId: Long?,
    ) {
        settingsRepository.restoreAllSettingsAndSelection(settings, selectedSemesterId)
        if (activeProfileId == null) {
            activeProfileSelectionStore.clearSelection()
        } else {
            activeProfileSelectionStore.selectProfile(activeProfileId)
        }
    }

    private suspend fun replaceRoom(
        profiles: List<TimetableProfile>,
        semesters: List<Semester>,
        courses: List<Course>,
        sourceBindings: List<SyncSourceBinding>,
    ) {
        database.withTransaction {
            database.syncSourceBindingDao().deleteAll()
            database.courseDao().deleteAllCourses()
            database.semesterDao().deleteAllSemesters()
            database.timetableProfileDao().deleteAll()
            profiles.forEach { database.timetableProfileDao().insert(it.toEntity()) }
            semesters.forEach { database.semesterDao().insertSemester(it.toEntity()) }
            courses.forEach { database.courseDao().insertCourse(it.toEntity()) }
            sourceBindings.forEach { database.syncSourceBindingDao().insert(it.toEntity()) }
        }
    }

    private fun idExists(
        id: Long,
        semesters: List<Semester>
    ): Boolean = semesters.any { it.id == id }

    private data class RoomRestoreSnapshot(
        val profiles: List<TimetableProfile>,
        val semesters: List<Semester>,
        val courses: List<Course>,
        val sourceBindings: List<SyncSourceBinding>,
    )
}
