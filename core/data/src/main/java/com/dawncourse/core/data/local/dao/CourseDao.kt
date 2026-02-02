package com.dawncourse.core.data.local.dao

import androidx.room.*
import com.dawncourse.core.data.local.entity.CourseEntity
import kotlinx.coroutines.flow.Flow

/**
 * 课程数据访问对象接口 (Data Access Object)
 *
 * 定义了对 `courses` 表的具体 SQL 操作。
 * Room 会在编译时自动生成此接口的实现类。
 * 所有挂起函数 (suspend functions) 均在 IO 线程执行，避免阻塞主线程。
 */
@Dao
interface CourseDao {
    /**
     * 查询所有课程
     *
     * @return 返回 [Flow] 流对象，实时监控数据库表变化。
     */
    @Query("SELECT * FROM courses")
    fun getAllCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses")
    suspend fun getAllCoursesOnce(): List<CourseEntity>

    /**
     * 根据学期 ID 查询课程
     *
     * @param semesterId 学期 ID
     * @return 返回该学期下所有课程的 Flow
     */
    @Query("SELECT * FROM courses WHERE semesterId = :semesterId")
    fun getCoursesBySemester(semesterId: Long): Flow<List<CourseEntity>>

    /**
     * 获取指定学期中所有课程的最大周次
     *
     * @param semesterId 学期 ID
     * @return 最大周次，如果无课程则返回 null
     */
    @Query("SELECT MAX(endWeek) FROM courses WHERE semesterId = :semesterId")
    suspend fun getMaxWeekInSemester(semesterId: Long): Int?

    /**
     * 根据原始 ID 查询课程
     *
     * 用于查找由同一门课程调课分裂出的所有相关课程记录。
     * 例如：一门课程被拆分为前 8 周和后 8 周，它们将共享同一个 originId。
     *
     * @param originId 原始课程 ID
     * @return 匹配的课程实体列表
     */
    @Query("SELECT * FROM courses WHERE originId = :originId")
    suspend fun getCoursesByOriginId(originId: Long): List<CourseEntity>
    
    /**
     * 根据 ID 查询单门课程
     *
     * @param id 课程主键
     * @return 匹配的实体，若不存在返回 null
     */
    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourseById(id: Long): CourseEntity?
    
    /**
     * 插入新课程
     *
     * @param course 要插入的实体
     * @return 新插入行的 RowId (即主键)
     * OnConflictStrategy.REPLACE 表示如果主键冲突则覆盖旧数据
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    /**
     * 批量插入课程
     *
     * @param courses 课程列表
     * @return 插入的主键列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>): List<Long>
    
    /**
     * 更新课程
     *
     * @param course 要更新的实体（根据主键匹配）
     */
    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Update
    suspend fun updateCourses(courses: List<CourseEntity>)
    
    /**
     * 删除课程
     *
     * @param course 要删除的实体
     */
    @Delete
    suspend fun deleteCourse(course: CourseEntity)
    
    /**
     * 根据 ID 删除课程
     *
     * 使用 SQL 语句直接删除，无需先查询出实体对象。
     * @param id 要删除的 ID
     */
    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: Long)

    /**
     * 批量更新所有课程的时长
     *
     * @param duration 新的时长（节数）
     */
    @Transaction
    suspend fun updateAllCoursesDuration(duration: Int) {
        val courses = getAllCoursesOnce()
        if (courses.isEmpty()) return
        val updated = courses
            .groupBy { it.semesterId to it.dayOfWeek }
            .flatMap { (_, dayCourses) ->
                val sorted = dayCourses.sortedWith(
                    compareBy<CourseEntity> { it.startSection }.thenBy { it.id }
                )
                var nextStart = 1
                sorted.map { course ->
                    val updatedCourse = course.copy(
                        startSection = nextStart,
                        duration = duration
                    )
                    nextStart += duration
                    updatedCourse
                }
            }
        updateCourses(updated)
    }
}
