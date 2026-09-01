package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态通知刷新 PendingIntent 身份的回归测试。
 */
class PersistentNotificationRefreshSchedulerTest {
    @Test
    fun `刷新广播使用唯一且稳定的显式身份`() {
        assertEquals(
            "com.dawncourse.action.REFRESH_COURSE_STATUS",
            PersistentNotificationRefreshScheduler.ACTION_REFRESH_COURSE_STATUS
        )
        assertEquals(
            "dawn://course-status/refresh",
            PersistentNotificationRefreshScheduler.REFRESH_DATA_URI
        )
        assertEquals(999, PersistentNotificationRefreshScheduler.REFRESH_REQUEST_CODE)
    }

    @Test
    fun `过期边界只允许提交一次即时对账以防止死循环`() {
        val expiredAt = java.time.Instant.parse("2026-08-24T00:00:00Z")
        val now = expiredAt.plusSeconds(1)

        val firstDecision = PersistentNotificationRefreshPolicy.decide(
            nextRefreshAt = expiredAt,
            now = now,
            expiredReconcileAlreadyRequested = false
        )
        val repeatedDecision = PersistentNotificationRefreshPolicy.decide(
            nextRefreshAt = expiredAt,
            now = now,
            expiredReconcileAlreadyRequested = true
        )

        assertEquals(PersistentNotificationRefreshAction.CANCEL_AND_RECONCILE, firstDecision)
        assertEquals(PersistentNotificationRefreshAction.CANCEL, repeatedDecision)
    }

    @Test
    fun `未来边界正常调度且空边界只取消`() {
        val now = java.time.Instant.parse("2026-08-24T00:00:00Z")

        assertEquals(
            PersistentNotificationRefreshAction.SCHEDULE,
            PersistentNotificationRefreshPolicy.decide(
                nextRefreshAt = now.plusSeconds(1),
                now = now,
                expiredReconcileAlreadyRequested = true
            )
        )
        assertEquals(
            PersistentNotificationRefreshAction.CANCEL,
            PersistentNotificationRefreshPolicy.decide(
                nextRefreshAt = null,
                now = now,
                expiredReconcileAlreadyRequested = false
            )
        )
    }
}
