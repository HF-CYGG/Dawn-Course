package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 应用前台对耗尽责任的可观察查询与显式用户操作入口。 */
@Singleton
class MuteRecoveryUserActionController @Inject constructor(
    private val store: MuteSessionPersistence,
    private val coordinator: MuteSessionCoordinator,
    private val scheduler: MuteRecoveryWorkScheduler,
    private val notificationHelper: MuteRecoveryAttention
) {
    /** 只向 UI 暴露需要用户处理的责任，并保持稳定顺序。 */
    fun observeExhaustedRecords(): Flow<List<MuteSessionRecord>> = store.observeRecords().map { records ->
        records.filter { record ->
            record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
        }.sortedBy { record -> record.key.toString() }
    }

    /**
     * 应用/系统事件启动时按持久状态补齐专用 Work；不依赖 ScheduledTriggerRegistry。
     */
    suspend fun reconcilePersistedState() = withContext(Dispatchers.IO) {
        val plan = MuteRecoveryBootstrapPolicy.plan(store.records(), Instant.now())
        plan.works.forEach { work ->
            val enqueued = try {
                scheduler.enqueueAt(work.key, work.runAt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!enqueued) coordinator.requireUserAction(work.key)
        }
        notificationHelper.refreshForCurrentState()
    }

    /** 用户确认 DND 权限可用后立即安排一次受控重试。 */
    suspend fun retry(key: TriggerKey): Boolean = withContext(Dispatchers.IO) {
        if (!coordinator.prepareUserRetry(key)) return@withContext false
        val enqueued = try {
            scheduler.enqueueNow(key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        if (!enqueued) {
            coordinator.restoreExhaustedAfterFailedRetry(key)
        }
        notificationHelper.refreshForCurrentState()
        enqueued
    }

    /** 用户确认已手动恢复或选择放弃责任后清理所有系统入口。 */
    suspend fun release(key: TriggerKey): Boolean = withContext(Dispatchers.IO) {
        if (!coordinator.releaseByUser(key)) return@withContext false
        scheduler.cancel(key)
        notificationHelper.refreshForCurrentState()
        true
    }
}
