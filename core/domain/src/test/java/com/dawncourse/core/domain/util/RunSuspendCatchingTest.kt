package com.dawncourse.core.domain.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 锁定 UI 协程边界共用入口的真实异常语义。
 *
 * 业务异常需要以 [Result] 交给调用方映射为 UI 状态；协程取消和 [Error]
 * 属于结构化并发/致命错误信号，不能被此入口吞掉。
 */
class RunSuspendCatchingTest {

    @Test
    fun `普通 Exception 应映射为失败 Result`() = runBlocking {
        val expected = IllegalStateException("repository failure")

        val result = runSuspendCatching<String> { throw expected }

        assertTrue(result.isFailure)
        assertSame(expected, result.exceptionOrNull())
    }

    @Test
    fun `成功结果应保持领域返回值`() = runBlocking {
        val result = runSuspendCatching { 42 }

        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun `CancellationException 必须继续向调用协程传播`() = runBlocking {
        val expected = CancellationException("screen disposed")

        try {
            runSuspendCatching<Unit> { throw expected }
            fail("CancellationException 不应被转换为 Result.failure")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun `Error 不应被转换为失败 Result`() = runBlocking {
        val expected = AssertionError("fatal invariant")

        try {
            runSuspendCatching<Unit> { throw expected }
            fail("Error 不应被转换为 Result.failure")
        } catch (actual: AssertionError) {
            assertSame(expected, actual)
        }
    }
}
