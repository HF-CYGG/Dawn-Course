package com.dawncourse.feature.widget.worker

/**
 * Widget 系统事件恢复计划。
 *
 * 该值对象不依赖 Android API，便于锁定“无实例不唤醒”的资源边界。
 */
internal data class WidgetRestorePlan(
    val schedulePeriodicWork: Boolean,
    val scheduleMidnightAlarm: Boolean,
    val enqueueImmediateUpdate: Boolean
) {
    companion object {
        /**
         * 无 Widget 实例时禁止一切恢复副作用。
         */
        val NONE = WidgetRestorePlan(
            schedulePeriodicWork = false,
            scheduleMidnightAlarm = false,
            enqueueImmediateUpdate = false
        )
    }
}

/**
 * Widget 系统事件恢复策略。
 */
internal object WidgetRestorePolicy {

    /**
     * 根据 Widget 实例存在性生成唯一的恢复动作计划。
     *
     * @param hasWidget 是否存在 DawnWidgetReceiver 对应的系统实例。
     */
    fun planFor(hasWidget: Boolean): WidgetRestorePlan {
        return if (hasWidget) {
            WidgetRestorePlan(
                schedulePeriodicWork = true,
                scheduleMidnightAlarm = true,
                enqueueImmediateUpdate = true
            )
        } else {
            WidgetRestorePlan.NONE
        }
    }
}
