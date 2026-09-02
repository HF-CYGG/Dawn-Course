package com.dawncourse.feature.widget.policy

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class WidgetTimelineBoundaryPolicyTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 2)
    private val sectionTimes = listOf(
        SectionTime("08:00", "08:45"),
        SectionTime("08:55", "09:40"),
        SectionTime("10:00", "10:45"),
    )

    @Test
    fun `课程尚未开始时下一边界是最早的开始时间`() {
        val nowMillis = instantAt("07:30")

        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(course(startSection = 2, duration = 1)),
            sectionTimes = sectionTimes,
            today = today,
            zoneId = zoneId,
            nowMillis = nowMillis,
        )

        assertEquals(instantAt("08:55"), actual)
    }

    @Test
    fun `课程进行中时下一边界是课程结束时间`() {
        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(course(startSection = 2, duration = 1)),
            sectionTimes = sectionTimes,
            today = today,
            zoneId = zoneId,
            nowMillis = instantAt("09:10"),
        )

        assertEquals(instantAt("09:40"), actual)
    }

    @Test
    fun `多个课程时从全部未来开始和结束边界中选最早者`() {
        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(
                course(startSection = 1, duration = 1),
                course(startSection = 2, duration = 1),
            ),
            sectionTimes = sectionTimes,
            today = today,
            zoneId = zoneId,
            nowMillis = instantAt("08:50"),
        )

        assertEquals(instantAt("08:55"), actual)
    }

    @Test
    fun `当天没有未来课程边界时返回空以交给午夜链路`() {
        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(course(startSection = 1, duration = 1)),
            sectionTimes = sectionTimes,
            today = today,
            zoneId = zoneId,
            nowMillis = instantAt("09:00"),
        )

        assertNull(actual)
    }

    @Test
    fun `二十四点结束时间表示次日零点而不是提前一分钟`() {
        val lateSection = listOf(SectionTime("23:00", "24:00"))

        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(course(startSection = 1, duration = 1)),
            sectionTimes = lateSection,
            today = today,
            zoneId = zoneId,
            nowMillis = instantAt("23:30"),
        )

        assertEquals(
            today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            actual,
        )
    }

    @Test
    fun `二十四点之后的分钟值无效且不会制造错误边界`() {
        val invalidSection = listOf(SectionTime("23:00", "24:01"))

        val actual = WidgetTimelineBoundaryPolicy.nextFutureBoundaryMillis(
            courses = listOf(course(startSection = 1, duration = 1)),
            sectionTimes = invalidSection,
            today = today,
            zoneId = zoneId,
            nowMillis = instantAt("23:30"),
        )

        assertNull(actual)
    }

    private fun course(startSection: Int, duration: Int) = Course(
        name = "课程",
        dayOfWeek = 3,
        startSection = startSection,
        duration = duration,
        startWeek = 1,
        endWeek = 16,
    )

    private fun instantAt(time: String): Long = today.atTime(LocalTime.parse(time))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}

class WidgetNextUpdateCoordinatorTest {

    @Test
    fun `未来边界精确调度成功时只留下 Alarm`() = runBlocking {
        val operations = RecordingNextUpdateOperations(canScheduleExact = true)

        WidgetNextUpdateCoordinator.reconcile(
            triggerAtMillis = 2_000L,
            nowMillis = 1_000L,
            operations = operations,
        )

        assertEquals(listOf("cancelAlarm", "setExact:2000", "cancelWork"), operations.events)
    }

    @Test
    fun `无精确权限时只留下 Work fallback`() = runBlocking {
        val operations = RecordingNextUpdateOperations(canScheduleExact = false)

        WidgetNextUpdateCoordinator.reconcile(
            triggerAtMillis = 2_000L,
            nowMillis = 1_000L,
            operations = operations,
        )

        assertEquals(listOf("cancelAlarm", "enqueueWork:1000"), operations.events)
    }

