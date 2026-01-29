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
    @Query("SELECT * FROM semesters")
    fun getAllSemesters(): Flow<List<SemesterEntity>>

    /**
     * 查询当前学期
     */
    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentSemester(): Flow<SemesterEntity?>

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
     * 清除其他学期的当前状态标记
     *
     * @param currentId 当前被设置为激活状态的学期 ID，该 ID 不会被清除标记
     */
    @Query("UPDATE semesters SET isCurrent = 0 WHERE id != :currentId")
    suspend fun clearOtherCurrentFlags(currentId: Long)

    /**
     * 删除学期
     */
    @Delete
    suspend fun deleteSemester(semester: SemesterEntity)
}
