package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState

/** MainActivity 启动状态的纯决策结果。 */
data class DatabaseStartupUiDecision(
    val keepSplash: Boolean,
    val createDatabaseViewModels: Boolean,
    /** 仅 Database Starting 且已校验快照时展示；Ready 必须整包切回实时 Root。 */
    val showSnapshot: Boolean,
    val showRecovery: Boolean,
    val showBlocked: Boolean,
)

/**
 * MainActivity 唯一的首帧门禁。
 *
 * 这里故意同时接收两个 Runtime 状态，避免 Splash、快照分支与 ViewModel 创建各自维护
 * 不同条件。任何 Recovery/Blocked 都优先撤下快照，永不构造数据库依赖 ViewModel。
 */
object DatabaseStartupUiPolicy {
    fun decide(
        databaseState: DatabaseRuntimeState,
        snapshotState: StartupSnapshotRuntimeState,
    ): DatabaseStartupUiDecision = when (databaseState) {
        DatabaseRuntimeState.Starting -> when (snapshotState) {
            is StartupSnapshotRuntimeState.Available -> DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = false,
                showSnapshot = true,
                showRecovery = false,
                showBlocked = false,
            )
            StartupSnapshotRuntimeState.Loading,
            StartupSnapshotRuntimeState.Missing -> DatabaseStartupUiDecision(
                keepSplash = true,
                createDatabaseViewModels = false,
                showSnapshot = false,
                showRecovery = false,
                showBlocked = false,
            )
        }
        DatabaseRuntimeState.Ready -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = true,
            showSnapshot = false,
            showRecovery = false,
            showBlocked = false,
        )
        is DatabaseRuntimeState.RecoveryRequired -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = false,
            showSnapshot = false,
            showRecovery = true,
            showBlocked = false,
        )
        DatabaseRuntimeState.StartupBlocked -> DatabaseStartupUiDecision(
            keepSplash = false,
            createDatabaseViewModels = false,
            showSnapshot = false,
            showRecovery = false,
            showBlocked = true,
        )
    }
}
