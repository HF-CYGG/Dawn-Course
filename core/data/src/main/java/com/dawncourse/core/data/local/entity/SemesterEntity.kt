package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.Semester

/**
 * 学期数据库实体
 *
 * 对应数据库中的 "semesters" 表。
 */
@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val startDate: Long,
    val weekCount: Int,
    val isCurrent: Boolean
)

/**
 * 将数据库实体转换为领域模型
 */
fun SemesterEntity.toDomain() = Semester(
    id = id,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
    isCurrent = isCurrent
)

/**
 * 将领域模型转换为数据库实体
 */
fun Semester.toEntity() = SemesterEntity(
    id = id,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
    isCurrent = isCurrent
)
