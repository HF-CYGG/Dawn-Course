package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 学期事实写入与 Profile 选择共用 [ProfileSelectionCoordinator]，不再写遗留选择键。 */
class SemesterRepositoryImpl @Inject constructor(
    private val semesterDao: SemesterDao,
    private val profileRepository: TimetableProfileRepository,
    private val profileSelectionCoordinator: ProfileSelectionCoordinator,
) : SemesterRepository {
    override fun getAllSemesters(): Flow<List<Semester>> = semesterDao.getAllSemesters().map { rows ->
        rows.map { it.toDomain().copy(isCurrent = false) }
    }

    override fun getCurrentSemester(): Flow<Semester?> = profileRepository.observeActiveContext().map { context ->
        context?.semester?.copy(isCurrent = false)
    }

    override suspend fun getSemesterById(id: Long): Semester? =
        semesterDao.getSemesterById(id)?.toDomain()?.copy(isCurrent = false)

    override suspend fun insertSemester(semester: Semester): Long =
        profileSelectionCoordinator.insertSemester(semester)

    override suspend fun updateSemester(semester: Semester) {
        profileSelectionCoordinator.updateSemester(semester)
    }

    override suspend fun deleteSemester(semester: Semester) {
        profileSelectionCoordinator.deleteSemester(semester)
    }

    override suspend fun deleteAllSemesters() {
        profileSelectionCoordinator.deleteAllSemesters()
    }

    override suspend fun setCurrentSemester(id: Long) {
        profileSelectionCoordinator.setCurrentSemester(id)
    }
}
