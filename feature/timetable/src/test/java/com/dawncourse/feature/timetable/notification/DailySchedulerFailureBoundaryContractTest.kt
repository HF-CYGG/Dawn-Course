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
        assertTrue(
            "恢复状态对账发生在快照之前，也必须纳入同一 retry 聚合",
            source.indexOf("var shouldRetry = false") <
                source.indexOf("muteRecoveryController.reconcilePersistedState()")
        )
        val recoveryBoundary = source.substring(
            source.indexOf("静音恢复状态对账失败"),
            source.indexOf("val snapshot")
        )
        assertTrue(recoveryBoundary.contains("shouldRetry = true"))
        assertTrue(recoveryBoundary.contains("触发就绪补投 journal 对账失败"))
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

    @Test
    fun `Surface 成功后才确认本轮持久刷新责任`() {
        val captureIndex = source.indexOf("captureCourseSurfaceRefreshClaim")
        val surfaceIndex = source.indexOf("if (snapshot.settings.enablePersistentNotification)")
        val acknowledgeIndex = source.indexOf("acknowledgeCourseSurfaceRefresh")
        assertTrue(captureIndex >= 0)
        assertTrue(surfaceIndex > captureIndex)
        assertTrue(acknowledgeIndex > surfaceIndex)
        assertTrue(source.contains("surfaceRefreshed = true"))
        assertTrue(source.substring(acknowledgeIndex).contains("shouldRetry = true"))
    }
}
