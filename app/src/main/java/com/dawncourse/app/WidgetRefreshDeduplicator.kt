package com.dawncourse.app

/** 单次 Widget 刷新的线性化键；同一前台周期只允许同一调度版本领取一次。 */
internal data class WidgetRefreshRequest(
    val foregroundGeneration: Long,
    val revision: ScheduleRevision,
)

/**
 * 将 Activity 生命周期和 Compose revision effect 收敛为唯一 Widget 刷新入口。
 *
 * 首次 onCreate/onStart 共用 generation 0；只有经历 onStop 后重新前台才进入新 generation。
 * `isCurrent` 允许已取消的旧 LaunchedEffect 在真正调用 Widget 前被最后一次检查拦截。
 */
internal class WidgetRefreshDeduplicator {
    private var foregroundGeneration = 0L
    private var foregroundStarted = false
    private var isForeground = false
    private var currentRequest: WidgetRefreshRequest? = null
    private val claimedRequests = mutableSetOf<WidgetRefreshRequest>()

    /** 进入前台并返回当前 generation；首次进入不递增，后续回前台才递增。 */
    fun onForegroundStarted(): Long {
        if (foregroundStarted && !isForeground) {
            foregroundGeneration += 1
            claimedRequests.clear()
            currentRequest = null
        }
        foregroundStarted = true
        isForeground = true
        return foregroundGeneration
    }

    /** 离开前台时撤销尚未执行的旧请求，避免后台中的旧 effect 迟到更新 Widget。 */
    fun onForegroundStopped() {
        isForeground = false
        currentRequest = null
    }

    /** 领取当前 generation 与 revision 组合的唯一刷新请求；重复键返回 null。 */
    fun claim(revision: ScheduleRevision): WidgetRefreshRequest? {
        val request = WidgetRefreshRequest(
            foregroundGeneration = foregroundGeneration,
            revision = revision,
        )
        if (!claimedRequests.add(request)) return null
        currentRequest = request
        return request
    }

    /** 仅最后领取的请求仍可触发不可合并的 Widget 更新。 */
    fun isCurrent(request: WidgetRefreshRequest): Boolean = currentRequest == request

    /** 只为当前请求执行一次副作用；Activity 仍负责把 Widget 异常降级为 best-effort。 */
    fun runIfCurrent(request: WidgetRefreshRequest, action: () -> Unit) {
        if (isCurrent(request)) action()
    }

    /** 当前请求的 Widget 异常被降级为返回值，调用方可记录而不让其逃出 Compose effect。 */
    fun runIfCurrentCatching(request: WidgetRefreshRequest, action: () -> Unit): Throwable? {
        if (!isCurrent(request)) return null
        return runCatching(action).exceptionOrNull()
    }
}
