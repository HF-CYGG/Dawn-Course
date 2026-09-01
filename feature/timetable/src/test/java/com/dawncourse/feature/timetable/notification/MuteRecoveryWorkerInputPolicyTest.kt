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

/** 专用静音恢复 Worker 的输入必须完整且严格指向 UNMUTE Key。 */
class MuteRecoveryWorkerInputPolicyTest {
    private val unmuteKey = TriggerKey(0, 42, LocalDate.of(2026, 8, 25), TriggerKind.UNMUTE)

    @Test
    fun `Work Data 携带完整 Trigger URI 并可往返`() {
        val input = MuteRecoveryWorkerInputPolicy.createInputData(unmuteKey)

        assertEquals(TriggerUriCodec.encode(unmuteKey), input.getString(MuteRecoveryWorkerInputPolicy.INPUT_TRIGGER_URI))
        assertEquals(unmuteKey, MuteRecoveryWorkerInputPolicy.decode(input))
    }

    @Test
    fun `拒绝非 unmute 缺失与非规范 URI`() {
        val muteKey = unmuteKey.copy(kind = TriggerKind.MUTE)

        assertThrows(IllegalArgumentException::class.java) {
            MuteRecoveryWorkerInputPolicy.createInputData(muteKey)
        }
        assertNull(MuteRecoveryWorkerInputPolicy.decode(workDataOf()))
        assertNull(MuteRecoveryWorkerInputPolicy.decode(workDataOf(
            MuteRecoveryWorkerInputPolicy.INPUT_TRIGGER_URI to TriggerUriCodec.encode(muteKey)
        )))
        assertNull(MuteRecoveryWorkerInputPolicy.decode(workDataOf(
            MuteRecoveryWorkerInputPolicy.INPUT_TRIGGER_URI to
                "dawn://alarm/0/42/2026-08-25/UNMUTE"
        )))
    }
}
