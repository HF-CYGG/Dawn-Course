package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerDiff
import com.dawncourse.core.domain.model.TriggerOrdering

/** 不依赖 Android 与存储实现的触发器差异算法。 */
object TriggerDiffCalculator {

    /**
     * 计算增量差异。
     *
     * force replay 会将全部 Desired 重新列入 add，用于开机等系统已清空 Alarm 但注册表仍存在的场景。
     */
    fun calculate(
        desired: List<DesiredTrigger>,
        scheduled: List<ScheduledTrigger>,
        forceReplay: Boolean
    ): TriggerDiff {
        val desiredByKey = desired.associateBy { trigger -> trigger.key }
        require(desiredByKey.size == desired.size) { "Desired trigger key 不允许重复" }
        val scheduledByKey = scheduled.associateBy { trigger -> trigger.key }
        require(scheduledByKey.size == scheduled.size) { "Scheduled trigger key 不允许重复" }

        val add = desired.filter { candidate ->
            val current = scheduledByKey[candidate.key]
            forceReplay || current == null || current.triggerAt != candidate.triggerAt
        }.sortedWith(TriggerOrdering.desiredComparator)
        val keep = if (forceReplay) {
            emptyList()
        } else {
            scheduled.filter { current ->
                desiredByKey[current.key]?.triggerAt == current.triggerAt
            }.sortedWith(TriggerOrdering.scheduledComparator)
        }
        val remove = scheduled.filter { current ->
            val target = desiredByKey[current.key]
            target == null || target.triggerAt != current.triggerAt
        }.sortedWith(TriggerOrdering.scheduledComparator)
        return TriggerDiff(add = add, keep = keep, remove = remove)
    }
}
