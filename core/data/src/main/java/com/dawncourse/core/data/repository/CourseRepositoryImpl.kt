package com.dawncourse.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Provider

/**
 * 课程仓库实现类 (Repository Implementation)
 *
 * 实现了 Domain 层定义的 [CourseRepository] 接口。
 * 负责协调数据的获取和存储，当前主要从本地 Room 数据库获取数据。
 *
 * @property courseDao 注入的 DAO 对象，用于操作数据库
 */
class CourseRepositoryImpl @Inject constructor(
    private val courseDao: CourseDao,
    private val database: AppDatabase,
    private val profileSelectionCoordinator: Provider<ProfileSelectionCoordinator>,
    private val mutationGate: OperationalDataMutationGate,
) : CourseRepository {

    /**
     * 获取所有课程
     * 将数据库实体 [CourseEntity] 列表映射转换为领域模型 [Course] 列表。
     */
    override fun getAllCourses(): Flow<List<Course>> {
        return courseDao.getAllCourses().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getCoursesBySemester(semesterId: Long): Flow<List<Course>> {
        return courseDao.getCoursesBySemester(semesterId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * 根据 ID 获取课程
     * 将数据库实体转换为领域模型。
     */
    override suspend fun getCourseById(id: Long): Course? {
        return courseDao.getCourseById(id)?.toDomain()
    }

    /**
     * 插入课程
     * 将领域模型转换为数据库实体并保存。
     */
    override suspend fun insertCourse(course: Course): Long {
        return mutationGate.withMutation { courseDao.insertCourse(course.toEntity()) }
    }

    override suspend fun insertCourses(courses: List<Course>): List<Long> {
        return mutationGate.withMutation { courseDao.insertCourses(courses.map { it.toEntity() }) }
    }

    override suspend fun saveCoursesAtomically(
        courses: List<Course>,
        editingCourseId: Long
    ): CourseRepository.AtomicSaveResult = saveWithConstraintRejection {
        mutationGate.withMutation {
            database.withTransaction { saveCoursesLocked(courses, editingCourseId) }
        }
    }

    override suspend fun saveCoursesIfScopeActive(
        profileId: Long,
        semesterId: Long,
        courses: List<Course>,
        editingCourseId: Long,
    ): CourseRepository.AtomicSaveResult = saveWithConstraintRejection {
        profileSelectionCoordinator.get().withActiveScopeTransaction(
            profileId = profileId,
            semesterId = semesterId,
        ) {
            saveCoursesLocked(courses, editingCourseId)
        } ?: CourseRepository.AtomicSaveResult.Rejected("活动课表或学期已变化，请重新打开后再试")
    }

    override suspend fun deleteCoursesIfScopeActive(
        profileId: Long,
        semesterId: Long,
        courseIds: Set<Long>,
    ): CourseRepository.AtomicSaveResult = profileSelectionCoordinator.get().withActiveScopeTransaction(
        profileId = profileId,
        semesterId = semesterId,
    ) {
        if (courseIds.isEmpty()) return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Success
        val current = courseIds.map { id -> courseDao.getCourseById(id) }
        if (current.any { entity -> entity == null || entity.semesterId != semesterId }) {
            return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected(
                "课程已变化或不属于当前学期，请刷新后重试"
            )
        }
        courseIds.forEach { id -> courseDao.deleteCourseById(id) }
        CourseRepository.AtomicSaveResult.Success
    } ?: CourseRepository.AtomicSaveResult.Rejected("活动课表或学期已变化，请重新打开后再试")

    override suspend fun restoreCoursesIfScopeActive(
        profileId: Long,
        semesterId: Long,
        courses: List<Course>,
    ): CourseRepository.AtomicSaveResult = saveWithConstraintRejection {
        profileSelectionCoordinator.get().withActiveScopeTransaction(
            profileId = profileId,
            semesterId = semesterId,
        ) {
            if (courses.isEmpty() || courses.any { course -> course.semesterId != semesterId || course.id <= 0L }) {
                return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected(
                    "待恢复课程不属于当前学期"
                )
            }
            if (courses.any { course -> courseDao.getCourseById(course.id) != null }) {
                return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected(
                    "课程 ID 已被占用，请刷新后重试"
                )
            }
            courseDao.insertCourses(courses.map { course -> course.toEntity() })
            CourseRepository.AtomicSaveResult.Success
        } ?: CourseRepository.AtomicSaveResult.Rejected("活动课表或学期已变化，请重新打开后再试")
    }

    override suspend fun undoRescheduleIfScopeActive(
        profileId: Long,
        semesterId: Long,
        originId: Long,
    ): CourseRepository.AtomicSaveResult = saveWithConstraintRejection {
        profileSelectionCoordinator.get().withActiveScopeTransaction(
            profileId = profileId,
            semesterId = semesterId,
        ) {
            if (originId <= 0L) {
                return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected("课程族标识无效")
            }
            val siblings = courseDao.getCoursesByOriginId(semesterId, originId).map { entity -> entity.toDomain() }
            if (siblings.isEmpty()) {
                return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected(
                    "调课记录已变化，请刷新后重试"
                )
            }
            if (siblings.size <= 1 && siblings.none { course -> course.isModified }) {
                return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Success
            }
            val template = siblings.firstOrNull { course -> !course.isModified }
                ?: return@withActiveScopeTransaction CourseRepository.AtomicSaveResult.Rejected(
                    "缺少原课程片段，无法安全撤销调课"
                )
            val allWeeks = siblings.flatMapTo(linkedSetOf()) { course ->
                (course.startWeek..course.endWeek).filter { week ->
                    when (course.weekType) {
                        Course.WEEK_TYPE_ODD -> week % 2 != 0
                        Course.WEEK_TYPE_EVEN -> week % 2 == 0
                        else -> true
                    }
                }
            }
            val restored = convertWeeksToSegments(allWeeks).map { (start, end, type) ->
                template.copy(
                    id = 0L,
                    startWeek = start,
                    endWeek = end,
                    weekType = type,
                    isModified = false,
                    note = "",
                    originId = 0L,
                )
            }
            siblings.forEach { course -> courseDao.deleteCourseById(course.id) }
            courseDao.insertCourses(restored.map { course -> course.toEntity() })
            CourseRepository.AtomicSaveResult.Success
        } ?: CourseRepository.AtomicSaveResult.Rejected("活动课表或学期已变化，请重新打开后再试")
    }

    /** 调用方必须已经持有 Room transaction。 */
    private suspend fun saveCoursesLocked(
        courses: List<Course>,
        editingCourseId: Long,
    ): CourseRepository.AtomicSaveResult {
        if (courses.isEmpty()) {
            return CourseRepository.AtomicSaveResult.Rejected("未选择任何周次，无法保存课程")
        }
        val semesterIds = courses.map { it.semesterId }.toSet()
        if (semesterIds.size != 1 || semesterIds.single() <= 0L) {
            return CourseRepository.AtomicSaveResult.Rejected("未选择有效学期，无法保存课程")
        }
        val semesterId = semesterIds.single()
        if (database.semesterDao().getSemesterById(semesterId) == null) {
            return CourseRepository.AtomicSaveResult.Rejected(
                "目标学期不存在或已被删除，请重新选择学期"
            )
        }
        if (editingCourseId > 0L && courseDao.getCourseById(editingCourseId) == null) {
            return CourseRepository.AtomicSaveResult.Rejected(
                "原课程已被删除，请刷新后重试"
            )
        }

        if (editingCourseId > 0L && courses.size == 1) {
            courseDao.updateCourse(courses.single().copy(id = editingCourseId).toEntity())
        } else {
            if (editingCourseId > 0L) courseDao.deleteCourseById(editingCourseId)
            courseDao.insertCourses(courses.map { it.copy(id = 0L).toEntity() })
        }
        return CourseRepository.AtomicSaveResult.Success
    }

    /** 仅把完整 Room 事务抛出的唯一约束异常转换为可恢复的业务拒绝。 */
    private suspend fun saveWithConstraintRejection(
        action: suspend () -> CourseRepository.AtomicSaveResult,
    ): CourseRepository.AtomicSaveResult = try {
        action()
    } catch (failure: SQLiteConstraintException) {
        CourseRepository.AtomicSaveResult.Rejected("存在重复课程或课程已变化，请刷新后重试")
    }

    private fun convertWeeksToSegments(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        val pending = weeks.toMutableSet()
        val segments = mutableListOf<Triple<Int, Int, Int>>()
        while (pending.isNotEmpty()) {
            val first = pending.minOrNull() ?: break
            var endAll = first
            while (pending.contains(endAll + 1)) endAll += 1
            var endParity = first
            while (pending.contains(endParity + 2)) endParity += 2
            val allCount = endAll - first + 1
            val parityCount = (endParity - first) / 2 + 1
            if (allCount >= parityCount) {
                segments += Triple(first, endAll, Course.WEEK_TYPE_ALL)
                (first..endAll).forEach(pending::remove)
            } else {
                val type = if (first % 2 == 0) Course.WEEK_TYPE_EVEN else Course.WEEK_TYPE_ODD
                segments += Triple(first, endParity, type)
                (first..endParity step 2).forEach(pending::remove)
            }
        }
        return segments
    }

    /**
     * 更新课程
     */
    override suspend fun updateCourse(course: Course) {
        mutationGate.withMutation { courseDao.updateCourse(course.toEntity()) }
    }

    /**
     * 删除课程
     */
    override suspend fun deleteCourse(course: Course) {
        mutationGate.withMutation { courseDao.deleteCourse(course.toEntity()) }
    }

    /**
     * 根据 ID 删除课程
     */
    override suspend fun deleteCourseById(id: Long) {
        mutationGate.withMutation { courseDao.deleteCourseById(id) }
    }

    /**
     * 批量更新所有课程的时长
     *
     * 调用 DAO 层进行事务处理，确保原子性。
     */
    override suspend fun updateAllCoursesDuration(duration: Int) {
        mutationGate.withMutation { courseDao.updateAllCoursesDuration(duration) }
    }

    override suspend fun getMaxWeekInSemester(semesterId: Long): Int {
        return courseDao.getMaxWeekInSemester(semesterId) ?: 0
    }

    override suspend fun deleteCoursesBySemester(semesterId: Long) {
        mutationGate.withMutation { courseDao.deleteCoursesBySemester(semesterId) }
    }

    override suspend fun deleteAllCourses() {
        mutationGate.withMutation { courseDao.deleteAllCourses() }
    }
}
