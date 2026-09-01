package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.Semester

/**
 * 学期数据库实体
 *
 * 对应数据库中的 "semesters" 表。
 */
@Entity(
    tableName = "semesters",
    foreignKeys = [
        ForeignKey(
            entity = TimetableProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"])],
)
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val name: String,
    val startDate: Long,
    val weekCount: Int,
)

/**
 * 将数据库实体转换为领域模型
 */
fun SemesterEntity.toDomain() = Semester(
    id = id,
    profileId = profileId,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
    isCurrent = false,
)

/**
 * 将领域模型转换为数据库实体
 */
fun Semester.toEntity() = SemesterEntity(
    id = id,
    profileId = profileId,
    name = name,
    startDate = startDate,
    weekCount = weekCount,
)
