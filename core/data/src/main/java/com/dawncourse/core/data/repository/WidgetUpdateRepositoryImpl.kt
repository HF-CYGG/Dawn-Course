package com.dawncourse.core.data.repository

import android.content.Context
import android.content.Intent
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 桌面小组件更新仓库的实现类
 *
 * 位于 Data 层，负责实际执行与 Android Framework (Context) 相关的操作。
 * 这样可以使 Domain 层和 UI 层的 ViewModel 保持纯粹，不持有任何 Context 引用，从而避免内存泄漏并遵循 Clean Architecture 架构原则。
 *
 * @property context 应用级上下文，由 Hilt 注入
 */
@Singleton
class WidgetUpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WidgetUpdateRepository {
    
    /**
     * 触发小组件更新广播
     *
     * 发送一个显式广播给 [com.dawncourse.feature.widget.DawnWidgetReceiver]，
     * 通知桌面小组件拉取最新数据并重新渲染。
     */
    override fun triggerUpdate() {
        val intent = Intent("com.dawncourse.widget.FORCE_UPDATE")
        // 设置包名限制广播只发送给当前应用，提升安全性与性能
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}