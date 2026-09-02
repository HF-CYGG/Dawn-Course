package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.ScheduledTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerPrecision
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trigger Desired/Scheduled 差异算法的纯 Kotlin 回归测试。 */
class TriggerDiffCalculatorTest {

    @Test
    fun `普通对账保留相同触发器并增删变化项`() {
        val kept = scheduled(courseId = 1, minute = 10)
        val removed = scheduled(courseId = 2, minute = 20)
        val added = desired(courseId = Long.MAX_VALUE, minute = 30)

        val diff = TriggerDiffCalculator.calculate(
            desired = listOf(added, kept.toDesired()),
            scheduled = listOf(removed, kept),
            forceReplay = false
        )

        assertEquals(listOf(added), diff.add)
        assertEquals(listOf(kept), diff.keep)
        assertEquals(listOf(removed), diff.remove)
    }

    @Test
    fun `同 key 触发时间变化同时进入 remove 与 add`() {
        val old = scheduled(courseId = 9, minute = 10)
        val changed = desired(courseId = 9, minute = 11)

        val diff = TriggerDiffCalculator.calculate(
            desired = listOf(changed),
            scheduled = listOf(old),
            forceReplay = false
        )

        assertEquals(listOf(changed), diff.add)
        assertTrue(diff.keep.isEmpty())
        assertEquals(listOf(old), diff.remove)
    }

    @Test
    fun `force replay 重放全部 desired 但只删除 stale`() {
        val same = scheduled(courseId = 7, minute = 10)
        val stale = scheduled(courseId = 8, minute = 20)

        val diff = TriggerDiffCalculator.calculate(
            desired = listOf(same.toDesired()),
            scheduled = listOf(same, stale),
            forceReplay = true
        )

        assertEquals(listOf(same.toDesired()), diff.add)
        assertTrue(diff.keep.isEmpty())
        assertEquals(listOf(stale), diff.remove)
    }

    @Test
    fun `差异输出按安全种类与稳定字段排序`() {
        val desired = listOf(
            desired(courseId = 3, minute = 10, kind = TriggerKind.MUTE),
            desired(courseId = 2, minute = 10, kind = TriggerKind.REMINDER),
            desired(courseId = 1, minute = 10, kind = TriggerKind.UNMUTE)
        )

        val diff = TriggerDiffCalculator.calculate(desired, emptyList(), forceReplay = false)

        assertEquals(
            listOf(TriggerKind.UNMUTE, TriggerKind.REMINDER, TriggerKind.MUTE),
            diff.add.map { trigger -> trigger.key.kind }
        )
    }

    private fun desired(
        courseId: Long,
        minute: Long,
        kind: TriggerKind = TriggerKind.REMINDER
    ): DesiredTrigger = DesiredTrigger(
        key = TriggerKey(
            profileId = TriggerKey.LEGACY_PROFILE_ID,
            courseId = courseId,
            occurrenceDate = LocalDate.of(2026, 8, 24),
            kind = kind
        ),
        triggerAt = Instant.parse("2026-08-24T00:${minute.toString().padStart(2, '0')}:00Z")
    )

    private fun scheduled(courseId: Long, minute: Long): ScheduledTrigger = ScheduledTrigger(
        key = desired(courseId, minute).key,
        triggerAt = desired(courseId, minute).triggerAt,
        precision = TriggerPrecision.EXACT
    )

    private fun ScheduledTrigger.toDesired(): DesiredTrigger = DesiredTrigger(key, triggerAt)
}
