package com.dawncourse.core.domain.repository

/**
 * 桌面小组件更新仓库接口
 *
 * 负责触发桌面小组件的刷新，解耦 ViewModel 对 Context 的依赖。
 * 实现类位于 Data 层，通过 Hilt 依赖注入到 ViewModel 中。
 */
interface WidgetUpdateRepository {
    /**
     * 触发小组件立即刷新
     */
    fun triggerUpdate()
}