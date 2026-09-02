package com.dawncourse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DawnAppInitializationGateTest {

    @Test
    fun `ready 信号会允许 Provider 继续执行`() {
        val gate = DawnAppInitializationGate()

        gate.markReady()

        assertEquals(DawnAppInitializationGate.AwaitResult.Ready, gate.await(timeoutMillis = 0))
    }

    @Test
    fun `未就绪时有限等待会明确超时而不是无限阻塞`() {
        val gate = DawnAppInitializationGate()

        assertEquals(DawnAppInitializationGate.AwaitResult.TimedOut, gate.await(timeoutMillis = 0))
    }

    @Test
    fun `Application 初始化失败会原样传递原因`() {
        val gate = DawnAppInitializationGate()
        val cause = IllegalStateException("Hilt WorkerFactory was not injected")

        gate.markFailed(cause)

        val result = gate.await(timeoutMillis = 0) as DawnAppInitializationGate.AwaitResult.Failed
        assertSame(cause, result.cause)
    }
}
