package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.ProfileCreationRequest
import com.dawncourse.core.domain.model.ProfileDeletionImpact
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfileSummary
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** [TimetableProfileRepository] 的数据层实现，所有可变操作委托统一协调器。 */
@Singleton
class TimetableProfileRepositoryImpl @Inject constructor(
    private val coordinator: ProfileSelectionCoordinator,
) : TimetableProfileRepository {
    override fun observeProfiles(): Flow<List<TimetableProfile>> = coordinator.observeProfiles()
    override fun observeActiveContext(): Flow<ActiveTimetableContext?> = coordinator.observeActiveContext()
    override suspend fun getActiveContext(): ActiveTimetableContext? = coordinator.getActiveContext()
    override fun observeSemesters(profileId: Long): Flow<List<Semester>> = coordinator.observeSemesters(profileId)
    override fun observeProfileSummaries(): Flow<List<TimetableProfileSummary>> = coordinator.observeProfileSummaries()
    override suspend fun switch(profileId: Long): ProfileMutationResult = coordinator.switch(profileId)
    override suspend fun setActiveSemester(profileId: Long, semesterId: Long?): ProfileMutationResult =
        coordinator.setActiveSemester(profileId, semesterId)
    override suspend fun create(request: ProfileCreationRequest): ProfileMutationResult = coordinator.create(request)
    override suspend fun createSemester(profileId: Long, semester: NewSemesterSpec): ProfileMutationResult =
        coordinator.createSemester(profileId, semester)
    override suspend fun rename(profileId: Long, name: String): ProfileMutationResult =
        coordinator.rename(profileId, name)
    override suspend fun previewDeletion(profileId: Long): ProfileDeletionImpact? = coordinator.previewDeletion(profileId)
    override suspend fun delete(profileId: Long): ProfileMutationResult = coordinator.delete(profileId)
}
