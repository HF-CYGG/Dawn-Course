package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local 与 WebDAV 恢复必须共用同一个破坏性操作门禁。 */
class BackupRepositoryValidationContractTest {

    @Test
    fun localAndWebDavRestoreUseSharedGate() {
        val local = source("LocalBackupRepositoryImpl.kt")
        val webDav = source("WebDavSyncRepositoryImpl.kt")

        assertTrue(local.contains("BackupRestoreGate.validateThenCommit"))
        assertTrue(webDav.contains("BackupRestoreGate.validateThenCommit"))
    }

    @Test
    fun bothExportsResolveActiveProfileWithoutLegacySemesterSelection() {
        val local = source("LocalBackupRepositoryImpl.kt")
        val webDav = source("WebDavSyncRepositoryImpl.kt")

        assertTrue(local.contains("backupSnapshotBuilder.build()"))
        assertTrue(webDav.contains("backupSnapshotBuilder.build()"))
        val builder = source("BackupSnapshotBuilder.kt")
        assertTrue(builder.contains("profileSelectionCoordinator.withResolvedActiveContext"))
        assertTrue(!builder.contains("withResolvedSelectionForExport"))
        assertTrue(builder.contains("database.withTransaction"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/core/data/repository/$name"),
            File("core/data/src/main/java/com/dawncourse/core/data/repository/$name")
        )
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("找不到源码：$name")
    }
}
