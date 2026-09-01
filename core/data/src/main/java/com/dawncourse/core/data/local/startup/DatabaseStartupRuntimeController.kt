package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.repository.OperationalDataReadiness
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 与后台入口共同观察的数据库启动状态，不包含数据库句柄或底层异常。 */
sealed interface DatabaseRuntimeState {
    /** 正在 IO 线程完成恢复、检查、密钥准备与数据库打开。 */
    data object Starting : DatabaseRuntimeState

    /** 数据库已通过完整性校验并可供 Hilt Repository 使用。 */
    data object Ready : DatabaseRuntimeState

    /** 必须由用户选择恢复或明确放弃；原因不携带敏感路径。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason
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
    /** 返回已验证句柄或稳定恢复原因，底层异常不得直接进入 UI。 */
    fun initialize(): DatabaseStartupInitialization<T>
}

/** 一次完整初始化的内部结果。 */
sealed interface DatabaseStartupInitialization<out T : Any> {
    /** 句柄已打开并验证；标记用于避免同一次迁移启动过早删除明文 pre-image。 */
    data class Ready<T : Any>(
        val handle: T,
        val migratedPlaintextThisRun: Boolean
    ) : DatabaseStartupInitialization<T>

    /** 初始化必须停止。 */
    data class RecoveryRequired(
        val reason: DatabaseRecoveryReason
    ) : DatabaseStartupInitialization<Nothing>
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

    /** 只允许 DataModule 在 Runtime 已发布 Ready 后取同一个已验证句柄。 */
    fun requireReadyHandle(): T = synchronized(handleLock) {
        checkNotNull(readyHandle) { "数据库尚未通过启动验证" }
    }
}
