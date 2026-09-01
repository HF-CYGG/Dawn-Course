package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worker 的读取、对账和 Surface 更新失败必须统一收敛为 retry。 */
class DailySchedulerFailureBoundaryContractTest {
    private val source = File(
        "src/main/java/com/dawncourse/feature/timetable/notification/DailySchedulerWorker.kt"
    ).readText()

    @Test
    fun `所有阶段均保留取消语义且可恢复错误标记 retry`() {
        assertTrue(source.count { it == '\n' } > 0)
        assertTrue(source.split("catch (cancellation: CancellationException)").size - 1 >= 4)
        assertTrue(source.split("throw cancellation").size - 1 >= 4)
        assertTrue(source.contains("课程调度快照读取失败"))
        assertTrue(source.contains("触发器生成失败"))
        assertTrue(source.contains("系统触发器对账失败"))
        assertTrue(source.contains("课程状态 Surface 刷新失败"))
        assertTrue(source.contains("return if (shouldRetry) Result.retry() else Result.success()"))
    }

    @Test
    fun `persistent 开启不预取消且对账失败后仍进入 Surface 更新`() {
        val reconcileIndex = source.indexOf("triggerReconciler.reconcile")
        val surfaceIndex = source.indexOf("if (snapshot.settings.enablePersistentNotification)")
        assertTrue(reconcileIndex >= 0)
        assertTrue(surfaceIndex > reconcileIndex)
        val enabledBranch = source.substring(surfaceIndex, source.indexOf("} else {", surfaceIndex))
        assertFalse(enabledBranch.contains("PersistentNotificationRefreshScheduler.cancel"))
        val disabledBranch = source.substring(source.indexOf("} else {", surfaceIndex))
        assertTrue(disabledBranch.contains("PersistentNotificationRefreshScheduler.cancel"))
        assertTrue(disabledBranch.contains("NotificationHelper.cancelCourseStatus"))
    }
}
