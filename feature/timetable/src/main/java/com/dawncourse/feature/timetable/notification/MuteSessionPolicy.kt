package com.dawncourse.feature.timetable.notification

/** 应用所有静音会话的纯策略。 */
object MuteSessionPolicy {
    /** 仅在应用从响铃切换，或已有应用会话需要支持重叠课程时持有责任。 */
    fun shouldOwnMuteSession(
        isRingerNormal: Boolean,
        hasActiveOwnedSession: Boolean,
        isRingerVibrate: Boolean = false,
        hasExhaustedResponsibility: Boolean = false
    ): Boolean = isRingerNormal ||
        hasActiveOwnedSession ||
        (isRingerVibrate && hasExhaustedResponsibility)

    /** 只有消费了最后一个应用会话且系统仍保持应用设置的震动时才恢复。 */
    fun shouldRestoreRinger(consumed: Boolean, remainingCount: Int, isVibrate: Boolean): Boolean =
        consumed && remainingCount == 0 && isVibrate
}
