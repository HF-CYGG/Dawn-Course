package com.dawncourse.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableProfileDao {
    @Query("SELECT * FROM timetable_profiles ORDER BY sortOrder, id")
    fun observeAllProfiles(): Flow<List<TimetableProfileEntity>>

    @Query("SELECT * FROM timetable_profiles WHERE id = :profileId")
    fun observeProfileById(profileId: Long): Flow<TimetableProfileEntity?>

    @Query("SELECT * FROM timetable_profiles ORDER BY sortOrder, id")
    suspend fun getAllProfilesOnce(): List<TimetableProfileEntity>

    @Query("SELECT * FROM timetable_profiles ORDER BY sortOrder, id LIMIT 1")
    suspend fun getFirstProfile(): TimetableProfileEntity?

    @Query("SELECT * FROM timetable_profiles WHERE id = :profileId")
    suspend fun getProfileById(profileId: Long): TimetableProfileEntity?

    @Query("SELECT COUNT(*) FROM timetable_profiles")
    suspend fun countProfiles(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: TimetableProfileEntity): Long

    @Query("UPDATE timetable_profiles SET name = :name WHERE id = :profileId")
    suspend fun updateName(profileId: Long, name: String): Int

    @Query("UPDATE timetable_profiles SET activeSemesterId = :semesterId WHERE id = :profileId")
    suspend fun updateActiveSemesterId(profileId: Long, semesterId: Long?): Int

    @Query("UPDATE timetable_profiles SET lastUsedAt = :lastUsedAt WHERE id = :profileId")
    suspend fun updateLastUsedAt(profileId: Long, lastUsedAt: Long): Int

    @Query("UPDATE timetable_profiles SET activeSemesterId = NULL")
    suspend fun clearAllActiveSemesterIds()

    @Query("DELETE FROM timetable_profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: Long): Int

    @Query("DELETE FROM timetable_profiles")
    suspend fun deleteAll()
}