    @Test
    fun `Alarm 调用失败时清理旧 Alarm 并排入 Work fallback`() = runBlocking {
        val operations = RecordingNextUpdateOperations(
            canScheduleExact = true,
            exactFailure = SecurityException("denied"),
        )

        WidgetNextUpdateCoordinator.reconcile(
            triggerAtMillis = 2_000L,
            nowMillis = 1_000L,
            operations = operations,
        )

        assertEquals(
            listOf("cancelAlarm", "setExact:2000", "cancelAlarm", "enqueueWork:1000"),
            operations.events,
        )
    }

    @Test
    fun `取消旧 fallback 失败时撤销新 Alarm 并重新确保 Work fallback`() = runBlocking {
        val operations = RecordingNextUpdateOperations(
            canScheduleExact = true,
            cancelWorkFailure = IllegalStateException("cancel failed"),
        )

        WidgetNextUpdateCoordinator.reconcile(
            triggerAtMillis = 2_000L,
            nowMillis = 1_000L,
            operations = operations,
        )

        assertEquals(
            listOf("cancelAlarm", "setExact:2000", "cancelWork", "cancelAlarm", "enqueueWork:1000"),
            operations.events,
        )
    }

    @Test
    fun `基础协调器遇到取消时保留原始取消并交给外层统一补偿`() = runBlocking {
        val operations = RecordingNextUpdateOperations(
            canScheduleExact = true,
            exactFailure = CancellationException("cancelled"),
        )

        val failure = runCatching {
            WidgetNextUpdateCoordinator.reconcile(2_000L, 1_000L, operations)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("cancelAlarm", "setExact:2000"), operations.events)
    }

    @Test
    fun `无未来边界时同时取消 Alarm 与 Work`() = runBlocking {
        val operations = RecordingNextUpdateOperations(canScheduleExact = true)

        WidgetNextUpdateCoordinator.reconcile(
            triggerAtMillis = null,
            nowMillis = 1_000L,
            operations = operations,
        )

        assertEquals(listOf("cancelAlarm", "cancelWork"), operations.events)
    }

    @Test
    fun `晚登记的新请求完成后旧请求不再产生任何副作用`() = runBlocking {
        val coordinator = SerializedWidgetRefreshCoordinator<Unit>()
        val oldRequest = coordinator.registerRequest()
        val latestRequest = coordinator.registerRequest()
        val latest = RecordingNextUpdateOperations(canScheduleExact = true)
        val stale = RecordingNextUpdateOperations(canScheduleExact = true)

        val latestResult = coordinator.reconcile(latestRequest, 3_000L, { 1_000L }, latest)
        val staleResult = coordinator.reconcile(oldRequest, 2_000L, { 1_000L }, stale)

        assertEquals(WidgetNextUpdateSchedulingResult.EXACT_SCHEDULED, latestResult)
        assertEquals(WidgetNextUpdateSchedulingResult.STALE_IGNORED, staleResult)
        assertTrue(stale.events.isEmpty())
    }

    @Test
    fun `并发刷新按进入顺序串行提交 Alarm 和 Work 副作用`() {
        val coordinator = SerializedWidgetRefreshCoordinator<Unit>()
        val firstEnteredExact = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = object : WidgetNextUpdateOperations {
            override suspend fun cancelExactAlarm() = Unit
            override fun canScheduleExactAlarm(): Boolean = true
            override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
                firstEnteredExact.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
            override suspend fun cancelFallbackWork() = Unit
            override suspend fun enqueueFallbackWork(delayMillis: Long) = Unit
        }
        val second = object : WidgetNextUpdateOperations {
            override suspend fun cancelExactAlarm() = Unit
            override fun canScheduleExactAlarm(): Boolean = true
            override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
                secondEntered.countDown()
            }
            override suspend fun cancelFallbackWork() = Unit
            override suspend fun enqueueFallbackWork(delayMillis: Long) = Unit
        }

