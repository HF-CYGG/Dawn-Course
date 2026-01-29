package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.Semester

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val startDate: Long,
    val weekCount: Int,
    val isCurrent: Boolean
)

fun SemesterEntity.toDomain() = Semester(
    id = id,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
    isCurrent = isCurrent
)

fun Semester.toEntity() = SemesterEntity(
    id = id,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
    isCurrent = isCurrent
)
