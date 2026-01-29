package com.dawncourse.core.data.local.dao

import androidx.room.*
import com.dawncourse.core.data.local.entity.SemesterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters")
    fun getAllSemesters(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentSemester(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semesters WHERE id = :id")
    suspend fun getSemesterById(id: Long): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterEntity): Long

    @Update
    suspend fun updateSemester(semester: SemesterEntity)
    
    @Query("UPDATE semesters SET isCurrent = 0 WHERE id != :currentId")
    suspend fun clearOtherCurrentFlags(currentId: Long)

    @Delete
    suspend fun deleteSemester(semester: SemesterEntity)
}
