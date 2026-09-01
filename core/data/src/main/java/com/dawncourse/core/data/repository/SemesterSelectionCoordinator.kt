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
    private val settingsRepository: SettingsRepository
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
        mutationMutex.withLock {
            initializeLegacyBridgeLocked()
            val selected = settingsRepository.selectedSemesterId.first()
            val resolved = selected?.takeIf { semesterDao.getSemesterById(it) != null } ?: NO_SELECTION_ID
            block(resolved)
        }

    suspend fun insertSemester(semester: SemesterEntity, shouldSelect: Boolean): Long =
        mutationMutex.withLock {
            val insertedId = semesterDao.insertSemester(semester)
            if (shouldSelect) settingsRepository.selectSemester(insertedId)
            insertedId
        }

    suspend fun updateSemester(semester: SemesterEntity) = mutationMutex.withLock {
        semesterDao.updateSemester(semester)
    }

    suspend fun deleteSemester(semester: SemesterEntity) = mutationMutex.withLock {
        val selectedBeforeDelete = settingsRepository.selectedSemesterId.first()
        semesterDao.deleteSemesterAndCourses(semester)
        if (selectedBeforeDelete == semester.id) settingsRepository.clearSelectedSemester()
    }

    suspend fun deleteAllSemesters() = mutationMutex.withLock {
        semesterDao.deleteAllSemestersAndCourses()
        settingsRepository.clearSelectedSemester()
    }

    suspend fun setCurrentSemester(id: Long) = mutationMutex.withLock {
        if (id > 0L && semesterDao.getSemesterById(id) != null) {
            settingsRepository.selectSemester(id)
        }
    }

    private suspend fun initializeLegacyBridge() = mutationMutex.withLock {
        initializeLegacyBridgeLocked()
    }

    private suspend fun initializeLegacyBridgeLocked() {
        val legacySemesterId = semesterDao.getLegacyCurrentSemesterOnce()?.id
        settingsRepository.initializeSelectedSemesterIfUnset(legacySemesterId)
    }

    private companion object {
        const val NO_SELECTION_ID = 0L
    }
}
