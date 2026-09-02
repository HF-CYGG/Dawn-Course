package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState

/** MainActivity 启动状态的纯决策结果。 */
data class DatabaseStartupUiDecision(
    val keepSplash: Boolean,
    val createDatabaseViewModels: Boolean,
    /** Database Starting 或实时 Root 仍 Loading 时展示已校验快照；不得与实时 UI 合并。 */
    val showSnapshot: Boolean,
    /** 仅数据库 Ready 且实时 MainUiState.Success 时允许创建 NavHost/ReportDrawn 的 live Root。 */
    val showLiveRoot: Boolean = false,
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
        liveRootReady: Boolean = false,
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
        DatabaseRuntimeState.Ready -> when {
            liveRootReady -> DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = true,
                showSnapshot = false,
                showLiveRoot = true,
                showRecovery = false,
                showBlocked = false,
            )
            snapshotState is StartupSnapshotRuntimeState.Available -> DatabaseStartupUiDecision(
                keepSplash = false,
                createDatabaseViewModels = true,
                showSnapshot = true,
                showRecovery = false,
                showBlocked = false,
            )
            else -> DatabaseStartupUiDecision(
                keepSplash = true,
                createDatabaseViewModels = true,
                showSnapshot = false,
                showRecovery = false,
                showBlocked = false,
            )
        }
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
