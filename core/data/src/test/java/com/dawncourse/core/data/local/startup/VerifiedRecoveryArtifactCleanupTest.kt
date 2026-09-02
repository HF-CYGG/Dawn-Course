package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 恢复安装提交状态与旧迁移产物清理资格的时序测试。 */
class VerifiedRecoveryArtifactCleanupTest {
    @Test
    fun committedRecoveryCleansRolledBackMigrationBeforeOtherRecoveryArtifacts() {
        val events = mutableListOf<String>()

        val cleaned = cleanupArtifactsAfterVerifiedRecoveryColdOpen(
            recoveryInstallStage = DatabaseRecoveryInstallStage.COMMITTED,
            cleanupRolledBackMigration = { events += "migration"; true },
            cleanupRecoveryDatabase = { events += "database"; true },
            cleanupOldKeyMaterial = { events += "key"; true },
            cleanupInstallJournal = { events += "journal"; true },
        )

        assertTrue(cleaned)
        assertEquals(listOf("migration", "database", "key", "journal"), events)
    }

    @Test
    fun unfinishedRecoveryNeverCleansRolledBackMigrationArtifacts() {
        var migrationCleanupCalled = false

        val cleaned = cleanupArtifactsAfterVerifiedRecoveryColdOpen(
            recoveryInstallStage = DatabaseRecoveryInstallStage.ROLLED_BACK,
            cleanupRolledBackMigration = { migrationCleanupCalled = true; true },
            cleanupRecoveryDatabase = { true },
            cleanupOldKeyMaterial = { true },
            cleanupInstallJournal = { true },
        )

        assertFalse(cleaned)
        assertFalse(migrationCleanupCalled)
    }

    @Test
    fun committedRecoveryStopsBeforeDeletingOtherRecoveryArtifactsWhenMigrationCleanupFails() {
        val events = mutableListOf<String>()

        val cleaned = cleanupArtifactsAfterVerifiedRecoveryColdOpen(
            recoveryInstallStage = DatabaseRecoveryInstallStage.COMMITTED,
            cleanupRolledBackMigration = { events += "migration"; false },
            cleanupRecoveryDatabase = { events += "database"; true },
            cleanupOldKeyMaterial = { events += "key"; true },
            cleanupInstallJournal = { events += "journal"; true },
        )

        assertFalse(cleaned)
        assertEquals(listOf("migration"), events)
    }

    @Test
    fun coldOpenWithoutRecoveryDecisionOnlyCleansOrdinaryRecoveryResidue() {
        val events = mutableListOf<String>()

        val cleaned = cleanupArtifactsAfterVerifiedRecoveryColdOpen(
            recoveryInstallStage = null,
            cleanupRolledBackMigration = { events += "migration"; true },
            cleanupRecoveryDatabase = { events += "database"; true },
            cleanupOldKeyMaterial = { events += "key"; true },
            cleanupInstallJournal = { events += "journal"; true },
        )

        assertTrue(cleaned)
        assertEquals(listOf("database"), events)
    }
}
