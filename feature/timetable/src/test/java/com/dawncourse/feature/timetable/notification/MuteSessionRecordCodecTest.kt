package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 静音恢复责任协议必须显式表达状态并兼容旧 active key/attempt。 */
class MuteSessionRecordCodecTest {
    private val key = TriggerKey(0, 7, LocalDate.of(2026, 8, 25), TriggerKind.UNMUTE)

    @Test
    fun `显式状态记录可稳定往返`() {
        val recoveryAt = Instant.parse("2026-08-25T03:04:05Z")
        MuteSessionStatus.entries.forEach { status ->
            val attempt = when (status) {
                MuteSessionStatus.ACTIVE -> 0
                MuteSessionStatus.RECOVERY_PENDING -> 2
                MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED -> 3
            }
            val record = MuteSessionRecord(key, status, attempt, recoveryAt)

            assertEquals(record, MuteSessionRecordCodec.decode(
                MuteSessionRecordCodec.preferenceKey(key),
                MuteSessionRecordCodec.encodeValue(record)
            ))
        }
    }

    @Test
    fun `旧 active key 与失败次数迁移成对应显式状态`() {
        val uri = TriggerUriCodec.encode(key)

        assertEquals(MuteSessionStatus.ACTIVE, MuteSessionRecordCodec.fromLegacy(uri, 0)?.status)
        assertEquals(MuteSessionStatus.RECOVERY_PENDING, MuteSessionRecordCodec.fromLegacy(uri, 2)?.status)
        assertEquals(
            MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED,
            MuteSessionRecordCodec.fromLegacy(uri, 3)?.status
        )
        assertNull(MuteSessionRecordCodec.fromLegacy("broken", 1))
    }

    @Test
    fun `value 损坏但 key 完整时隔离为用户处理责任`() {
        val quarantined = MuteSessionRecordCodec.decode(
            MuteSessionRecordCodec.preferenceKey(key),
            "broken-value"
        )

        assertEquals(key, quarantined?.key)
        assertEquals(MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED, quarantined?.status)
        assertEquals(MuteSessionCoordinator.MAX_RECOVERY_ATTEMPTS, quarantined?.recoveryAttempt)
    }
}
