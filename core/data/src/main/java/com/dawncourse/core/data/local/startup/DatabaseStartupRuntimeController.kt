package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.repository.OperationalDataReadiness
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI 与后台入口共同观察的数据库启动状态，不包含数据库句柄或底层异常。 */
enum class DatabaseRecoveryEntryMode {
    /** 冷启动已完成数据库物理隔离，可以执行恢复或明确放弃。 */
    ACTIONS_AVAILABLE,

    /** 在线故障 marker 已落盘，旧 Room 仍打开，只允许受控重启。 */
    RESTART_REQUIRED,

    /** marker 尚未落盘；当前进程已逻辑隔离，只允许重试持久化 marker。 */
    MARKER_RETRY_REQUIRED,
}

sealed interface DatabaseRuntimeState {
    /** 正在 IO 线程完成恢复、检查、密钥准备与数据库打开。 */
    data object Starting : DatabaseRuntimeState

    /** Room 首次连接已安全完成；条件策略允许双完整性扫描在本状态发布后后台收口。 */
    data object Ready : DatabaseRuntimeState

    /** 必须由用户选择恢复或明确放弃；原因不携带敏感路径。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason,
        val entryMode: DatabaseRecoveryEntryMode = DatabaseRecoveryEntryMode.ACTIONS_AVAILABLE,
    ) : DatabaseRuntimeState

    /** 无法取得或完成外层安全临界区，因而没有持久化可执行恢复事务。 */
    data object StartupBlocked : DatabaseRuntimeState
}

/** 全启动生命周期的跨进程外层互斥边界。 */
fun interface DatabaseStartupCriticalSection {
    /** block 内不得等待 UI；所有磁盘与 SQLCipher 操作都应在调用方 IO dispatcher 执行。 */
    fun run(block: () -> Unit)
}

/** 外层锁内完成全部步骤的初始化入口。 */
fun interface DatabaseStartupInitializer<T : Any> {
    /** 返回已通过同步前置条件的句柄或稳定恢复原因，底层异常不得直接进入 UI。 */
    fun initialize(): DatabaseStartupInitialization<T>
}

/** 一次完整初始化的内部结果。 */
sealed interface DatabaseStartupInitialization<out T : Any> {
    /** 句柄已完成首次连接；标记用于避免同一次迁移启动过早删除明文 pre-image。 */
    data class Ready<T : Any>(
        val handle: T,
        val migratedPlaintextThisRun: Boolean,
        /** 非 null 时只能在发布 Ready 后执行，失败结果会单向进入在线恢复。 */
        val postReadyAction: DatabasePostReadyAction? = null,
    ) : DatabaseStartupInitialization<T>

    /** 初始化必须停止。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason
    ) : DatabaseStartupInitialization<Nothing>
}

/** Ready 发布后的非阻塞完整性责任；异常回退必须复用同一个 fail-closed 入口。 */
class DatabasePostReadyAction(
    private val runAction: suspend () -> DatabasePostReadyResult,
    private val failClosedAfterUnexpectedException: suspend () -> DatabasePostReadyResult.RecoveryRequired,
) {
    /** 返回稳定状态转换，不传播底层异常、路径或 SQL 结果。 */
    suspend fun run(): DatabasePostReadyResult = runAction()

    /** run 异常时由责任拥有者执行 marker 与写门的不可取消收口。 */
    suspend fun failClosedAfterUnexpectedException(): DatabasePostReadyResult.RecoveryRequired =
        failClosedAfterUnexpectedException.invoke()
}

/** Ready 后动作的稳定结果。 */
sealed interface DatabasePostReadyResult {
    /** 后台责任已经成功收口，保持 Ready。 */
    data object Complete : DatabasePostReadyResult

