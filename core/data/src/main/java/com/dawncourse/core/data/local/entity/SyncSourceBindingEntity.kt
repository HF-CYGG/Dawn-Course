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

/** 读取持久化数据时容忍未来版本写入的未知 Provider，避免整个备份流程失败。 */
fun SyncSourceBindingEntity.toDomainOrNull(): SyncSourceBinding? {
    val providerType = SyncProviderType.entries.firstOrNull { it.name == provider } ?: return null
    return SyncSourceBinding(
        sourceBindingId = sourceBindingId,
        profileId = profileId,
        semesterId = semesterId,
        provider = providerType,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** 仅供已由当前应用写入且可信的来源绑定读取路径使用。 */
fun SyncSourceBindingEntity.toDomain(): SyncSourceBinding = requireNotNull(toDomainOrNull()) {
    "未知同步来源提供者：$provider"
}

/** 导出层使用的纯投影结果，便于在不打开 Room 的情况下验证未知 Provider 的隔离行为。 */
internal data class BackupBindingProjection(
    val bindings: List<SyncSourceBinding>,
    val invalidBindingCount: Int,
)

/** 未知 Provider 只影响其自身 binding，不能阻断课程、学期和 Profile 的备份快照。 */
internal fun List<SyncSourceBindingEntity>.projectForBackupExport(): BackupBindingProjection {
    val bindings = mapNotNull { it.toDomainOrNull() }
    return BackupBindingProjection(
        bindings = bindings,
        invalidBindingCount = size - bindings.size,
    )
}

fun SyncSourceBinding.toEntity() = SyncSourceBindingEntity(
    sourceBindingId = sourceBindingId,
    profileId = profileId,
    semesterId = semesterId,
    provider = provider.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
