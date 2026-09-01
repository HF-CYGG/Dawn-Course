package com.dawncourse.feature.timetable.notification

import androidx.work.workDataOf
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 触发就绪重试 Worker 的输入必须完整，且只接受 REMINDER / MUTE Key。 */
class TriggerReadinessRetryInputPolicyTest {
    private val reminderKey = TriggerKey(7, 42, LocalDate.of(2026, 8, 25), TriggerKind.REMINDER)
    private val muteKey = reminderKey.copy(kind = TriggerKind.MUTE)
    private val unmuteKey = reminderKey.copy(kind = TriggerKind.UNMUTE)

    @Test
    fun `REMINDER 与 MUTE 的 Work Data 携带完整 URI 并可往返`() {
        listOf(reminderKey, muteKey).forEach { key ->
            val input = TriggerReadinessRetryInputPolicy.createInputData(key)

            assertEquals(
                TriggerUriCodec.encode(key),
                input.getString(TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI)
            )
            assertEquals(key, TriggerReadinessRetryInputPolicy.decode(input))
        }
    }

    @Test
    fun `拒绝 UNMUTE 缺失与非规范 URI`() {
        assertThrows(IllegalArgumentException::class.java) {
            TriggerReadinessRetryInputPolicy.createInputData(unmuteKey)
        }
        assertNull(TriggerReadinessRetryInputPolicy.decode(workDataOf()))
        assertNull(
            TriggerReadinessRetryInputPolicy.decode(
                workDataOf(
                    TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI to
                        TriggerUriCodec.encode(unmuteKey)
                )
            )
        )
        assertNull(
            TriggerReadinessRetryInputPolicy.decode(
                workDataOf(
                    TriggerReadinessRetryInputPolicy.INPUT_TRIGGER_URI to
                        "dawn://alarm/7/42/2026-08-25/REMINDER"
                )
            )
        )
    }

    @Test
    fun `唯一任务名随 Key 稳定变化`() {
        assertEquals(
            "TriggerReadinessRetry:${TriggerUriCodec.encode(reminderKey)}",
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey)
        )
        assertEquals(
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey),
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey)
        )
        assertEquals(
            false,
            WorkManagerTriggerReadinessRetryScheduler.workName(reminderKey) ==
                WorkManagerTriggerReadinessRetryScheduler.workName(muteKey)
        )
    }
}
