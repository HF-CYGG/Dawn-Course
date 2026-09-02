package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerPrecision
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Android Adapter 之上的触发器对账流程测试。 */
class TriggerReconcilerTest {

    @Test
    fun `课程删除后取消 registry 中的旧 key 并提交空快照`() = runBlocking {
        val old = scheduled(99, TriggerKind.REMINDER)
        val registry = FakeRegistry(listOf(old))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(emptyList(), forceReplay = false)

        assertEquals(listOf(old.key), gateway.cancelled)
        assertEquals(emptyList<ScheduledTrigger>(), registry.written)
        assertEquals(1, registry.writeCount)
    }

    @Test
    fun `切换活动 Profile 时移除旧课表 trigger 并只保留新课表 desired`() = runBlocking {
        val oldProfileTrigger = scheduled(profileId = 1L, courseId = 99L, kind = TriggerKind.REMINDER)
        val activeProfileDesired = desired(profileId = 2L, courseId = 99L, kind = TriggerKind.REMINDER)
        val registry = FakeRegistry(listOf(oldProfileTrigger))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(
            desired = listOf(activeProfileDesired),
            forceReplay = false
        )

        assertEquals(listOf(activeProfileDesired), gateway.scheduled)
        assertEquals(listOf(oldProfileTrigger.key), gateway.cancelled)
        assertEquals(listOf(activeProfileDesired.key), registry.written.map { it.key })
    }

    @Test
    fun `legacy profile 注册表记录在首次真实 Profile 对账时被清理`() = runBlocking {
        val legacy = scheduled(profileId = TriggerKey.LEGACY_PROFILE_ID, courseId = 1L, kind = TriggerKind.MUTE)
        val active = desired(profileId = 2L, courseId = 1L, kind = TriggerKind.MUTE)
        val registry = FakeRegistry(listOf(legacy))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(listOf(active), forceReplay = false)

        assertEquals(listOf(active), gateway.scheduled)
        assertEquals(listOf(legacy.key), gateway.cancelled)
    }

    @Test
    fun `时间变化时先以同一 key 覆盖调度且不取消新 alarm`() = runBlocking {
        val old = scheduled(5, TriggerKind.MUTE, minute = 10)
        val desired = desired(5, TriggerKind.MUTE, minute = 20)
        val registry = FakeRegistry(listOf(old))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(listOf(desired), forceReplay = false)

        assertEquals(listOf(desired), gateway.scheduled)
        assertEquals(emptyList<TriggerKey>(), gateway.cancelled)
        assertEquals(desired.triggerAt, registry.written.single().triggerAt)
    }

    @Test
    fun `force replay 按 unmute reminder mute 安全顺序重放`() = runBlocking {
        val desired = listOf(
            desired(3, TriggerKind.MUTE),
            desired(2, TriggerKind.REMINDER),
            desired(1, TriggerKind.UNMUTE)
        )
        val registry = FakeRegistry(desired.map { value -> value.scheduled() })
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(desired, forceReplay = true)

        assertEquals(
            listOf(TriggerKind.UNMUTE, TriggerKind.REMINDER, TriggerKind.MUTE),
            gateway.scheduled.map { value -> value.key.kind }
        )
    }

    @Test
    fun `闹钟下发失败时不提前改写 registry`() {
        val registry = FakeRegistry(emptyList())
        val gateway = FakeGateway(scheduleFailure = IOException("alarm failed"))

        assertThrows(IOException::class.java) {
            runBlocking {
                TriggerReconciler(registry, gateway).reconcile(
                    listOf(desired(1, TriggerKind.REMINDER)),
                    forceReplay = false
                )
            }
        }
        assertEquals(0, registry.writeCount)
    }

    @Test
    fun `registry 写入失败向上抛出而不假装成功`() {
        val registry = FakeRegistry(emptyList(), writeFailure = IOException("commit=false"))

        assertThrows(IOException::class.java) {
            runBlocking {
                TriggerReconciler(registry, FakeGateway()).reconcile(
                    listOf(desired(1, TriggerKind.REMINDER)),
                    forceReplay = false
                )
            }
        }
    }

    @Test
    fun `关闭自动静音后保留应用正在承担的 unmute 恢复责任`() = runBlocking {
        val unmute = scheduled(5, TriggerKind.UNMUTE, minute = 30)
        val registry = FakeRegistry(listOf(unmute))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(
            desired = emptyList(),
            forceReplay = false,
            protectedUnmuteKeys = setOf(unmute.key),
            retainedUnmuteAlarmKeys = setOf(unmute.key)
        )

        assertEquals(emptyList<TriggerKey>(), gateway.cancelled)
        assertEquals(listOf(unmute), registry.written)
    }

