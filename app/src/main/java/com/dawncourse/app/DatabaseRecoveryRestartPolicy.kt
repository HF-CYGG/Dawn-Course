package com.dawncourse.app

import com.dawncourse.core.data.local.startup.DatabaseRecoveryEntryMode
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState

/** 在线恢复状态的重启策略；缺少 marker 时不得自动结束仍可用于重试的进程。 */
internal object DatabaseRecoveryRestartPolicy {
    fun shouldAutoRestart(state: DatabaseRuntimeState): Boolean =
        state is DatabaseRuntimeState.RecoveryRequired &&
            state.entryMode == DatabaseRecoveryEntryMode.RESTART_REQUIRED
}
