package com.dawncourse.core.data.local.dao

import androidx.room.*
import com.dawncourse.core.data.local.entity.SemesterEntity
import kotlinx.coroutines.flow.Flow

/**
 * 学期数据访问对象 (DAO)
 *
 * 提供对 semesters 表的底层数据库操作。
 */
@Dao
interface SemesterDao {
    /**
     * 查询所有学期
     */
    @Query("SELECT * FROM semesters ORDER BY profileId, startDate, id")
    fun getAllSemesters(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE profileId = :profileId ORDER BY startDate, id")
    fun getSemestersByProfile(profileId: Long): Flow<List<SemesterEntity>>

    /**
     * 一次性查询所有学期（非 Flow）
     *
     * 用于备份/恢复等一次性读取场景。
     */
    @Query("SELECT * FROM semesters ORDER BY profileId, startDate, id")
    suspend fun getAllSemestersOnce(): List<SemesterEntity>

    @Query("SELECT * FROM semesters WHERE profileId = :profileId ORDER BY startDate, id")
    suspend fun getSemestersByProfileOnce(profileId: Long): List<SemesterEntity>

    @Query("SELECT COUNT(*) FROM semesters WHERE profileId = :profileId")
    suspend fun countByProfile(profileId: Long): Int

    /**
     * 一次性读取旧版当前学期标记。
     *
     * 该查询只用于 selected_semester_id 缺键时的首次桥接；多个旧标记按最小 ID 稳定选择。
     */
    @Query(
        "SELECT semesters.* FROM semesters " +
            "INNER JOIN timetable_profiles ON timetable_profiles.activeSemesterId = semesters.id " +
            "ORDER BY timetable_profiles.sortOrder, timetable_profiles.id LIMIT 1",
    )
    suspend fun getLegacyCurrentSemesterOnce(): SemesterEntity?

    /** 根据 ID 观察学期，供 DataStore 选择流切换。 */
    @Query("SELECT * FROM semesters WHERE id = :id")
    fun observeSemesterById(id: Long): Flow<SemesterEntity?>

    /**
     * 根据 ID 查询学期
     */
    @Query("SELECT * FROM semesters WHERE id = :id")
    suspend fun getSemesterById(id: Long): SemesterEntity?

    /**
     * 插入学期
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterEntity): Long

    /**
     * 更新学期
     */
    @Update
    suspend fun updateSemester(semester: SemesterEntity)
    
    /**
     * 删除学期
     */
    @Delete
    suspend fun deleteSemester(semester: SemesterEntity)

    @Query("DELETE FROM courses WHERE semesterId = :semesterId")
    suspend fun deleteCoursesForSemester(semesterId: Long)

    @Transaction
    suspend fun deleteSemesterAndCourses(semester: SemesterEntity) {
        deleteCoursesForSemester(semester.id)
        deleteSemester(semester)
    }
    /**
     * 删除所有学期
     */
    @Query("DELETE FROM semesters")
    suspend fun deleteAllSemesters()

    /** 显式删除一个 Profile 的学期；调用方必须先删除绑定与课程。 */
    @Query("DELETE FROM semesters WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)

    @Query("DELETE FROM courses")
    suspend fun deleteAllCoursesForSemesterCleanup()

    @Transaction
    suspend fun deleteAllSemestersAndCourses() {
        deleteAllCoursesForSemesterCleanup()
        deleteAllSemesters()
    }
}