    @Test
    fun `合法 key 的损坏 value 若已移动则先覆盖为新 reminder`() = runBlocking {
        val moved = desired(7, TriggerKind.REMINDER, minute = 20)
        val registry = FakeRegistry(
            current = emptyList(),
            corruptedKeys = setOf(moved.key),
            corruptedEntryNames = setOf(ScheduledTriggerRecordCodec.preferenceKey(moved.key))
        )
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(listOf(moved), forceReplay = false)

        assertEquals(listOf(moved), gateway.scheduled)
        assertEquals(emptyList<TriggerKey>(), gateway.cancelled)
        assertEquals(moved.triggerAt, registry.written.single().triggerAt)
        assertEquals(1, registry.quarantined.size)
    }

    @Test
    fun `合法 key 的损坏 stale 记录会实际 cancel`() = runBlocking {
        val staleKey = desired(8, TriggerKind.MUTE).key
        val registry = FakeRegistry(
            current = emptyList(),
            corruptedKeys = setOf(staleKey),
            corruptedEntryNames = setOf(ScheduledTriggerRecordCodec.preferenceKey(staleKey))
        )
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(emptyList(), forceReplay = false)

        assertEquals(listOf(staleKey), gateway.cancelled)
        assertEquals(1, registry.quarantined.size)
    }

    @Test
    fun `受保护 unmute 即使 registry value 损坏也不得 cancel`() = runBlocking {
        val protectedKey = desired(9, TriggerKind.UNMUTE).key
        val registry = FakeRegistry(
            current = emptyList(),
            corruptedKeys = setOf(protectedKey),
            corruptedEntryNames = setOf(ScheduledTriggerRecordCodec.preferenceKey(protectedKey))
        )
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(
            desired = emptyList(),
            forceReplay = false,
            protectedUnmuteKeys = setOf(protectedKey)
        )

        assertEquals(emptyList<TriggerKey>(), gateway.cancelled)
        assertEquals(emptyList<ScheduledTrigger>(), registry.written)
        assertEquals(1, registry.quarantined.size)
    }

    @Test
    fun `专用 Worker 接管的健康 past unmute 从 registry 移除且不再 force replay`() = runBlocking {
        val pending = scheduled(10, TriggerKind.UNMUTE, minute = 30)
        val registry = FakeRegistry(listOf(pending))
        val gateway = FakeGateway()

        TriggerReconciler(registry, gateway).reconcile(
            desired = emptyList(),
            forceReplay = true,
            protectedUnmuteKeys = setOf(pending.key),
            retainedUnmuteAlarmKeys = emptySet()
        )

        assertEquals(emptyList<DesiredTrigger>(), gateway.scheduled)
        assertEquals(listOf(pending.key), gateway.cancelled)
        assertEquals(emptyList<ScheduledTrigger>(), registry.written)
    }

    private class FakeRegistry(
        private val current: List<ScheduledTrigger>,
        private val writeFailure: Throwable? = null,
        private val corruptedKeys: Set<TriggerKey> = emptySet(),
        private val corruptedEntryNames: Set<String> = emptySet()
    ) : ScheduledTriggerRegistry {
        var written: List<ScheduledTrigger> = current
        var writeCount: Int = 0
        var quarantined: Set<String> = emptySet()

        override suspend fun read(): ScheduledTriggerRegistrySnapshot = ScheduledTriggerRegistrySnapshot(
            records = current,
            corruptedKeys = corruptedKeys,
            corruptedEntryNames = corruptedEntryNames
        )

        override suspend fun replaceAll(
            triggers: List<ScheduledTrigger>,
            quarantinedEntryNames: Set<String>
        ) {
            writeCount += 1
            writeFailure?.let { failure -> throw failure }
            written = triggers
            quarantined = quarantinedEntryNames
        }
    }

    private class FakeGateway(
        private val scheduleFailure: Throwable? = null
    ) : TriggerAlarmGateway {
        val scheduled = mutableListOf<DesiredTrigger>()
        val cancelled = mutableListOf<TriggerKey>()

        override fun schedule(trigger: DesiredTrigger): TriggerPrecision {
            scheduleFailure?.let { failure -> throw failure }
            scheduled += trigger
            return TriggerPrecision.EXACT
        }

        override fun cancel(key: TriggerKey) {
            cancelled += key
        }
    }

    private fun desired(
        courseId: Long,
        kind: TriggerKind,
        minute: Long = 10,
        profileId: Long = TriggerKey.LEGACY_PROFILE_ID
    ): DesiredTrigger = DesiredTrigger(
        TriggerKey(profileId, courseId, LocalDate.of(2026, 8, 24), kind),
        Instant.parse("2026-08-24T00:${minute.toString().padStart(2, '0')}:00Z")
    )

    private fun scheduled(
        courseId: Long,
        kind: TriggerKind,
        minute: Long = 10,
        profileId: Long = TriggerKey.LEGACY_PROFILE_ID
    ): ScheduledTrigger = desired(courseId, kind, minute, profileId).scheduled()

    private fun DesiredTrigger.scheduled(): ScheduledTrigger = ScheduledTrigger(
        key = key,
        triggerAt = triggerAt,
        precision = TriggerPrecision.EXACT
    )
}
