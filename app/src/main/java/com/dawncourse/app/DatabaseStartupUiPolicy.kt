package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.repository.StartupSnapshotRuntimeState
import com.dawncourse.core.domain.model.StartupSnapshot

/** MainActivity 启动状态的互斥 UI 决策，携带渲染分支所需的真实状态。 */
sealed interface DatabaseStartupUiDecision {
    val keepSplash: Boolean
    val createDatabaseViewModels: Boolean

    /** Database Starting 或实时 Root 仍 Loading 时展示已经验证的快照。 */
    data class Snapshot(
        val snapshot: StartupSnapshot,
        override val createDatabaseViewModels: Boolean,
    ) : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = false
    }

    /** 实时 Root 尚未可用时继续保留 Splash。 */
    data class Splash(
        override val createDatabaseViewModels: Boolean,
    ) : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = true
    }

    /** 仅数据库 Ready 且实时 MainUiState.Success 时允许创建实时 Root。 */
    data object LiveRoot : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = false
        override val createDatabaseViewModels: Boolean = true
    }

    /** 根数据流失败时的脱敏安全界面，禁止构建应用导航图。 */
    data object RootError : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = false
        override val createDatabaseViewModels: Boolean = true
    }

    /** 恢复界面直接携带恢复状态，避免 MainActivity 强转 Runtime 状态。 */
    data class Recovery(
        val state: DatabaseRuntimeState.RecoveryRequired,
    ) : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = false
        override val createDatabaseViewModels: Boolean = false
    }

    data object Blocked : DatabaseStartupUiDecision {
        override val keepSplash: Boolean = false
        override val createDatabaseViewModels: Boolean = false
    }
}

/**
 * MainActivity 唯一的首帧门禁。
 *
 * 所有分支都由 sealed decision 承载实际快照或 Recovery 状态，避免 Splash、快照和恢复页
 * 各自维护布尔组合或依赖不安全强转。
 */
object DatabaseStartupUiPolicy {
    fun decide(
        databaseState: DatabaseRuntimeState,
        snapshotState: StartupSnapshotRuntimeState,
        liveRootReady: Boolean = false,
        liveRootFailed: Boolean = false,
    ): DatabaseStartupUiDecision = when (databaseState) {
        DatabaseRuntimeState.Starting -> when (snapshotState) {
            is StartupSnapshotRuntimeState.Available -> DatabaseStartupUiDecision.Snapshot(
                snapshot = snapshotState.snapshot,
                createDatabaseViewModels = false,
            )
            StartupSnapshotRuntimeState.Loading,
            StartupSnapshotRuntimeState.Missing -> DatabaseStartupUiDecision.Splash(
                createDatabaseViewModels = false,
            )
        }
        DatabaseRuntimeState.Ready -> when {
            liveRootFailed -> DatabaseStartupUiDecision.RootError
            liveRootReady -> DatabaseStartupUiDecision.LiveRoot
            snapshotState is StartupSnapshotRuntimeState.Available -> DatabaseStartupUiDecision.Snapshot(
                snapshot = snapshotState.snapshot,
                createDatabaseViewModels = true,
            )
            else -> DatabaseStartupUiDecision.Splash(
                createDatabaseViewModels = true,
            )
        }
        is DatabaseRuntimeState.RecoveryRequired -> DatabaseStartupUiDecision.Recovery(databaseState)
        DatabaseRuntimeState.StartupBlocked -> DatabaseStartupUiDecision.Blocked
    }
}
