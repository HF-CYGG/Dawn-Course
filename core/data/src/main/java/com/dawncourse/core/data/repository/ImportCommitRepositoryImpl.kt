package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.dao.SyncSourceBindingDao
import com.dawncourse.core.data.local.dao.TimetableProfileDao
import com.dawncourse.core.data.local.entity.CourseEntity
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.ImportCommitImpact
import com.dawncourse.core.domain.model.ImportCommitRequest
import com.dawncourse.core.domain.model.ImportCommitResult
import com.dawncourse.core.domain.model.ImportDestination
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.repository.ImportCommitRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 导入候选的原子提交实现。
 *
 * Room 写入全部收敛至单个事务；活动课表选择位于事务外但受与 Profile 切换共用的锁保护，
 * 选择写入失败时会用精确 pre-image 补偿 Room，避免成功页面指向未完成的导入。
 */
@Singleton
class ImportCommitRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val profileDao: TimetableProfileDao,
    private val semesterDao: SemesterDao,
    private val courseDao: CourseDao,
    private val bindingDao: SyncSourceBindingDao,
    private val activeSelectionStore: ActiveProfileSelectionStore,
    private val profileSelectionCoordinator: ProfileSelectionCoordinator,
    private val mutationGate: OperationalDataMutationGate,
) : ImportCommitRepository {

    override suspend fun preview(request: ImportCommitRequest): Result<ImportCommitImpact> = try {
        Result.success(database.withTransaction {
            when (val destination = request.destination) {
                is ImportDestination.NewSemester -> {
                    val profile = requireProfile(destination.profileId)
                    ImportCommitImpact(
                        destination = destination,
                        profileName = profile.name,
                        semesterName = request.semester.name.trim(),
                        coursesToReplace = 0,
                    )
                }

                ImportDestination.NewProfile -> ImportCommitImpact(
                    destination = destination,
                    profileName = request.newProfileName.orEmpty().trim(),
                    semesterName = request.semester.name.trim(),
                    coursesToReplace = 0,
                )

                is ImportDestination.OverwriteSemester -> {
                    val profile = requireProfile(destination.profileId)
                    val semester = requireSemesterInProfile(destination.semesterId, profile.id)
                    ImportCommitImpact(
                        destination = destination,
                        profileName = profile.name,
                        semesterName = semester.name,
                        coursesToReplace = courseDao.getCoursesBySemesterOnce(semester.id).size,
                    )
                }
            }
        })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    override suspend fun commit(request: ImportCommitRequest): ImportCommitResult = try {
        mutationGate.withMutation {
            profileSelectionCoordinator.withProfileMutationLock {
            try {
                validateRequest(request)
                val selectionPreimage = if (request.expectedSourceBindingId == null) {
                    activeSelectionStore.rawActiveProfileId.first()
                } else {
                    null
                }
                val mutation = database.withTransaction { commitInTransaction(request) }
                // 后台自动更新使用已捕获的固定 binding，绝不能把用户从当前 Profile 切回目标 Profile。
                if (request.expectedSourceBindingId == null) {
                    try {
                        activeSelectionStore.selectProfile(mutation.context.profile.id)
                    } catch (selectionFailure: Throwable) {
                        val selectionRollbackFailure = withContext(NonCancellable) {
                            try {
                                activeSelectionStore.restoreRawSelection(selectionPreimage)
                                null
                            } catch (failure: Throwable) {
                                failure
                            }
                        }
                        if (selectionRollbackFailure != null) {
                            selectionFailure.addSuppressed(selectionRollbackFailure)
                            return@withProfileMutationLock ImportCommitResult.Inconsistent(
                                "导入选择写入失败且原选择状态无法确认，已保留新数据等待恢复",
                            )
                        }
                        val rollbackFailure = withContext(NonCancellable) {
                            try {
                                database.withTransaction { rollback(mutation.rollback) }
                                null
                            } catch (failure: Throwable) {
                                failure
                            }
                        }
                        if (rollbackFailure != null) {
                            return@withProfileMutationLock ImportCommitResult.Inconsistent(
                                "导入选择写入失败且数据补偿失败：${rollbackFailure.message.orEmpty()}",
                            )
                        }
                        if (selectionFailure is CancellationException) throw selectionFailure
                        return@withProfileMutationLock ImportCommitResult.Rejected(
                            "导入未完成：无法保存活动课表选择",
                        )
                    }
                }
                ImportCommitResult.Success(
                    activeContext = mutation.context,
                    committedCourseCount = request.courses.size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ImportCommitResult.Rejected(failure.message ?: "导入目标无效，未写入数据")
            }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        ImportCommitResult.Rejected(failure.message ?: "课程数据正在恢复隔离中，未写入导入结果")
    }

    /** Room 事务内按目标写入，并保留刚好足够的回滚信息。 */
    private suspend fun commitInTransaction(request: ImportCommitRequest): DatabaseMutation =
        when (val destination = request.destination) {
            is ImportDestination.NewSemester -> {
                require(request.expectedSourceBindingId == null) { "自动更新不能创建新学期" }
                val profile = requireProfile(destination.profileId)
                val insertedSemester = insertSemester(profile.id, request.semester)
                insertCourses(request.courses, insertedSemester.id)
                profileDao.updateActiveSemesterId(profile.id, insertedSemester.id)
                DatabaseMutation(
                    context = context(profile.copy(activeSemesterId = insertedSemester.id), insertedSemester),
                    rollback = Rollback.NewSemester(
                        profileId = profile.id,
                        previousActiveSemesterId = profile.activeSemesterId,
                        insertedSemesterId = insertedSemester.id,
                    ),
                )
            }

            ImportDestination.NewProfile -> {
                require(request.expectedSourceBindingId == null) { "自动更新不能创建新课表" }
                val name = request.newProfileName.orEmpty().trim()
                require(name.isNotEmpty()) { "新课表名称不能为空" }
                val profile = insertProfile(name)
                val semester = insertSemester(profile.id, request.semester)
                insertCourses(request.courses, semester.id)
                profileDao.updateActiveSemesterId(profile.id, semester.id)
                DatabaseMutation(
                    context = context(profile.copy(activeSemesterId = semester.id), semester),
                    rollback = Rollback.NewProfile(profile.id),
                )
            }

            is ImportDestination.OverwriteSemester -> {
                val profile = requireProfile(destination.profileId)
                val oldSemester = requireSemesterInProfile(destination.semesterId, profile.id)
                requireExpectedBinding(request, profile.id, oldSemester.id)
                val oldCourses = courseDao.getCoursesBySemesterOnce(oldSemester.id)
                val updatedSemester = oldSemester.copy(
                    name = request.semester.name.trim(),
                    startDate = request.semester.startDate,
                    weekCount = request.semester.weekCount,
                )
                courseDao.deleteCoursesBySemester(oldSemester.id)
                semesterDao.updateSemester(updatedSemester)
                insertCourses(request.courses, oldSemester.id)
                val activatesDestination = request.expectedSourceBindingId == null
                if (activatesDestination) profileDao.updateActiveSemesterId(profile.id, oldSemester.id)
                val resultingProfile = if (activatesDestination) {
                    profile.copy(activeSemesterId = oldSemester.id)
                } else {
                    profile
                }
                val resultingActiveSemester = when (resultingProfile.activeSemesterId) {
                    null -> null
                    oldSemester.id -> updatedSemester
                    else -> semesterDao.getSemesterById(resultingProfile.activeSemesterId)
                }
                DatabaseMutation(
                    context = context(resultingProfile, resultingActiveSemester),
                    rollback = Rollback.OverwriteSemester(
                        profileId = profile.id,
                        previousActiveSemesterId = profile.activeSemesterId,
                        oldSemester = oldSemester,
                        oldCourses = oldCourses,
                    ),
                )
            }
        }

    /** 同一选择锁与事务内复核固定 binding；UI 切换不改变已捕获任务的提交目标。 */
    private suspend fun requireExpectedBinding(
        request: ImportCommitRequest,
        profileId: Long,
        semesterId: Long,
    ) {
        val expectedBindingId = request.expectedSourceBindingId ?: return
        val binding = bindingDao.getBySemesterOnce(semesterId)
        require(
            binding != null && binding.sourceBindingId == expectedBindingId &&
                binding.profileId == profileId && binding.semesterId == semesterId,
        ) { "同步来源绑定已变化" }
    }

    /** DataStore 选择失败时，仅恢复本次事务变动的 aggregate。 */
    private suspend fun rollback(rollback: Rollback) {
        when (rollback) {
            is Rollback.NewSemester -> {
                courseDao.deleteCoursesBySemester(rollback.insertedSemesterId)
                semesterDao.deleteSemester(
                    requireNotNull(semesterDao.getSemesterById(rollback.insertedSemesterId)),
                )
                profileDao.updateActiveSemesterId(rollback.profileId, rollback.previousActiveSemesterId)
            }

            is Rollback.NewProfile -> {
                courseDao.deleteCoursesByProfile(rollback.profileId)
                semesterDao.deleteByProfile(rollback.profileId)
                profileDao.deleteById(rollback.profileId)
            }

            is Rollback.OverwriteSemester -> {
                courseDao.deleteCoursesBySemester(rollback.oldSemester.id)
                semesterDao.updateSemester(rollback.oldSemester)
                if (rollback.oldCourses.isNotEmpty()) courseDao.insertCourses(rollback.oldCourses)
                profileDao.updateActiveSemesterId(rollback.profileId, rollback.previousActiveSemesterId)
            }
        }
    }

    private suspend fun insertProfile(name: String): TimetableProfileEntity {
        val sortOrder = (profileDao.getAllProfilesOnce().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val profile = TimetableProfileEntity(
            uuid = UUID.randomUUID().toString(),
            name = name,
            sortOrder = sortOrder,
        )
        return profile.copy(id = profileDao.insert(profile))
    }

    private suspend fun insertSemester(profileId: Long, spec: NewSemesterSpec): SemesterEntity {
        val semester = SemesterEntity(
            profileId = profileId,
            name = spec.name.trim(),
            startDate = spec.startDate,
            weekCount = spec.weekCount,
        )
        return semester.copy(id = semesterDao.insertSemester(semester))
    }

    /** 忽略候选对象携带的旧 ID 与 semesterId，防止跨 Profile 写入。 */
    private suspend fun insertCourses(courses: List<com.dawncourse.core.domain.model.Course>, semesterId: Long) {
        if (courses.isEmpty()) return
        // 外部导入路径：命中业务键唯一索引的重复行直接忽略，作为解析层去重之外的兜底。
        courseDao.insertCoursesIgnoringDuplicates(
            courses.map { course ->
                course.copy(
                    id = 0L,
                    semesterId = semesterId,
                    // 外部导入不是调课片段，禁止携带指向其他 Profile 的 originId。
                    originId = 0L,
                    isModified = false,
                ).toEntity()
            },
        )
    }

    private suspend fun requireProfile(profileId: Long): TimetableProfileEntity =
        requireNotNull(profileDao.getProfileById(profileId)) { "目标课表不存在" }

    private suspend fun requireSemesterInProfile(semesterId: Long, profileId: Long): SemesterEntity {
        val semester = requireNotNull(semesterDao.getSemesterById(semesterId)) { "目标学期不存在" }
        require(semester.profileId == profileId) { "目标学期不属于指定课表" }
        return semester
    }

    private fun validateRequest(request: ImportCommitRequest) {
        require(request.semester.name.trim().isNotEmpty()) { "学期名称不能为空" }
        require(request.semester.weekCount > 0) { "学期周数必须为正数" }
        request.courses.forEach { course ->
            require(course.name.isNotBlank()) { "课程名称不能为空" }
            require(course.dayOfWeek in 1..7) { "课程星期无效" }
            require(course.startSection > 0 && course.duration > 0) { "课程节次无效" }
            require(course.startWeek > 0 && course.endWeek >= course.startWeek) { "课程周次无效" }
            require(course.endWeek <= request.semester.weekCount) { "课程周次超出学期范围" }
        }
    }

    private fun context(profile: TimetableProfileEntity, semester: SemesterEntity?): ActiveTimetableContext =
        ActiveTimetableContext(profile = profile.toDomain(), semester = semester?.toDomain())

    private data class DatabaseMutation(
        val context: ActiveTimetableContext,
        val rollback: Rollback,
    )

    private sealed interface Rollback {
        data class NewSemester(
            val profileId: Long,
            val previousActiveSemesterId: Long?,
            val insertedSemesterId: Long,
        ) : Rollback

        data class NewProfile(val profileId: Long) : Rollback

        data class OverwriteSemester(
            val profileId: Long,
            val previousActiveSemesterId: Long?,
            val oldSemester: SemesterEntity,
            val oldCourses: List<CourseEntity>,
        ) : Rollback
    }
}
