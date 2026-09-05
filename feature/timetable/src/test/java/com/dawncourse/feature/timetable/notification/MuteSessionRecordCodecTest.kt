package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静音恢复责任协议必须显式表达状态并兼容旧 active key/attempt。 */
class MuteSessionRecordCodecTest {
    private val key = TriggerKey(0, 7, LocalDate.of(2026, 8, 25), TriggerKind.UNMUTE)

    @Test
    fun `迁移元数据类型损坏时使用安全默认值`() {
        val corrupted = mapOf<String, Any?>(
            "mute_session_v3_migrated" to "不是布尔值",
            "active_unmute_keys" to 7,
        )

        assertFalse(MuteSessionMigrationValueReader.isV3Complete(corrupted))
        assertTrue(MuteSessionMigrationValueReader.legacyUris(corrupted).isEmpty())
    }

    @Test
    fun `显式状态记录可稳定往返`() {
        val recoveryAt = Instant.parse("2026-08-25T03:04:05Z")
        MuteSessionStatus.entries.forEach { status ->
            val attempt = when (status) {
                MuteSessionStatus.ACTIVE -> 0
                MuteSessionStatus.RECOVERY_PENDING -> 2
                MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED -> 3
            }
            val record = MuteSessionRecord(
                key = key,
                status = status,
                recoveryAttempt = attempt,
                recoveryAt = recoveryAt,
                ownership = MuteSystemOwnership(
                    mode = MuteApplicationMode.APP_OWNED_DND,
                    originalRingerState = RingerState.SILENT,
                    ownedRingerState = null,
                    appDndActivationOwned = true,
                ),
            )

            assertEquals(record, MuteSessionRecordCodec.decode(
                MuteSessionRecordCodec.preferenceKey(key),
                MuteSessionRecordCodec.encodeValue(record)
            ))
        }
    }

    @Test
    fun `v2 记录解码为仅恢复震动的兼容责任且绝不撤销 DND`() {
        val decoded = MuteSessionRecordCodec.decode(
            MuteSessionRecordCodec.preferenceKey(key),
            "v2|ACTIVE|0|",
        )

        assertEquals(MuteApplicationMode.LEGACY_V2_VIBRATE, decoded?.ownership?.mode)
        assertEquals(RingerState.NORMAL, decoded?.ownership?.originalRingerState)
        assertEquals(RingerState.VIBRATE, decoded?.ownership?.ownedRingerState)
        assertEquals(false, decoded?.ownership?.appDndActivationOwned)
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
        assertEquals(MuteApplicationMode.UNKNOWN_QUARANTINED, quarantined?.ownership?.mode)
    }

    @Test
    fun `不可能的 DND 与铃声组合不会获得系统恢复权限`() {
        val quarantined = MuteSessionRecordCodec.decode(
            MuteSessionRecordCodec.preferenceKey(key),
            "v3|ACTIVE|0||APP_OWNED_DND|SILENT|VIBRATE|true",
        )

        assertEquals(MuteSessionStatus.EXHAUSTED_USER_ACTION_REQUIRED, quarantined?.status)
        assertEquals(MuteApplicationMode.UNKNOWN_QUARANTINED, quarantined?.ownership?.mode)
        assertEquals(false, quarantined?.ownership?.appDndActivationOwned)
    }

    @Test
    fun `DND 不确定结果可持久表达应用规则与震动复合责任`() {
        val composite = MuteSessionRecord(
            key = key,
            status = MuteSessionStatus.ACTIVE,
            recoveryAttempt = 0,
            ownership = MuteSystemOwnership(
                mode = MuteApplicationMode.APP_OWNED_DND,
                originalRingerState = RingerState.NORMAL,
                ownedRingerState = RingerState.VIBRATE,
                appDndActivationOwned = true,
            ),
        )

        assertEquals(
            composite,
            MuteSessionRecordCodec.decode(
                MuteSessionRecordCodec.preferenceKey(key),
                MuteSessionRecordCodec.encodeValue(composite),
            ),
        )
    }
}
