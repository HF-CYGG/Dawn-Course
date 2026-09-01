package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 明文换入后失败时，文件回滚与 Recovery 隔离的顺序契约。 */
class PostMigrationRecoveryFinalizerTest {

    @Test
    fun `Room 已打开但 COMPLETE 写入失败时先关闭并回滚再进入恢复`() {
        val events = mutableListOf<String>()

        val result = finalizeFailedPostMigration(
            closeOpenedHandle = { events += "close" },
            rollbackPlaintextPreimage = {
                events += "rollback"
                true
            },
            enterRecovery = { reason ->
                events += "recovery:$reason"
                DatabaseStartupInitialization.RecoveryRequired(reason)
            },
        )

        assertEquals(DatabaseRecoveryReason.MigrationFailed, result.reason)
        assertEquals(
            listOf("close", "rollback", "recovery:MigrationFailed"),
            events,
        )
    }

    @Test
    fun `Room 打开失败时回滚成功后才进入恢复`() {
        val events = mutableListOf<String>()

        val result = finalizeFailedPostMigration(
            rollbackPlaintextPreimage = {
                events += "rollback"
                true
            },
            enterRecovery = { reason ->
                events += "recovery:$reason"
                DatabaseStartupInitialization.RecoveryRequired(reason)
            },
        )

        assertEquals(DatabaseRecoveryReason.MigrationFailed, result.reason)
        assertEquals(listOf("rollback", "recovery:MigrationFailed"), events)
    }

    @Test
    fun `回滚失败仍进入恢复并升级为状态损坏`() {
        val events = mutableListOf<String>()

        val result = finalizeFailedPostMigration(
            closeOpenedHandle = {
                events += "close"
                error("模拟关闭失败")
            },
            rollbackPlaintextPreimage = {
                events += "rollback"
                false
            },
            enterRecovery = { reason ->
                events += "recovery:$reason"
                DatabaseStartupInitialization.RecoveryRequired(reason)
            },
        )

        assertEquals(DatabaseRecoveryReason.RecoveryStateCorrupt, result.reason)
        assertEquals(
            listOf("close", "rollback", "recovery:RecoveryStateCorrupt"),
            events,
        )
    }
}
