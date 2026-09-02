package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import com.dawncourse.core.domain.repository.StartupSnapshotRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 冷启动快照的稳定可观察状态；它与数据库 Runtime 平行且不持有数据库句柄。 */
sealed interface StartupSnapshotRuntimeState {
    data object Loading : StartupSnapshotRuntimeState
    data class Available(val snapshot: StartupSnapshot) : StartupSnapshotRuntimeState
    data object Missing : StartupSnapshotRuntimeState
}

/**
 * 应用级启动快照 Runtime。
 *
 * Profile 复核只读 `active_profile_id` DataStore，绝不通过 Repository 或 DAO 回读 Room；
 * 因此数据库迁移、损坏或恢复门禁期间，快照路径也不会意外提前开库。
 */
@Singleton
class StartupSnapshotRuntime(
    private val repository: StartupSnapshotRepository,
    private val activeProfileSelectionStore: ActiveProfileSelectionStore,
    private val nowEpochMillis: () -> Long,
    private val zoneId: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val invalidated = AtomicBoolean(false)
    @Volatile private var readJob: Job? = null
    private val mutableState = MutableStateFlow<StartupSnapshotRuntimeState>(StartupSnapshotRuntimeState.Loading)

    /** UI 与 Splash 只订阅这个稳定 StateFlow，不直接访问文件或 Keystore。 */
    val state: StateFlow<StartupSnapshotRuntimeState> = mutableState.asStateFlow()

    /** Application 主进程在数据库 Runtime 启动的同时调用一次。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        readJob = scope.launch {
            val result = runCatching {
                repository.read(
                    expectedProfileId = activeProfileSelectionStore.activeProfileId.first(),
                    nowEpochMillis = nowEpochMillis(),
                    expectedZoneId = zoneId(),
                )
            }.getOrDefault(StartupSnapshotReadResult.Missing)
            // Recovery/Blocked 可能在 Keystore/文件读取期间到达。此时晚到的可用结果绝不能
            // 重新暴露已撤下的快照，即使底层读取实现没有协作响应 cancel。
            if (!invalidated.get()) {
                mutableState.value = when (result) {
                    is StartupSnapshotReadResult.Available -> StartupSnapshotRuntimeState.Available(result.snapshot)
                    StartupSnapshotReadResult.Missing -> StartupSnapshotRuntimeState.Missing
                }
            }
        }
    }

    /** Recovery/Blocked 时先撤下画面，再在后台尽力删除不可再信任的加速文件。 */
    fun invalidate() {
        invalidated.set(true)
        readJob?.cancel()
        mutableState.value = StartupSnapshotRuntimeState.Missing
        scope.launch { runCatching { repository.invalidate() } }
    }
}
