package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext

/**
 * 将系统 Surface 的最终副作用与 Profile 切换线性化。
 *
 * action 只会在指定 Profile/学期仍为活动范围时执行；返回 null 表示范围已经失效。
 */
interface ActiveTimetableActionGate {
    suspend fun <T> executeIfActive(
        profileId: Long,
        semesterId: Long,
        action: suspend (ActiveTimetableContext) -> T,
    ): T?
}
