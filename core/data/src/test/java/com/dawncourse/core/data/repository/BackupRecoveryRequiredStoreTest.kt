package com.dawncourse.core.data.repository

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份恢复 marker 的删除失败必须可观察，不能把未删除的责任误报为成功。 */
class BackupRecoveryRequiredStoreTest {
    @Test
    fun clearFailureIsPropagatedAndKeepsBackupRecoveryResponsibility() {
        val marker = MemoryBackupRecoveryMarker(required = true, failClear = true)
        val store = BackupRecoveryRequiredStore(marker)

        assertThrows(IllegalStateException::class.java) { store.clearRequired() }

        assertTrue(marker.required)
        assertTrue(store.isRequired())
    }

    private class MemoryBackupRecoveryMarker(
        var required: Boolean,
        private val failClear: Boolean,
    ) : BackupRecoveryRequiredMarker {
        override fun markRequired() {
            required = true
        }

        override fun isRequired(): Boolean = required

        override fun clearRequiredAndConfirm() {
            if (failClear) error("模拟 AtomicFile 删除失败")
            required = false
        }
    }
}
