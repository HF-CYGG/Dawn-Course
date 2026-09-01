package com.dawncourse.core.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Alarm PendingIntent 唯一 URI 协议测试。 */
class TriggerUriCodecTest {

    @Test
    fun `大 Long ID 与日期可完整往返`() {
        val key = TriggerKey(
            profileId = Long.MAX_VALUE,
            courseId = Long.MAX_VALUE - 1,
            occurrenceDate = LocalDate.of(2026, 12, 31),
            kind = TriggerKind.UNMUTE
        )

        val encoded = TriggerUriCodec.encode(key)

        assertEquals(
            "dawn://alarm/9223372036854775807/9223372036854775806/2026-12-31/unmute",
            encoded
        )
        assertEquals(key, TriggerUriCodec.decode(encoded))
    }

    @Test
    fun `不是唯一 alarm 协议或字段非法时拒绝`() {
        val invalidValues = listOf(
            null,
            "",
            "dawn://other/0/1/2026-08-24/reminder",
            "dawn://alarm/0/1/2026-02-30/reminder",
            "dawn://alarm/-1/1/2026-08-24/reminder",
            "dawn://alarm/0/1/2026-08-24/unknown",
            "dawn://alarm/0/-1/2026-08-24/reminder",
            "dawn://alarm/0/1/2026-08-24/reminder?x=1",
            "dawn://alarm/0/1/2026-08-24/reminder/extra"
        )

        invalidValues.forEach { value -> assertNull(value, TriggerUriCodec.decode(value)) }
    }
}
