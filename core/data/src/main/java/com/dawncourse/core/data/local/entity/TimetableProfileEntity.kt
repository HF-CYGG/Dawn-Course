package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.TimetableProfile

@Entity(
    tableName = "timetable_profiles",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class TimetableProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String,
    val name: String,
    val activeSemesterId: Long? = null,
    val lastUsedAt: Long = 0L,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

fun TimetableProfileEntity.toDomain() = TimetableProfile(
    id = id,
    uuid = uuid,
    name = name,
    activeSemesterId = activeSemesterId,
    lastUsedAt = lastUsedAt,
    sortOrder = sortOrder,
    archived = archived,
)

fun TimetableProfile.toEntity() = TimetableProfileEntity(
    id = id,
    uuid = uuid,
    name = name,
    activeSemesterId = activeSemesterId,
    lastUsedAt = lastUsedAt,
    sortOrder = sortOrder,
    archived = archived,
)
