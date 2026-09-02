package com.dawncourse.app

import com.dawncourse.core.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前台周期与课程版本共同构成 Widget 刷新的唯一请求键。 */
class WidgetRefreshDeduplicatorTest {
    @Test
    fun `首次 Success 与首次 onStart 共享 generation 且只领取一次`() {
        val deduplicator = WidgetRefreshDeduplicator()
        val revision = revision(profileId = 7L)
        val widget = CountingWidget()

        assertEquals(0L, deduplicator.onForegroundStarted())
        val request = deduplicator.claim(revision)
        assertNotNull(request)
        deduplicator.runIfCurrent(request!!) { widget.refresh() }
        assertNull("同一个首次前台 key 不得重复刷新", deduplicator.claim(revision))
        assertEquals("首次 Success 与 onStart 重叠时只更新一次", 1, widget.refreshCount)
    }

    @Test
    fun `同一前台的新 revision 只领取一次且淘汰旧请求`() {
        val deduplicator = WidgetRefreshDeduplicator()
        val widget = CountingWidget()
        deduplicator.onForegroundStarted()
        val older = deduplicator.claim(revision(profileId = 7L))!!
        val latest = deduplicator.claim(revision(profileId = 8L))!!

        assertFalse("旧 LaunchedEffect 即使晚到也不得更新 Widget", deduplicator.isCurrent(older))
        assertTrue(deduplicator.isCurrent(latest))
        deduplicator.runIfCurrent(older) { widget.refresh() }
        deduplicator.runIfCurrent(latest) { widget.refresh() }
        assertNull("最新 revision 的重复重组不得二次刷新", deduplicator.claim(revision(profileId = 8L)))
        assertEquals("快速 A/B 发射只能由最新 revision 更新一次", 1, widget.refreshCount)
    }

    @Test
    fun `新的前台周期允许相同 revision 自愈一次`() {
        val deduplicator = WidgetRefreshDeduplicator()
        val revision = revision(profileId = 7L)
        val widget = CountingWidget()

        assertEquals(0L, deduplicator.onForegroundStarted())
        deduplicator.runIfCurrent(deduplicator.claim(revision)!!) { widget.refresh() }
        deduplicator.onForegroundStopped()

        assertEquals(1L, deduplicator.onForegroundStarted())
        val nextCycleRequest = deduplicator.claim(revision)
        assertNotNull("回前台必须允许同一 revision 再次对账", nextCycleRequest)
        deduplicator.runIfCurrent(nextCycleRequest!!) { widget.refresh() }
        assertEquals("新前台周期可自愈一次", 2, widget.refreshCount)
    }

    @Test
    fun `当前 Widget 刷新抛异常时保持调用方稳定并返回失败`() {
        val deduplicator = WidgetRefreshDeduplicator()
        deduplicator.onForegroundStarted()
        val request = deduplicator.claim(revision(profileId = 7L))!!
        var attempts = 0

        val failure = deduplicator.runIfCurrentCatching(request) {
            attempts += 1
            error("模拟 Widget 更新异常")
        }

        assertEquals(1, attempts)
        assertEquals("模拟 Widget 更新异常", failure?.message)
    }

    private fun revision(profileId: Long): ScheduleRevision = ScheduleRevision.create(
        settings = AppSettings(),
        semester = null,
        courses = emptyList(),
        profileId = profileId,
    )

    /** 可计数 fake，验证 policy 实际允许的副作用次数。 */
    private class CountingWidget {
        var refreshCount = 0

        fun refresh() {
            refreshCount += 1
        }
    }
}
