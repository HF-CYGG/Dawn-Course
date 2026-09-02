package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.startup.BackupRecoveryActivation
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
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
    private val databaseStartupRuntime: DatabaseStartupRuntime,
    private val backupRecoveryRequiredStore: BackupRecoveryRequiredStore,
    private val mutationGate: OperationalDataMutationGate,
) {
    suspend fun restore(replacement: ValidatedBackupRestore): Result<Unit> {
        // 从预置 marker 到补偿完成（或永久阻断）的整个窗口必须独占业务写入，不能让其它
        // Repository 在 Room 与 DataStore 的跨存储协议中间插入事务。
        val lease = mutationGate.acquireLease()
        return try {
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
                BackupRestoreSafetyProtocol.execute(
                    // marker 写入或可见性验证失败时，协议不会调用 runRestore，因此不会触达
                    // deleteAll/insert 等破坏性 Room 写入。
                    prepareMarker = {
                        backupRecoveryRequiredStore.markRequired()
                        check(backupRecoveryRequiredStore.isRequired()) {
                            "恢复保护标记未能确认写入"
                        }
                    },
                    runRestore = {
                        CompensatingBackupRestore.execute(
                            preImage = preImage,
                            replacement = replacement,
                            replaceRoom = ::replaceRoom,
                            replaceSettingsAndSelection = ::restoreSettingsAndSelection,
                            enterRecoveryRequired = {
                                // 补偿不完整时，在释放独占 lease 前先永久阻断业务写入；随后
                                // 才发布要求重启的 Runtime 状态，避免任何旧 Repository 继续写库。
                                lease.blockPermanently()
                                databaseStartupRuntime.enterBackupRestoreRecovery(
                                    BackupRecoveryActivation.MarkerPersisted,
                                )
                                BackupRecoveryActivation.MarkerPersisted
                            },
                        )
                    },
                    clearMarkerAndVerify = {
                        backupRecoveryRequiredStore.clearRequired()
                        check(!backupRecoveryRequiredStore.isRequired()) {
                            "恢复保护标记未能确认清除"
                        }
                    },
                    onMarkerUnavailable = { failure ->
                        // marker 从未确认存在时，也停止本进程后续写入，只给用户 marker 重试入口。
                        lease.blockPermanently()
                        databaseStartupRuntime.enterBackupRestoreRecovery(
                            BackupRecoveryActivation.MarkerPersistenceFailed(failure),
                        )
                    },
                    onMarkerStillRequired = {
                        // 替换成功但 marker 无法清除同样必须 fail-closed：冷启动会根据 marker
                        // 进入恢复流程，不允许当前进程继续把看似成功的状态当作可写数据。
                        lease.blockPermanently()
                        databaseStartupRuntime.enterBackupRestoreRecovery(
                            BackupRecoveryActivation.MarkerPersisted,
                        )
                        BackupRecoveryActivation.MarkerPersisted
                    },
                )
            }
        } finally {
            lease.release()
        }
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
