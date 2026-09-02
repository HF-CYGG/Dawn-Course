package com.dawncourse.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/**
 * 进程内所有 Operational Model 写入共用的线性化门。
 *
 * 备份恢复补偿无法完整回滚时，持有 lease 的调用方会在释放前永久关闭该门；
 * 同一进程内后续任何经由本门的业务写入都会失败，直到受控重启重新建立 Hilt 单例。
 */
@Singleton
class OperationalDataMutationGate @Inject constructor() {
    private val mutex = Mutex()
    private val permanentlyBlocked = AtomicBoolean(false)

    /** 取得独占 lease；已永久关闭时绝不允许进入业务写入。 */
    internal suspend fun acquireLease(): Lease {
        mutex.lock()
        if (permanentlyBlocked.get()) {
            mutex.unlock()
            throw OperationalDataMutationBlockedException()
        }
        return Lease(this)
    }

    /**
     * 非预期 fail-closed 路径的最后收口：不等待当前 lease，也绝不撤销已经开始的事务；
     * 仅保证之后取得 lease 的写入全部被拒绝。
     */
    internal fun forceBlockPermanently() {
        permanentlyBlocked.set(true)
    }

    /** 普通写入的便捷入口，保证检查状态与实际事务之间不存在 TOCTOU 窗口。 */
    suspend fun <T> withMutation(block: suspend () -> T): T {
        val lease = acquireLease()
        return try {
            block()
        } finally {
            lease.release()
        }
    }

    /** 只允许持有者在释放前调用的独占 lease。 */
    internal class Lease internal constructor(
        private val gate: OperationalDataMutationGate,
    ) {
        private var released = false

        /**
         * 将门切换为永久阻断。调用方必须在数据库状态不可再被信任时、释放 lease 前调用。
         */
        fun blockPermanently() {
            check(!released) { "写入 lease 已释放" }
            gate.forceBlockPermanently()
        }

        /** 释放独占权；永久阻断状态会保留到进程结束。 */
        fun release() {
            if (released) return
            released = true
            gate.mutex.unlock()
        }
    }
}

/** 当前进程中的 Operational Model 已进入恢复隔离，禁止继续写入。 */
internal class OperationalDataMutationBlockedException : IllegalStateException(
    "课程数据正在恢复隔离中，请重启应用后继续",
)