        val firstRequest = coordinator.registerRequest()
        val firstThread = thread {
            runBlocking { coordinator.reconcile(firstRequest, 2_000L, { 1_000L }, first) }
        }
        assertTrue(firstEnteredExact.await(2, TimeUnit.SECONDS))
        val secondRequest = coordinator.registerRequest()
        val secondThread = thread {
            runBlocking { coordinator.reconcile(secondRequest, 3_000L, { 1_000L }, second) }
        }

        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        firstThread.join(2_000L)
        secondThread.join(2_000L)
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `旧调度挂起期间新请求登记并取消时旧责任会被补偿清空`() {
        val coordinator = SerializedWidgetRefreshCoordinator<Unit>()
        val enteredExact = CountDownLatch(1)
        val releaseExact = CountDownLatch(1)
        val events = mutableListOf<String>()
        val result = AtomicReference<WidgetNextUpdateSchedulingResult>()
        val operations = object : WidgetNextUpdateOperations {
            override suspend fun cancelExactAlarm() {
                events += "cancelAlarm"
            }
            override fun canScheduleExactAlarm(): Boolean = true
            override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
                events += "setExact:$triggerAtMillis"
                enteredExact.countDown()
                assertTrue(releaseExact.await(2, TimeUnit.SECONDS))
            }
            override suspend fun cancelFallbackWork() {
                events += "cancelWork"
            }
            override suspend fun enqueueFallbackWork(delayMillis: Long) {
                events += "enqueueWork:$delayMillis"
            }
        }
        val oldRequest = coordinator.registerRequest()
        val oldThread = thread {
            result.set(
                runBlocking {
                    coordinator.reconcile(oldRequest, 2_000L, { 1_000L }, operations)
                },
            )
        }

        assertTrue(enteredExact.await(2, TimeUnit.SECONDS))
        coordinator.registerRequest() // 新请求随后被取消，不执行 reconcile。
        releaseExact.countDown()
        oldThread.join(2_000L)

        assertEquals(WidgetNextUpdateSchedulingResult.STALE_IGNORED, result.get())
        assertEquals(
            listOf("cancelAlarm", "setExact:2000", "cancelAlarm", "cancelWork"),
            events,
        )
    }

    @Test
    fun `无精确权限的 fallback 入队被取消时补偿清空两条责任`() = runBlocking {
        val coordinator = SerializedWidgetRefreshCoordinator<Unit>()
        val request = coordinator.registerRequest()
        val operations = RecordingNextUpdateOperations(
            canScheduleExact = false,
            enqueueWorkFailure = CancellationException("cancelled"),
        )

        val failure = runCatching {
            coordinator.reconcile(request, 2_000L, { 1_000L }, operations)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(
            listOf("cancelAlarm", "enqueueWork:1000", "cancelAlarm", "cancelWork"),
            operations.events,
        )
    }

    @Test
    fun `精确路径取消补偿失败时仍传播原始取消并继续清理 Work`() = runBlocking {
        val coordinator = SerializedWidgetRefreshCoordinator<Unit>()
        val request = coordinator.registerRequest()
        val events = mutableListOf<String>()
        var alarmCancellationCount = 0
        var workCancellationCount = 0
        val operations = object : WidgetNextUpdateOperations {
            override suspend fun cancelExactAlarm() {
                alarmCancellationCount += 1
                events += "cancelAlarm:$alarmCancellationCount"
                if (alarmCancellationCount == 2) error("alarm cleanup failed")
            }

            override fun canScheduleExactAlarm(): Boolean = true

            override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
                events += "setExact:$triggerAtMillis"
            }

            override suspend fun cancelFallbackWork() {
                workCancellationCount += 1
                events += "cancelWork:$workCancellationCount"
                if (workCancellationCount == 1) throw CancellationException("original cancellation")
            }

            override suspend fun enqueueFallbackWork(delayMillis: Long) = Unit
        }

        val failure = runCatching {
            coordinator.reconcile(request, 2_000L, { 1_000L }, operations)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("original cancellation", failure?.message)
        assertEquals(
            listOf(
                "cancelAlarm:1",
                "setExact:2000",
                "cancelWork:1",
                "cancelAlarm:2",
                "cancelWork:2",
            ),
            events,
        )
    }

    private class RecordingNextUpdateOperations(
        private val canScheduleExact: Boolean,
        private val exactFailure: Throwable? = null,
        private val cancelWorkFailure: Throwable? = null,
        private val enqueueWorkFailure: Throwable? = null,
    ) : WidgetNextUpdateOperations {
        val events = mutableListOf<String>()

        override suspend fun cancelExactAlarm() {
            events += "cancelAlarm"
        }

        override fun canScheduleExactAlarm(): Boolean = canScheduleExact

        override suspend fun scheduleExactAlarm(triggerAtMillis: Long) {
            events += "setExact:$triggerAtMillis"
            exactFailure?.let { throw it }
        }

        override suspend fun cancelFallbackWork() {
            events += "cancelWork"
            cancelWorkFailure?.let { throw it }
        }

        override suspend fun enqueueFallbackWork(delayMillis: Long) {
            events += "enqueueWork:$delayMillis"
            enqueueWorkFailure?.let { throw it }
        }
    }
}

