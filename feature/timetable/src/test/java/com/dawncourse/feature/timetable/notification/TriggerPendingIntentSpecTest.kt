package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** PendingIntent 完全依赖 component/action/data 的身份测试。 */
class TriggerPendingIntentSpecTest {

    @Test
    fun `所有课程 trigger requestCode 均为零且日期体现在 URI`() {
        val first = TriggerPendingIntentSpec.from(
            TriggerKey(0, Long.MAX_VALUE, LocalDate.of(2026, 8, 24), TriggerKind.REMINDER)
        )
        val second = TriggerPendingIntentSpec.from(
            TriggerKey(0, Long.MAX_VALUE, LocalDate.of(2026, 8, 25), TriggerKind.REMINDER)
        )

        assertEquals(0, first.requestCode)
        assertEquals(0, second.requestCode)
        assertNotEquals(first.dataUri, second.dataUri)
        assertEquals(ReminderReceiver.ACTION_REMINDER, first.action)
    }
}
