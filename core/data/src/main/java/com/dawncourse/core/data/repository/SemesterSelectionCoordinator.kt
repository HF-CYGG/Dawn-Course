package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行协调 Room 学期事实与 DataStore 当前选择。
 *
 * 所有会同时触及学期记录和选择 ID 的操作都共享同一把锁，避免删除旧学期时清掉并发产生的新选择。
 */
@Singleton
class SemesterSelectionCoordinator @Inject constructor(
    private val semesterDao: SemesterDao,
    private val settingsRepository: SettingsRepository,
    private val mutationGate: OperationalDataMutationGate,
) {
    private val mutationMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentSemester(): Flow<SemesterEntity?> = flow {
        initializeLegacyBridge()
        emitAll(
            settingsRepository.selectedSemesterId.flatMapLatest { selectedSemesterId ->
                selectedSemesterId?.let(semesterDao::observeSemesterById) ?: flowOf(null)
            }
        )
    }

    /** 导出唯一入口：先完成一次性桥接，再返回 v2 显式选择；无效选择规范化为 0。 */
    suspend fun resolveSelectionForExport(): Long = withResolvedSelectionForExport { it }

    /** 回调执行期间持续持有选择锁，确保导出快照不会与删除或切换学期交错。 */
    suspend fun <T> withResolvedSelectionForExport(block: suspend (Long) -> T): T =
        withResolvedSelectionLock(block)

    /** 恢复与导出共用的持锁选择解析入口。 */
    internal suspend fun <T> withResolvedSelectionLock(block: suspend (Long) -> T): T =
        withOperationalMutation {
            initializeLegacyBridgeLocked()
            val selected = settingsRepository.selectedSemesterId.first()
            val resolved = selected?.takeIf { semesterDao.getSemesterById(it) != null } ?: NO_SELECTION_ID
            block(resolved)
        }

    suspend fun insertSemester(semester: SemesterEntity, shouldSelect: Boolean): Long =
        withOperationalMutation {
            val insertedId = semesterDao.insertSemester(semester)
            if (shouldSelect) settingsRepository.selectSemester(insertedId)
            insertedId
        }

    suspend fun updateSemester(semester: SemesterEntity) = withOperationalMutation {
        semesterDao.updateSemester(semester)
    }

    suspend fun deleteSemester(semester: SemesterEntity) = withOperationalMutation {
        val selectedBeforeDelete = settingsRepository.selectedSemesterId.first()
        semesterDao.deleteSemesterAndCourses(semester)
        if (selectedBeforeDelete == semester.id) settingsRepository.clearSelectedSemester()
    }

    suspend fun deleteAllSemesters() = withOperationalMutation {
        semesterDao.deleteAllSemestersAndCourses()
        settingsRepository.clearSelectedSemester()
    }

    suspend fun setCurrentSemester(id: Long) = withOperationalMutation {
        if (id > 0L && semesterDao.getSemesterById(id) != null) {
            settingsRepository.selectSemester(id)
        }
    }

    private suspend fun initializeLegacyBridge() = withOperationalMutation {
        initializeLegacyBridgeLocked()
    }

    /** 遗留协调器虽已无生产消费者，仍必须服从恢复失败后的进程级写入隔离。 */
    private suspend fun <T> withOperationalMutation(block: suspend () -> T): T =
        mutationGate.withMutation {
            mutationMutex.withLock { block() }
        }

    private suspend fun initializeLegacyBridgeLocked() {
        val legacySemesterId = semesterDao.getLegacyCurrentSemesterOnce()?.id
        settingsRepository.initializeSelectedSemesterIfUnset(legacySemesterId)
    }

    private companion object {
        const val NO_SELECTION_ID = 0L
    }
}