class WidgetContentSourcePolicyTest {

    @Test
    fun `可用 snapshot 在数据库仍启动时仍优先作为内容源`() {
        assertEquals(
            WidgetContentSource.STARTUP_SNAPSHOT,
            WidgetContentSourcePolicy.decide(
                hasStartupSnapshot = true,
                databaseReadiness = OperationalDataReadiness.STARTING,
            ),
        )
    }

    @Test
    fun `缺失 snapshot 且数据库就绪时读取数据库`() {
        assertEquals(
            WidgetContentSource.DATABASE,
            WidgetContentSourcePolicy.decide(
                hasStartupSnapshot = false,
                databaseReadiness = OperationalDataReadiness.READY,
            ),
        )
    }

    @Test
    fun `数据库就绪后即使 snapshot 仍在内存也切换到实时数据`() {
        assertEquals(
            WidgetContentSource.DATABASE,
            WidgetContentSourcePolicy.decide(
                hasStartupSnapshot = true,
                databaseReadiness = OperationalDataReadiness.READY,
            ),
        )
    }

    @Test
    fun `缺失 snapshot 且数据库仍启动时展示准备态并安排短重试`() {
        assertEquals(
            WidgetContentSource.STARTING_RETRY,
            WidgetContentSourcePolicy.decide(
                hasStartupSnapshot = false,
                databaseReadiness = OperationalDataReadiness.STARTING,
            ),
        )
    }

    @Test
    fun `恢复门禁关闭时始终使用安全 UI 而不显示 snapshot`() {
        assertEquals(
            WidgetContentSource.RECOVERY_SAFE_UI,
            WidgetContentSourcePolicy.decide(
                hasStartupSnapshot = true,
                databaseReadiness = OperationalDataReadiness.RECOVERY_REQUIRED,
            ),
        )
    }
}

class LatestWidgetContentRuntimeTest {

    @Test
    fun `Recovery 登记与安全内容发布是单一原子操作`() {
        val coordinator = SerializedWidgetRefreshCoordinator<String>()

        val recoveryRequest = coordinator.registerAndPublish("recovery-safe")

        assertEquals("recovery-safe", coordinator.state.value)
        assertFalse(coordinator.publish(WidgetNextUpdateRequest(recoveryRequest.id - 1L), "old-live"))
        assertEquals("recovery-safe", coordinator.state.value)
    }

    @Test
    fun `较晚请求只要已登记即使尚未发布也会拒绝较早 snapshot`() {
        val coordinator = SerializedWidgetRefreshCoordinator<String>()
        val snapshotRequest = coordinator.registerRequest()
        coordinator.registerRequest()

        assertFalse(coordinator.publish(snapshotRequest, "snapshot"))
        assertNull(coordinator.state.value)
    }

    @Test
    fun `较晚请求被取消也不允许较早请求恢复发布`() {
        val coordinator = SerializedWidgetRefreshCoordinator<String>()
        val snapshotRequest = coordinator.registerRequest()
        val cancelledLiveRequest = coordinator.registerRequest()

        assertFalse(coordinator.publish(snapshotRequest, "snapshot"))
        assertTrue(coordinator.publish(cancelledLiveRequest, "live-after-retry"))
        assertEquals("live-after-retry", coordinator.state.value)
    }

