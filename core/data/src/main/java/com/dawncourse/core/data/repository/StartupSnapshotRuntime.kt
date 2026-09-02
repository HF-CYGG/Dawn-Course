package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.contentIdentity
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import com.dawncourse.core.domain.repository.StartupSnapshotRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val visibleSnapshotReleased = AtomicBoolean(false)
    /** 新请求先登记 generation，再排队进入写入 mutex，保证 B 可以淘汰仍在执行的 A。 */
    private val mutationGeneration = AtomicLong(0L)
    /** replace 与 Recovery 删除共用同一串行区，删除一定发生在已开始的写入之后。 */
    private val mutationMutex = Mutex()
    @Volatile private var readJob: Job? = null
    /** 只在 [mutationMutex] 内读写；用于跳过内容未变的重复加密写入。 */
    private var lastCommitted: CommittedSnapshot? = null
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
            if (!invalidated.get() && !visibleSnapshotReleased.get()) {
                mutableState.value = when (result) {
                    is StartupSnapshotReadResult.Available -> StartupSnapshotRuntimeState.Available(result.snapshot)
                    StartupSnapshotReadResult.Missing -> StartupSnapshotRuntimeState.Missing
                }
            }
            // 磁盘上已存在的快照同样是"已提交内容"。不播种的话，实时 Root 首次 Success 必然
            // 触发一次内容完全相同的加密重写。
            if (result is StartupSnapshotReadResult.Available) {
                mutationMutex.withLock {
                    if (lastCommitted == null && !invalidated.get()) {
                        lastCommitted = CommittedSnapshot.of(result.snapshot)
                    }
                }
            }
        }
    }

    /** 只提交调用时仍为 latest 的完整快照；副作用由调用方之外的单一实时对账路径负责。 */
    suspend fun replaceLatest(snapshot: StartupSnapshot): Boolean {
        if (invalidated.get()) return false
        val requestGeneration = mutationGeneration.incrementAndGet()
        if (invalidated.get()) return false
        return mutationMutex.withLock {
            if (invalidated.get() || mutationGeneration.get() != requestGeneration) {
                return@withLock false
            }
            // 内容未变时不重复付出 Keystore + AES-GCM + 原子写的代价；但仍必须在既有快照
            // 临近过期前续写一次，否则长期不改课表的用户会在 TTL 到期后失去冷启动加速。
            val identity = snapshot.contentIdentity()
            val committed = lastCommitted
            if (committed != null &&
                committed.identity == identity &&
                nowEpochMillis() < committed.refreshAfterEpochMillis
            ) {
                return@withLock true
            }
            val replaced = try {
                repository.replace(snapshot)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            if (!replaced || invalidated.get() || mutationGeneration.get() != requestGeneration) {
                return@withLock false
            }
            lastCommitted = CommittedSnapshot.of(snapshot)
            true
        }
    }

    /**
     * Recovery/Blocked 的写入否决。先同步关闭未来 replace、递增代际并撤下 UI，再在与
     * replace 同一 mutex 中完成删除；已进入 repository.replace 的旧请求也不能在删除后重写。
     */
    suspend fun invalidate() {
        invalidated.set(true)
        visibleSnapshotReleased.set(true)
        mutationGeneration.incrementAndGet()
        readJob?.cancel()
        mutableState.value = StartupSnapshotRuntimeState.Missing
        withContext(NonCancellable) {
            mutationMutex.withLock {
                runCatching { repository.invalidate() }
                // 文件已删除；即使后续内容相同也不能再判重跳过。
                lastCommitted = null
            }
        }
    }

    /** 已落盘快照的内容指纹与续写时刻；不持有快照本体，避免延长大对象生命周期。 */
    private data class CommittedSnapshot(
        val identity: StartupSnapshotRevision,
        val refreshAfterEpochMillis: Long,
    ) {
        companion object {
            /** 过半 TTL 后允许续写，使未变更内容最多每半个 TTL 重写一次。 */
            fun of(snapshot: StartupSnapshot): CommittedSnapshot = CommittedSnapshot(
                identity = snapshot.contentIdentity(),
                refreshAfterEpochMillis =
                    snapshot.expiresAtEpochMillis - StartupSnapshot.TTL_MILLIS / 2,
            )
        }
    }

    /** 实时 Root 已稳定渲染后释放大快照引用；绝不删除刚刚替换的 no-backup 文件。 */
    fun releaseVisibleSnapshot() {
        visibleSnapshotReleased.set(true)
        mutableState.value = StartupSnapshotRuntimeState.Missing
    }
}
