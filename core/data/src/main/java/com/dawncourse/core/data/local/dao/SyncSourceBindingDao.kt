package com.dawncourse.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dawncourse.core.data.local.entity.SyncSourceBindingEntity

@Dao
interface SyncSourceBindingDao {
    @Query("SELECT * FROM sync_source_bindings ORDER BY profileId, semesterId, sourceBindingId")
    suspend fun getAllOnce(): List<SyncSourceBindingEntity>

    @Query("SELECT * FROM sync_source_bindings WHERE profileId = :profileId ORDER BY semesterId, sourceBindingId")
    suspend fun getByProfileOnce(profileId: Long): List<SyncSourceBindingEntity>

    @Query("SELECT * FROM sync_source_bindings WHERE semesterId = :semesterId LIMIT 1")
    suspend fun getBySemesterOnce(semesterId: Long): SyncSourceBindingEntity?

    @Query("SELECT COUNT(*) FROM sync_source_bindings WHERE profileId = :profileId")
    suspend fun countByProfile(profileId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(binding: SyncSourceBindingEntity)

    @Query("DELETE FROM sync_source_bindings WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)

    /** 覆盖导入只清理目标学期的来源绑定，禁止扩大到整个 Profile。 */
    @Query("DELETE FROM sync_source_bindings WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Long)

    @Query("DELETE FROM sync_source_bindings")
    suspend fun deleteAll()
}
