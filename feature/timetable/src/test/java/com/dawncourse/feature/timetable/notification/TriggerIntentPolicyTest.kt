package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Receiver 对新 URI/action 的封口校验。 */
class TriggerIntentPolicyTest {

    @Test
    fun `只接受 action 与 URI kind 一致的新触发器`() {
        val key = TriggerKey(0, 1, LocalDate.of(2026, 8, 24), TriggerKind.MUTE)

        assertEquals(
            key,
            TriggerIntentPolicy.parse(
                action = SilenceReceiver.ACTION_MUTE,
                dataUri = TriggerUriCodec.encode(key)
            )
        )
    }

    @Test
    fun `旧版无 URI 广播与 action 错配广播一律拒绝`() {
        val key = TriggerKey(0, 1, LocalDate.of(2026, 8, 24), TriggerKind.UNMUTE)

        assertNull(TriggerIntentPolicy.parse(SilenceReceiver.ACTION_UNMUTE, null))
        assertNull(
            TriggerIntentPolicy.parse(
                SilenceReceiver.ACTION_MUTE,
                TriggerUriCodec.encode(key)
            )
        )
    }
}
