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
            File(noBackupDirectory, "recovery/integrity_verification_required_v1"),
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

    @Test
    fun completedMigrationJournalStillForcesSynchronousVerificationBeforeCleanup() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-migration")
        val databaseFile = File(temporaryFolder.newFolder("databases-migration"), "dawn_course.db")
        File(databaseFile.parentFile, "${databaseFile.name}.sqlcipher-migration.journal")
            .writeText("DAWN_SQLCIPHER_MIGRATION_V1\nstage=COMPLETE")

        val snapshot = DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)

        assertTrue(snapshot.requiresFullRecoveryCheck)
        assertTrue(
            "待验证后清理的迁移 journal 必须强制同步扫描，不能被新时间戳绕到后台",
            snapshot.recoveryResponsibilityMarkerPresent,
        )
    }

    @Test
    fun atomicFileBackupAndInterruptedNewArtifactsAreRecoveryResponsibilities() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-atomic-artifacts")
        val databaseFile = File(temporaryFolder.newFolder("databases-atomic-artifacts"), "dawn_course.db")
        val knownMarkers = listOf(
            "database-recovery/bootstrap-install-v1",
            "database-recovery/recovery-state-v1",
            "recovery/backup_restore_required",
            "recovery/integrity_verification_required_v1",
        )

        knownMarkers.forEach { relativePath ->
            val backup = File(noBackupDirectory, "$relativePath.bak")
            backup.parentFile?.mkdirs()
            backup.writeText("previous durable marker")

            assertTrue(
                "仅保留 .bak 的 $relativePath 仍是恢复责任，不能被快速路径遗漏",
                DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                    .recoveryResponsibilityMarkerPresent,
            )

            assertTrue(backup.delete())
        }

        val interrupted = File(noBackupDirectory, "database-recovery/recovery-state-v1.new")
        interrupted.parentFile?.mkdirs()
        interrupted.writeText("interrupted write")

        assertTrue(
            "孤立 .new 必须被视为中断状态而非 Missing",
            DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                .requiresFullRecoveryCheck,
        )
    }

    @Test
    fun strictRekeyArtifactsForceRecoveryButUnknownNeighborsDoNot() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-rekey-artifacts")
        val databaseFile = File(temporaryFolder.newFolder("databases-rekey-artifacts"), "dawn_course.db")
        val envelopeDirectory = File(noBackupDirectory, "database").apply { mkdirs() }
        val attemptId = "00000000-0000-0000-0000-000000000001"
        val known = listOf(
            File(databaseFile.parentFile, "${databaseFile.name}.raw-temp.$attemptId-wal.at-rekey"),
            File(envelopeDirectory, "dawn_course_key_envelope.bin.raw-staged.$attemptId.new"),
        )

        known.forEach { artifact ->
            artifact.writeText("known")
            assertTrue(
                DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                    .requiresFullRecoveryCheck,
            )
            assertTrue(artifact.delete())
        }

        File(databaseFile.parentFile, "${databaseFile.name}.raw-temp.$attemptId-user-file")
            .writeText("unknown")
        File(envelopeDirectory, "dawn_course_key_envelope.bin.raw-staged.$attemptId-user-file")
            .writeText("unknown")

        assertFalse(
            DatabaseStartupRecoveryMarkerSnapshot.capture(noBackupDirectory, databaseFile)
                .requiresFullRecoveryCheck,
        )
    }
}
