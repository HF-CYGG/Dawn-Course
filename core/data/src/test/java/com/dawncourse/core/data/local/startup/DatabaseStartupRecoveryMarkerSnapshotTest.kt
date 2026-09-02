package com.dawncourse.core.data.local.startup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 常态启动快速判断不得遗漏任一既有恢复 marker。 */
class DatabaseStartupRecoveryMarkerSnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun everyExistingRecoveryMarkerRequiresTheFullRecoverySequence() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup")
        val databaseFile = File(temporaryFolder.newFolder("databases"), "dawn_course.db")

        assertFalse(
            DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                .requiresFullRecoveryCheck
        )

        listOf(
            File(noBackupDirectory, "database-recovery/bootstrap-install-v1"),
            File(noBackupDirectory, "database-recovery/recovery-state-v1"),
            File(noBackupDirectory, "recovery/backup_restore_required"),
            File(databaseFile.parentFile, "${databaseFile.name}.sqlcipher-migration.journal"),
        ).forEach { marker ->
            marker.parentFile?.mkdirs()
            marker.writeText("marker")

            assertTrue(
                "marker ${marker.name} 不得被常态启动快速路径跳过",
                DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                    .requiresFullRecoveryCheck
            )

            assertTrue(marker.delete())
        }
    }
}
