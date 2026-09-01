package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.Instant

/** 一条无需依赖 Alarm registry 的按 Key 恢复 Work 计划。 */
data class MuteRecoveryWorkPlan(
    /** 完整 UNMUTE Key。 */
    val key: TriggerKey,
    /** Work 最早执行时刻。 */
    val runAt: Instant
)

/** 启动/开机时由静音责任 Store 独立生成的恢复计划。 */
data class MuteRecoveryBootstrapPlan(
    /** 需要补齐的持久 Work。 */
    val works: List<MuteRecoveryWorkPlan>,
    /** 是否还需展示不能自动清理的用户责任。 */
    val needsUserAttention: Boolean
)

/** 将持久责任转换为重启后恢复入口的纯策略。 */
object MuteRecoveryBootstrapPolicy {
    /** ACTIVE 使用持久结束时间，PENDING 立即恢复，EXHAUSTED 只提示用户。 */
    fun plan(records: Set<MuteSessionRecord>, now: Instant): MuteRecoveryBootstrapPlan {
        val works = records.mapNotNull { record ->
            when (record.status) {
                MuteSessionStatus.ACTIVE -> MuteRecoveryWorkPlan(
                    key = record.key,
                    runAt = record.recoveryAt?.takeIf { value -> value.isAfter(now) } ?: now
                )
                MuteSessionStatus.RECOVERY_PENDING -> MuteRecoveryWorkPlan(record.key, now)
                MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED -> null
            }
        }.sortedBy { work -> TriggerUriCodec.encode(work.key) }
        return MuteRecoveryBootstrapPlan(
            works = works,
            needsUserAttention = records.any { record ->
                record.status == MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED
            }
        )
    }
}
