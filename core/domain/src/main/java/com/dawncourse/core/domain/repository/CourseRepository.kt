package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.Course
import kotlinx.coroutines.flow.Flow

/**
 * 课程数据仓库接口 (Repository Interface)
 *
 * 定义了对课程数据的所有操作契约，遵循 Repository 模式。
 * Domain 层只定义接口，具体的实现（如 Room 数据库操作）由 Data 层提供。
 * 这种设计实现了业务逻辑与数据存储的解耦。
 */
interface CourseRepository {
    /**
     * 获取所有课程列表
     *
     * @return 返回一个 [Flow]，当数据库中的课程数据发生变化时，会自动发射最新的列表数据。
     * 使用 Flow 可以实现响应式 UI 更新。
     */
    fun getAllCourses(): Flow<List<Course>>

    fun getCoursesBySemester(semesterId: Long): Flow<List<Course>>

    suspend fun getCoursesByOriginId(originId: Long): List<Course>
    
    /**
     * 根据 ID 获取单个课程
     *
     * @param id 课程 ID
     * @return 返回对应的 [Course] 对象，如果未找到则返回 null
     */
    suspend fun getCourseById(id: Long): Course?
    
    /**
     * 插入一门新课程
     *
     * @param course 要插入的课程对象
     * @return 返回新插入课程的 ID
     */
    suspend fun insertCourse(course: Course): Long

    /**
     * 批量插入课程
     *
     * @param courses 课程列表
     * @return 插入的主键列表
     */
    suspend fun insertCourses(courses: List<Course>): List<Long>
    
    /**
     * 更新已有课程信息
     *
     * @param course 包含更新后信息的课程对象（必须包含有效的 ID）
     */
    suspend fun updateCourse(course: Course)
    
    /**
     * 删除指定课程
     *
     * @param course 要删除的课程对象
     */
    suspend fun deleteCourse(course: Course)
    
    /**
     * 根据 ID 删除课程
     *
     * @param id 要删除的课程 ID
     */
    suspend fun deleteCourseById(id: Long)
}
