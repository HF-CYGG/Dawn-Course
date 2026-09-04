package com.dawncourse.core.ui.theme

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** 视觉增强降级与 Context 包装链安全性的纯 JVM 契约。 */
class ThemeSafetyPolicyTest {
    @Test
    fun `视觉增强的普通异常回退为 null`() = runBlocking {
        assertNull(
            visualEnhancementOrNull<String> {
                throw IllegalStateException("palette failed")
            },
        )
    }

    @Test
    fun `视觉增强不得吞掉协程取消`() = runBlocking {
        val cancellation = CancellationException("cancelled")

        try {
            visualEnhancementOrNull<String> { throw cancellation }
            fail("CancellationException 必须重新抛出")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `包装链无匹配节点时安全结束`() {
        val start = Node()
        val end = Node()
        start.next = end

        assertNull(findInWrapperChainOrNull(start, Node::next) { it.matches })
    }

    @Test
    fun `自循环包装链安全结束`() {
        val node = Node()
        node.next = node

        assertNull(findInWrapperChainOrNull(node, Node::next) { it.matches })
    }

    @Test
    fun `包装链返回第一个匹配节点`() {
        val start = Node()
        val target = Node(matches = true)
        start.next = target

        assertEquals(target, findInWrapperChainOrNull(start, Node::next) { it.matches })
    }

    private class Node(
        val matches: Boolean = false,
        var next: Node? = null,
    )
}
