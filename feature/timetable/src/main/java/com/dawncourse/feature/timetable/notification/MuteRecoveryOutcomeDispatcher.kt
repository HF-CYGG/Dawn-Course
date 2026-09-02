package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Receiver 恢复结果到持久 Work/用户警示的可测试编排器。 */
@Singleton
class MuteRecoveryOutcomeDispatcher @Inject constructor(
    private val coordinator: MuteSessionCoordinator,
    private val scheduler: MuteRecoveryWorkScheduler,
    private val notificationHelper: MuteRecoveryAttention
) {
    /** 入队必须等待 Operation.result；失败时责任升级为显式用户处理态。 */
    suspend fun dispatch(key: TriggerKey, outcome: MuteRecoveryOutcome) {
        when (outcome) {
            is MuteRecoveryOutcome.RetryRequired -> {
                val enqueued = try {
                    scheduler.enqueueRetry(key, outcome.attempt)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
                if (!enqueued) coordinator.requireUserAction(key)
                notificationHelper.refreshForCurrentState()
            }
            MuteRecoveryOutcome.Recovered,
            MuteRecoveryOutcome.ResponsibilityReleased,
            MuteRecoveryOutcome.NoAction -> {
                scheduler.cancel(key)
                notificationHelper.refreshForCurrentState()
            }
            MuteRecoveryOutcome.Exhausted -> notificationHelper.refreshForCurrentState()
        }
    }
}
