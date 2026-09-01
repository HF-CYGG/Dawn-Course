package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncSourceBinding
import com.dawncourse.core.domain.model.TimetableProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 恢复开始前采集的完整可补偿状态。 */
internal data class BackupRestorePreImage(
    val settings: AppSettings,
    val semesters: List<Semester>,
    val courses: List<Course>,
    val selectedSemesterId: Long?,
    /** v6 起运行时唯一选择；旧 selectedSemesterId 仅用于 v1/v2 桥接兼容。 */
    val activeProfileId: Long?,
    val profiles: List<TimetableProfile> = emptyList(),
    val sourceBindings: List<SyncSourceBinding> = emptyList(),
)

/** 新状态未能应用，但旧状态已经完整补偿恢复。 */
internal class BackupRestoreRolledBackException(cause: Throwable) :
    IllegalStateException("备份恢复失败，原数据已完整还原", cause)

/** 新状态失败且旧状态无法完整补偿，必须进入 RecoveryRequired。 */
internal class BackupRecoveryRequiredException(
    cause: Throwable,
    compensationFailures: List<Throwable>
) : IllegalStateException("备份恢复补偿失败，必须进入数据恢复流程", cause) {
    init {
        compensationFailures.forEach(::addSuppressed)
    }
}

/**
 * Room 与 DataStore 不能形成同一事务；这里明确实现提交后的补偿协议。
 */
internal object CompensatingBackupRestore {
    suspend fun execute(
        preImage: BackupRestorePreImage,
        replacement: ValidatedBackupRestore,
        replaceRoom: suspend (
            List<TimetableProfile>,
            List<Semester>,
            List<Course>,
            List<SyncSourceBinding>,
        ) -> Unit,
        replaceSettingsAndSelection: suspend (AppSettings, Long?, Long?) -> Unit,
        persistRecoveryRequired: suspend () -> Unit
    ): Result<Unit> {
        try {
            replaceRoom(
                replacement.profiles,
                replacement.semesters,
                replacement.courses,
                replacement.sourceBindings,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(error)
        }

        try {
            replaceSettingsAndSelection(
                replacement.settings,
                replacement.selectedSemesterId,
                replacement.activeProfileId,
            )
            return Result.success(Unit)
        } catch (applyFailure: Throwable) {
            val compensationFailures = withContext(NonCancellable) {
                buildList {
                    try {
                        replaceRoom(
                            preImage.profiles,
                            preImage.semesters,
                            preImage.courses,
                            preImage.sourceBindings,
                        )
                    } catch (error: Throwable) {
                        add(error)
                    }
                    try {
                        replaceSettingsAndSelection(
                            preImage.settings,
                            preImage.selectedSemesterId,
                            preImage.activeProfileId,
                        )
                    } catch (error: Throwable) {
                        add(error)
                    }
                }
            }

            if (compensationFailures.isEmpty()) {
                if (applyFailure is CancellationException) throw applyFailure
                return Result.failure(BackupRestoreRolledBackException(applyFailure))
            }

            val recoveryRequired = BackupRecoveryRequiredException(applyFailure, compensationFailures)
            withContext(NonCancellable) {
                try {
                    persistRecoveryRequired()
                } catch (markerFailure: Throwable) {
                    recoveryRequired.addSuppressed(markerFailure)
                }
            }
            return Result.failure(recoveryRequired)
        }
    }
}