    @Test
    fun `Recovery 请求发布后旧 live resolver 不得重新暴露课程`() {
        val coordinator = SerializedWidgetRefreshCoordinator<String>()
        val oldLiveRequest = coordinator.registerRequest()
        val recoveryRequest = coordinator.registerRequest()

        assertTrue(coordinator.publish(recoveryRequest, "recovery-safe"))
        assertFalse(coordinator.publish(oldLiveRequest, "old-live"))
        assertEquals("recovery-safe", coordinator.state.value)
    }
}

class ForceUpdateCompletionPolicyTest {

    @Test
    fun `更新成功后完成 PendingResult`() = runBlocking {
        val events = mutableListOf<String>()

        ForceUpdateCompletionPolicy.execute(
            updateAll = { events += "update" },
            enqueueRetry = { error("retry must not run") },
            finishPendingResult = { events += "finish" },
        )

        assertEquals(listOf("update", "finish"), events)
    }

    @Test
    fun `更新失败时先完成持久重试交接再释放 PendingResult`() = runBlocking {
        val events = mutableListOf<String>()

        ForceUpdateCompletionPolicy.execute(
            updateAll = {
                events += "update"
                error("Glance failed")
            },
            enqueueRetry = { failure -> events += "retry:${failure.message}" },
            finishPendingResult = { events += "finish" },
        )

        assertEquals(listOf("update", "retry:Glance failed", "finish"), events)
    }

    @Test
    fun `更新协程取消时仍完成 PendingResult 并向上传播取消`() = runBlocking {
        val events = mutableListOf<String>()

        val failure = runCatching {
            ForceUpdateCompletionPolicy.execute(
                updateAll = {
                    events += "update"
                    throw CancellationException("cancelled")
                },
                enqueueRetry = { events += "retry" },
                finishPendingResult = { events += "finish" },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("update", "finish"), events)
    }
}

class WidgetForceUpdatePolicyTest {

    @Test
    fun `无实例时只清理遗留责任且绝不解析内容`() = runBlocking {
        val events = mutableListOf<String>()

        WidgetForceUpdatePolicy.execute(
            hasWidgetInstances = false,
            clearResponsibilities = { events += "clear" },
            refreshWidgetContent = { events += "refresh" },
        )

        assertEquals(listOf("clear"), events)
    }

    @Test
    fun `有实例时刷新内容且不清理有效责任`() = runBlocking {
        val events = mutableListOf<String>()

        WidgetForceUpdatePolicy.execute(
            hasWidgetInstances = true,
            clearResponsibilities = { events += "clear" },
            refreshWidgetContent = { events += "refresh" },
        )

        assertEquals(listOf("refresh"), events)
    }
}

class WidgetInstanceCleanupPolicyTest {

    @Test
    fun `清理期间重新出现实例时在全部取消完成后恢复更新链路`() = runBlocking {
        val events = mutableListOf<String>()
        var hasWidgetInstances = false

        WidgetInstanceCleanupPolicy.execute(
            clearResponsibilities = {
                events += "clear"
                hasWidgetInstances = true
            },
            hasWidgetInstances = {
                events += "query"
                hasWidgetInstances
            },
            restoreAfterCleanup = { events += "restore" },
        )

        assertEquals(listOf("clear", "query", "restore"), events)
    }

    @Test
    fun `清理完成后仍无实例时不恢复更新链路`() = runBlocking {
        val events = mutableListOf<String>()

        WidgetInstanceCleanupPolicy.execute(
            clearResponsibilities = { events += "clear" },
            hasWidgetInstances = {
                events += "query"
                false
            },
            restoreAfterCleanup = { events += "restore" },
        )

        assertEquals(listOf("clear", "query"), events)
    }
}

class WidgetInstanceTopologyCoordinatorTest {

