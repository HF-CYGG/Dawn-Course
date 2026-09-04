package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.entity.projectForBackupExport
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncSourceBinding
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** settings、Room 和已解析选择组成的同一导出快照。 */
internal data class BackupSnapshot(
    val settings: AppSettings,
    val semesters: List<Semester>,
    val courses: List<Course>,
    val selectedSemesterId: Long,
    val profiles: List<TimetableProfile>,
    val sourceBindings: List<SyncSourceBinding>,
    val activeProfileId: Long,
)

@Singleton
internal class BackupSnapshotBuilder @Inject constructor(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val profileSelectionCoordinator: ProfileSelectionCoordinator,
) {
    suspend fun build(): BackupSnapshot = profileSelectionCoordinator.withResolvedActiveContext { activeContext ->
        val settings = settingsRepository.settings.first()
        val snapshot = database.withTransaction {
            val semesters = database.semesterDao().getAllSemestersOnce().map {
                it.toDomain().copy(isCurrent = false)
            }
            val courses = database.courseDao().getAllCoursesOnce().map { it.toDomain() }
            val profiles = database.timetableProfileDao().getAllProfilesOnce().map { it.toDomain() }
            val bindingProjection = database.syncSourceBindingDao().getAllOnce().projectForBackupExport()
            RoomBackupSnapshot(
                profiles = profiles,
                semesters = semesters,
                courses = courses,
                sourceBindings = bindingProjection.bindings,
                invalidBindingCount = bindingProjection.invalidBindingCount,
            )
        }
        if (snapshot.invalidBindingCount > 0) {
            // 不记录 binding 标识或凭据，避免备份日志扩大敏感数据暴露面。
            android.util.Log.w(
                INVALID_BINDING_LOG_TAG,
                "已跳过 ${snapshot.invalidBindingCount} 个无效同步来源绑定，课程、学期与课表仍会正常导出",
            )
        }
        require(snapshot.profiles.any { it.id == activeContext.profile.id }) { "活动课表未进入导出快照" }
        BackupSnapshot(
            settings = settings,
            semesters = snapshot.semesters,
            courses = snapshot.courses,
            selectedSemesterId = activeContext.semester?.id ?: 0L,
            profiles = snapshot.profiles,
            sourceBindings = snapshot.sourceBindings,
            activeProfileId = activeContext.profile.id,
        )
    }

    private data class RoomBackupSnapshot(
        val profiles: List<TimetableProfile>,
        val semesters: List<Semester>,
        val courses: List<Course>,
        val sourceBindings: List<SyncSourceBinding>,
        val invalidBindingCount: Int,
    )

    private companion object {
        const val INVALID_BINDING_LOG_TAG = "BackupSnapshot"
    }
}
