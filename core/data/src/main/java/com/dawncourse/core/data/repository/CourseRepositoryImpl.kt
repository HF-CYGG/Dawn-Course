package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 课程仓库实现类 (Repository Implementation)
 *
 * 实现了 Domain 层定义的 [CourseRepository] 接口。
 * 负责协调数据的获取和存储，当前主要从本地 Room 数据库获取数据。
 *
 * @property courseDao 注入的 DAO 对象，用于操作数据库
 */
class CourseRepositoryImpl @Inject constructor(
    private val courseDao: CourseDao
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
        return courseDao.insertCourse(course.toEntity())
    }

    /**
     * 更新课程
     */
    override suspend fun updateCourse(course: Course) {
        courseDao.updateCourse(course.toEntity())
    }

    /**
     * 删除课程
     */
    override suspend fun deleteCourse(course: Course) {
        courseDao.deleteCourse(course.toEntity())
    }

    /**
     * 根据 ID 删除课程
     */
    override suspend fun deleteCourseById(id: Long) {
        courseDao.deleteCourseById(id)
    }
}