    @Test
    fun `恢复提交期间移除最后实例时清理在线性化顺序中最终获胜`() {
        val coordinator = WidgetInstanceTopologyCoordinator()
        val restoreEntered = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val clearEntered = CountDownLatch(1)
        val events = mutableListOf<String>()
        var responsibilitiesPresent = false

        val restoreThread = thread {
            coordinator.restoreIfPresent(
                hasWidgetInstances = { true },
                restoreResponsibilities = {
                    restoreEntered.countDown()
                    assertTrue(releaseRestore.await(2, TimeUnit.SECONDS))
                    responsibilitiesPresent = true
                    events += "restore"
                },
            )
        }
        assertTrue(restoreEntered.await(2, TimeUnit.SECONDS))

        val clearThread = thread {
            coordinator.clearResponsibilities {
                clearEntered.countDown()
                responsibilitiesPresent = false
                events += "clear"
            }
        }

        assertFalse(clearEntered.await(100, TimeUnit.MILLISECONDS))
        releaseRestore.countDown()
        restoreThread.join(2_000L)
        clearThread.join(2_000L)

        assertEquals(listOf("restore", "clear"), events)
        assertFalse(responsibilitiesPresent)
    }
}

class WidgetStartupRetryPolicyTest {

    @Test
    fun `启动重试仅在门禁仍启动且未超过上限时继续`() {
        assertTrue(WidgetStartupRetryPolicy.shouldRetry(OperationalDataReadiness.STARTING, 0))
        assertTrue(WidgetStartupRetryPolicy.shouldRetry(OperationalDataReadiness.STARTING, 1))
        assertFalse(WidgetStartupRetryPolicy.shouldRetry(OperationalDataReadiness.STARTING, 2))
    }

    @Test
    fun `数据库就绪或进入恢复时不再重试`() {
        assertFalse(WidgetStartupRetryPolicy.shouldRetry(OperationalDataReadiness.READY, 0))
        assertFalse(WidgetStartupRetryPolicy.shouldRetry(OperationalDataReadiness.RECOVERY_REQUIRED, 0))
    }

    @Test
    fun `本轮使用 snapshot 后即使门禁刚变 Ready 也至少再收敛一次`() {
        assertTrue(
            WidgetStartupRetryPolicy.shouldRetry(
                readiness = OperationalDataReadiness.READY,
                runAttemptCount = 0,
                usedStartupSnapshot = true,
            ),
        )
    }

    @Test
    fun `Recovery 优先于本轮使用 snapshot 且不得继续重试`() {
        assertFalse(
            WidgetStartupRetryPolicy.shouldRetry(
                readiness = OperationalDataReadiness.RECOVERY_REQUIRED,
                runAttemptCount = 0,
                usedStartupSnapshot = true,
            ),
        )
    }

    @Test
    fun `启动重试即使刷新持续失败也会在同一上限收敛`() {
        assertTrue(WidgetStartupRetryPolicy.shouldRetryFailure(isStartupRetry = true, 1))
        assertFalse(WidgetStartupRetryPolicy.shouldRetryFailure(isStartupRetry = true, 2))
        assertTrue(WidgetStartupRetryPolicy.shouldRetryFailure(isStartupRetry = false, 20))
    }
}

class WidgetBroadcastActionPolicyTest {

    @Test
    fun `系统时间日期和时区变化继续恢复刷新与重排链路`() {
        listOf(
            "android.intent.action.TIME_SET",
            "android.intent.action.DATE_CHANGED",
            "android.intent.action.TIMEZONE_CHANGED",
        ).forEach { action ->
            assertEquals(
                WidgetBroadcastAction.RESTORE_AFTER_SYSTEM_EVENT,
                WidgetBroadcastActionPolicy.resolve(action),
            )
        }
    }

    @Test
    fun `系统 AppWidget 更新同时请求重新解析内容`() {
        assertEquals(
            WidgetBroadcastAction.REFRESH_WIDGET_CONTENT,
            WidgetBroadcastActionPolicy.resolve("android.appwidget.action.APPWIDGET_UPDATE"),
        )
    }

    @Test
    fun `未知广播才完全交还 Glance 默认处理`() {
        assertEquals(
            WidgetBroadcastAction.DELEGATE_TO_GLANCE,
            WidgetBroadcastActionPolicy.resolve("custom.unknown"),
        )
    }
}
