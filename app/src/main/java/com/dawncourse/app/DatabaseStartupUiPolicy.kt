package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRuntimeState

/** MainActivity 启动状态的纯决策结果。 */
data class DatabaseStartupUiDecision(
    val keepSplash: Boolean,
    val createDatabaseViewModels: Boolean,
    val showRecovery: Boolean
)

/** 保证 RecoveryRequired 不会触发任何数据库依赖 ViewModel 构造。 */
object DatabaseStartupUiPolicy {
    fun decide(state: DatabaseRuntimeState): DatabaseStartupUiDecision = when (state) {
        DatabaseRuntimeState.Starting -> DatabaseStartupUiDecision(
            keepSplash = true,
            createDatabaseViewModels = false,
            showRecovery = false
        )
        DatabaseRuntimeState.Ready -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = true,
            showRecovery = false
        )
        is DatabaseRuntimeState.RecoveryRequired -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = false,
            showRecovery = true
        )
        DatabaseRuntimeState.StartupBlocked -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = false,
            showRecovery = false
        )
    }
}
