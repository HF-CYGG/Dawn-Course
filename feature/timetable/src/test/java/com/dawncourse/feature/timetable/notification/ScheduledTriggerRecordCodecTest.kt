package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerPrecision
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 可枚举触发器注册表的损坏隔离测试。 */
class ScheduledTriggerRecordCodecTest {

    @Test
    fun `单条损坏记录不影响其他记录恢复`() {
        val valid = scheduled(courseId = Long.MAX_VALUE)
        val entries = mapOf<String, Any?>(
            ScheduledTriggerRecordCodec.preferenceKey(valid.key) to
                ScheduledTriggerRecordCodec.encodeValue(valid),
            "trigger:dawn://alarm/0/2/2026-08-24/reminder" to "broken",
            "unrelated" to 1
        )

        val decoded = ScheduledTriggerRecordCodec.decodeAll(entries)

        assertEquals(listOf(valid), decoded.records)
        assertEquals(
            setOf("trigger:dawn://alarm/0/2/2026-08-24/reminder"),
            decoded.corruptedEntryNames
        )
        assertEquals(
            setOf(TriggerKey(0, 2, LocalDate.of(2026, 8, 24), TriggerKind.REMINDER)),
            decoded.corruptedKeys
        )
    }

    @Test
    fun `无法解析 key 的损坏项只进入审计而不伪造 trigger`() {
        val decoded = ScheduledTriggerRecordCodec.decodeAll(
            mapOf("trigger:not-a-trigger-uri" to "broken")
        )

        assertEquals(emptySet<TriggerKey>(), decoded.corruptedKeys)
        assertEquals(setOf("trigger:not-a-trigger-uri"), decoded.corruptedEntryNames)
    }

    private fun scheduled(courseId: Long): ScheduledTrigger = ScheduledTrigger(
        key = TriggerKey(0, courseId, LocalDate.of(2026, 8, 24), TriggerKind.REMINDER),
        triggerAt = Instant.parse("2026-08-24T00:00:00Z"),
        precision = TriggerPrecision.INEXACT
    )
}
