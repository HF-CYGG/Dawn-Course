package com.dawncourse.core.data.local.startup

import com.dawncourse.core.data.repository.OperationalDataMutationGate
import com.dawncourse.core.data.repository.OperationalDataMutationBlockedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 完整性失败收口所需的最小写门能力，允许 JVM 覆盖底层异常路径。 */
internal interface IntegrityFailureMutationGate {
    suspend fun acquireLease(): IntegrityFailureMutationLease

    fun forceBlockPermanently()
}

/** fail-closed 所持有的写入 lease。 */
internal interface IntegrityFailureMutationLease {
    fun blockPermanently()

    fun release()
}

/** 生产适配器仅暴露完整性收口需要的内部能力，不进入域层或外部 API。 */
internal class OperationalDataMutationGateIntegrityAdapter(
    private val delegate: OperationalDataMutationGate,
) : IntegrityFailureMutationGate {
    override suspend fun acquireLease(): IntegrityFailureMutationLease {
        val lease = delegate.acquireLease()
        return object : IntegrityFailureMutationLease {
            override fun blockPermanently() = lease.blockPermanently()

            override fun release() = lease.release()
        }
    }

    override fun forceBlockPermanently() {
        delegate.forceBlockPermanently()
    }
}

/**
 * 将纯策略的同步/后台模式落实为稳定动作。
 *
 * 后台失败只持久专用 marker 并关闭进程写门；不得关闭、移动或删除仍被 Room 使用的文件。
 */
internal class DatabaseStartupIntegrityCoordinator<T : Any>(
    private val verifier: (T) -> Unit,
    private val completeSuccessfulVerification: () -> Unit,
    private val persistIntegrityRecoveryMarker: () -> Unit,
    private val mutationGate: IntegrityFailureMutationGate,
) {
    /** 同步模式只完成双扫描；成功状态延迟到其它 Ready 条件全部通过后提交。 */
    fun verifyBeforeReady(mode: IntegrityVerificationMode, handle: T): Boolean {
        if (mode == IntegrityVerificationMode.BACKGROUND_AFTER_READY) return true
        return runCatching { verifier(handle) }.isSuccess
    }

    /** 同步扫描成功且其它启动检查已完成时，才原子提交时间并清除启动责任。 */
    fun completeBeforeReady(mode: IntegrityVerificationMode): Boolean {
        if (mode == IntegrityVerificationMode.BACKGROUND_AFTER_READY) return true
        return runCatching(completeSuccessfulVerification).isSuccess
    }

    /** 仅后台模式返回动作，确保 Controller 先发布 Ready 再执行。 */
    fun postReadyAction(
        mode: IntegrityVerificationMode,
        handle: T,
    ): DatabasePostReadyAction? = if (mode == IntegrityVerificationMode.BACKGROUND_AFTER_READY) {
        DatabasePostReadyAction(
            runAction = {
                val verified = runCatching {
                    verifier(handle)
                    completeSuccessfulVerification()
                }.isSuccess
                if (verified) {
                    DatabasePostReadyResult.Complete
                } else {
                    failClosedAfterBackgroundFailure()
                }
            },
            failClosedAfterUnexpectedException = ::failClosedAfterBackgroundFailure,
        )
    } else {
        null
    }

    /** 稳定顺序：专用 marker → 持 lease 永久关门 → 交给 Controller 发布 Recovery。 */
    private suspend fun failClosedAfterBackgroundFailure(): DatabasePostReadyResult.RecoveryRequired {
        val markerPersisted = runCatching(persistIntegrityRecoveryMarker).isSuccess
        // marker 成败都必须完成永久关门；NonCancellable 保证取消不会在 marker 与写门之间
        // 留下仍可写的已发布 Room。lease 任一环节异常后由 finally 的原子关闭兜底，
        // 不撤销已经持有 lease 的事务，但之后所有新写入都会被拒绝。
        withContext(NonCancellable) {
            try {
                val lease = try {
                    mutationGate.acquireLease()
                } catch (_: OperationalDataMutationBlockedException) {
                    null
                }
                if (lease != null) {
                    try {
                        lease.blockPermanently()
                    } finally {
                        lease.release()
                    }
                }
            } catch (_: Throwable) {
                // 底层异常不得越过稳定 Recovery 结果。
            } finally {
                runCatching { mutationGate.forceBlockPermanently() }
            }
        }
        return DatabasePostReadyResult.RecoveryRequired(
            reason = DatabaseRecoveryReason.IntegrityVerificationFailed,
            entryMode = if (markerPersisted) {
                DatabaseRecoveryEntryMode.RESTART_REQUIRED
            } else {
                DatabaseRecoveryEntryMode.MARKER_RETRY_REQUIRED
            },
        )
    }
}
