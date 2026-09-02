package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显式恢复不能吞掉备份 recovery marker 的删除故障。 */
class BackupRecoveryRequiredStoreContractTest {
    @Test
    fun clearRequiredMustConfirmAtomicMarkerRemovalInsteadOfSwallowingDeleteFailure() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/BackupRecoveryRequiredStore.kt",
        ).readText()

        assertFalse(
            "删除失败不得只被 runCatching 吞掉",
            source.contains("runCatching { markerFile.delete() }"),
        )
        assertTrue(
            "删除后必须确认 AtomicFile 全部残留均已消失",
            source.contains("deleteAndConfirm"),
        )
    }
}
