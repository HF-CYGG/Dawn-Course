package com.dawncourse.core.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进程级写入门必须在恢复失败后阻断所有后续业务写入。 */
class OperationalDataMutationGateTest {

    @Test
    fun concurrentMutationWaitsForExistingLeaseToRelease() = runBlocking {
        val gate = OperationalDataMutationGate()
        val firstLease = gate.acquireLease()
        var secondEntered = false
        val secondStarted = CompletableDeferred<Unit>()
        val second = async {
            secondStarted.complete(Unit)
            gate.withMutation {
                secondEntered = true
            }
        }

        secondStarted.await()
        assertFalse("首个 lease 未释放时，第二个业务写入不得进入", secondEntered)
        firstLease.release()
        second.await()

        assertTrue(secondEntered)
    }

    @Test
    fun permanentlyBlockedGateRejectsEveryFutureMutationAfterLeaseRelease() = runBlocking {
        val gate = OperationalDataMutationGate()
        val lease = gate.acquireLease()

        lease.blockPermanently()
        lease.release()

        val failure = runCatching {
            gate.withMutation { error("永久阻断后不得执行任何业务写入") }
        }.exceptionOrNull()

        assertTrue(failure is OperationalDataMutationBlockedException)
    }

    @Test
    fun queuedMutationIsRejectedWhenCurrentLeaseBlocksBeforeRelease() = runBlocking {
        val gate = OperationalDataMutationGate()
        val firstLease = gate.acquireLease()
        var queuedBlockEntered = false
        val queuedStarted = CompletableDeferred<Unit>()
        val queuedFailure = async {
            queuedStarted.complete(Unit)
            runCatching {
                gate.withMutation { queuedBlockEntered = true }
            }.exceptionOrNull()
        }

        queuedStarted.await()
        firstLease.blockPermanently()
        firstLease.release()

        assertTrue(queuedFailure.await() is OperationalDataMutationBlockedException)
        assertFalse("排队写入在门被永久关闭后不得进入业务 block", queuedBlockEntered)
    }

    @Test
    fun forceBlockPermanentlyRejectsEveryNewMutation() = runBlocking {
        val gate = OperationalDataMutationGate()

        gate.forceBlockPermanently()

        val failure = runCatching { gate.withMutation { Unit } }.exceptionOrNull()

        assertTrue(failure is OperationalDataMutationBlockedException)
    }
}
