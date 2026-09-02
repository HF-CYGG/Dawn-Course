package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerOrdering
import com.dawncourse.core.domain.usecase.TriggerDiffCalculator
import javax.inject.Inject
import javax.inject.Singleton

/** Desired 与系统 Scheduled 注册表之间的单次增量对账器。 */
@Singleton
class TriggerReconciler @Inject constructor(
    private val registry: ScheduledTriggerRegistry,
    private val alarmGateway: TriggerAlarmGateway
) {
    /**
     * 执行单次对账。
     *
     * [protectedUnmuteKeys] 仅保留应用已经建立的静音恢复责任，避免关闭自动静音后把安全 UNMUTE 一并删掉。
     * [retainedUnmuteAlarmKeys] 只包含仍处于 ACTIVE、尚未交给专用 Worker 的恢复边界。
     */
    suspend fun reconcile(
        desired: List<DesiredTrigger>,
        forceReplay: Boolean,
        protectedUnmuteKeys: Set<TriggerKey> = emptySet(),
        retainedUnmuteAlarmKeys: Set<TriggerKey> = emptySet()
    ) {
        val registrySnapshot = registry.read()
        // 同一逻辑 Key 只要存在损坏证据就不信任 keep，强制 Desired 覆盖系统身份。
        val current = registrySnapshot.records.filterNot { trigger ->
            trigger.key in registrySnapshot.corruptedKeys
        }
        val desiredByKey = desired.associateBy { trigger -> trigger.key }.toMutableMap()
        current.filter { trigger ->
            trigger.key.kind == TriggerKind.UNMUTE && trigger.key in retainedUnmuteAlarmKeys
        }.forEach { trigger ->
            desiredByKey.putIfAbsent(trigger.key, DesiredTrigger(trigger.key, trigger.triggerAt))
        }
        val effectiveDesired = desiredByKey.values.sortedWith(TriggerOrdering.desiredComparator)
        val diff = TriggerDiffCalculator.calculate(effectiveDesired, current, forceReplay)
        val applied = mutableMapOf<TriggerKey, ScheduledTrigger>()

        // 先确保 UNMUTE/REMINDER/MUTE 新状态存在；同 Key 时间变化会直接覆盖旧 PendingIntent。
        diff.add.forEach { trigger ->
            val precision = alarmGateway.schedule(trigger)
            applied[trigger.key] = ScheduledTrigger(trigger.key, trigger.triggerAt, precision)
        }

        val replacementKeys = diff.add.mapTo(mutableSetOf()) { trigger -> trigger.key }
        diff.remove.filterNot { trigger -> trigger.key in replacementKeys }.forEach { stale ->
            alarmGateway.cancel(stale.key)
        }
        registrySnapshot.corruptedKeys
            .filterNot { key -> key in replacementKeys || key in protectedUnmuteKeys }
            .sortedWith(TriggerOrdering.keyComparator)
            .forEach(alarmGateway::cancel)

        val keptByKey = diff.keep.associateBy { trigger -> trigger.key }
        val finalSnapshot = effectiveDesired.map { trigger ->
            applied[trigger.key]
                ?: keptByKey[trigger.key]
                ?: current.firstOrNull { value ->
                    value.key == trigger.key && value.triggerAt == trigger.triggerAt
                }
                ?: error("触发器对账结果缺少已下发记录")
        }.sortedWith(TriggerOrdering.scheduledComparator)

        // 所有系统操作成功后才一次性提交注册表，失败由 Worker 转换为 retry。
        registry.replaceAll(finalSnapshot, registrySnapshot.corruptedEntryNames)
    }
}
