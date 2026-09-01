package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.SyncSourceBinding

@Entity(
    tableName = "sync_source_bindings",
    foreignKeys = [
        ForeignKey(
            entity = TimetableProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["semesterId"], unique = true),
    ],
)
data class SyncSourceBindingEntity(
    @PrimaryKey val sourceBindingId: String,
    val profileId: Long,
    val semesterId: Long,
    val provider: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun SyncSourceBindingEntity.toDomain() = SyncSourceBinding(
    sourceBindingId = sourceBindingId,
    profileId = profileId,
    semesterId = semesterId,
    provider = SyncProviderType.valueOf(provider),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SyncSourceBinding.toEntity() = SyncSourceBindingEntity(
    sourceBindingId = sourceBindingId,
    profileId = profileId,
    semesterId = semesterId,
    provider = provider.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