    /** 后台发现数据库不可信；marker 和写门状态已经在返回前确定。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason,
        val entryMode: DatabaseRecoveryEntryMode,
    ) : DatabasePostReadyResult
}

/**
 * 只启动一次、只在完整外层锁退出后发布 Ready 的通用 Runtime 控制器。
 *
 * 句柄不放入 StateFlow，避免 UI、日志或序列化工具意外持有数据库对象。
 */
class DatabaseStartupRuntimeController<T : Any>(
    private val criticalSection: DatabaseStartupCriticalSection,
    private val initializer: DatabaseStartupInitializer<T>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<DatabaseRuntimeState>(DatabaseRuntimeState.Starting)
    private val handleLock = Any()
    private var readyHandle: T? = null

    /** 只读启动状态。 */
    val state: StateFlow<DatabaseRuntimeState> = mutableState.asStateFlow()

    /** 异步提交唯一初始化任务；重复调用不会并发打开数据库。 */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            val outcome = runCatching {
                var initialized: DatabaseStartupInitialization<T>? = null
                criticalSection.run {
                    initialized = initializer.initialize()
                }
                checkNotNull(initialized) { "数据库启动临界区未返回结果" }
            }.getOrNull()
            if (outcome == null) {
                // 锁获取失败或控制器级异常时不得伪造可点击的 RecoveryRequired 页面。
                mutableState.value = DatabaseRuntimeState.StartupBlocked
                return@launch
            }
            when (outcome) {
                is DatabaseStartupInitialization.Ready -> {
                    synchronized(handleLock) { readyHandle = outcome.handle }
                    mutableState.value = DatabaseRuntimeState.Ready
                    outcome.postReadyAction?.let { action ->
                        // 独立协程保证 Ready 发布不等待后台双扫描；action 返回 Recovery 前已经
                        // 完成专用 marker 与写门线性化，Controller 只负责最后的状态切换。
                        scope.launch(ioDispatcher) {
                            val result = try {
                                action.run()
                            } catch (_: Throwable) {
                                // Controller 不复制半套 marker/lease 协议；生产 action 自己拥有
                                // 不可取消的收口入口。若异常来源本身也异常，仍稳定撤销 Ready。
                                withContext(NonCancellable) {
                                    runCatching { action.failClosedAfterUnexpectedException() }
                                        .getOrDefault(
                                            DatabasePostReadyResult.RecoveryRequired(
                                                DatabaseRecoveryReason.IntegrityVerificationFailed,
                                                DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED,
                                            ),
                                        )
                                }
                            }
                            when (result) {
                                DatabasePostReadyResult.Complete -> Unit
                                is DatabasePostReadyResult.RecoveryRequired -> {
                                    enterRuntimeRecovery(result.reason, result.entryMode)
                                }
                            }
                        }
                    }
                }
                is DatabaseStartupInitialization.RecoveryRequired -> {
                    mutableState.value = DatabaseRuntimeState.RecoveryRequired(outcome.reason)
                }
            }
        }
    }

    /** Feature 后台守卫所需的无阻塞瞬时状态。 */
    fun readiness(): OperationalDataReadiness = when (state.value) {
        DatabaseRuntimeState.Starting -> OperationalDataReadiness.STARTING
        DatabaseRuntimeState.Ready -> OperationalDataReadiness.READY
        is DatabaseRuntimeState.RecoveryRequired -> OperationalDataReadiness.RECOVERY_REQUIRED
        DatabaseRuntimeState.StartupBlocked -> OperationalDataReadiness.RECOVERY_REQUIRED
    }

    /**
     * 已发布 Ready 后发现数据库内容不再可信时，立即撤销新的句柄访问并切断业务 UI。
     *
     * 这里故意不关闭或移动已发布的 Room：已有 DAO/事务引用无法安全撤销，物理隔离必须
     * 交给受控进程重启后的冷启动临界区完成。
     */
    fun enterRuntimeRecovery(
        reason: DatabaseRecoveryReason,
        entryMode: DatabaseRecoveryEntryMode,
    ) = synchronized(handleLock) {
        val current = mutableState.value
        val allowed = when {
            current is DatabaseRuntimeState.Ready -> true
            current is DatabaseRuntimeState.RecoveryRequired &&
                current.entryMode == DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED &&
                entryMode == DatabaseRecoveryEntryMode.RESTART_REQUIRED -> true
            else -> false
        }
        if (allowed) mutableState.value = DatabaseRuntimeState.RecoveryRequired(reason, entryMode)
    }

    /** 只允许 DataModule 在 Runtime 已发布 Ready 后取同一个已验证句柄。 */
    fun requireReadyHandle(): T = synchronized(handleLock) {
        check(mutableState.value == DatabaseRuntimeState.Ready) { "数据库已停止业务访问" }
        checkNotNull(readyHandle) { "数据库尚未通过启动验证" }
    }
}
