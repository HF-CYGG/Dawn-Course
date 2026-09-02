package com.dawncourse.core.data.local.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 明文数据库原子加密换入协调器的纯 JVM 契约测试。 */
class PlaintextToSqlCipherMigratorTest {
    @Test
    fun matchingExportIsSwappedReopenedAndLeftSwappedNotVerifiedUntilCallerConfirms() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val snapshot = sampleSnapshot()
        val backend = FakeMigrationBackend(events, snapshot)
        val migrator = PlaintextToSqlCipherMigrator(files, backend)
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = migrator.migrate(passphrase)

        assertTrue(result is PlaintextToSqlCipherMigrationResult.Success)
        // migrate() 本身不再标记 COMPLETE：调用方（Room 打开/迁移）还没有验证，
        // 过早提交会让明文 pre-image 从此无法回滚。
        assertEquals(
            listOf(
                "lock", "recover", "begin", "checkpoint", "preimage", "stage:PREIMAGE_READY",
                "inspect-plaintext", "export", "stage:ENCRYPTED_TEMP_READY", "stage:SWAP_PENDING",
                "swap", "stage:SWAPPED_NOT_VERIFIED", "inspect-encrypted", "unlock"
            ),
            events
        )
        passphrase.close()
    }

    @Test
    fun confirmCompleteRecordsCompleteUnderItsOwnLock() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val snapshot = sampleSnapshot()
        val migrator = PlaintextToSqlCipherMigrator(files, FakeMigrationBackend(events, snapshot))
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })
        val success = migrator.migrate(passphrase) as PlaintextToSqlCipherMigrationResult.Success
        events.clear()

        val confirmed = migrator.confirmComplete(success.attempt)

        assertTrue("调用方验证成功后必须能提交完成状态", confirmed)
        assertEquals(listOf("lock", "stage:COMPLETE", "unlock"), events)
        passphrase.close()
    }

    @Test
    fun abandonAfterOpenFailureRollsBackSwappedNotVerifiedAttempt() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val snapshot = sampleSnapshot()
        val migrator = PlaintextToSqlCipherMigrator(files, FakeMigrationBackend(events, snapshot))
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })
        val success = migrator.migrate(passphrase) as PlaintextToSqlCipherMigrationResult.Success
        events.clear()

        val abandoned = migrator.abandonAfterOpenFailure(success.attempt)

        assertTrue("SWAPPED_NOT_VERIFIED 阶段必须仍能物理回滚到明文 pre-image", abandoned)
        assertEquals(listOf("lock", "rollback", "unlock"), events)
        passphrase.close()
    }

    @Test
    fun schemaMismatchKeepsUntouchedMainWithoutUsingPreimageRollback() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val source = sampleSnapshot()
        val target = source.copy(
            schema = source.schema + DatabaseSchemaIdentity("index", "unexpected", "courses", "CREATE INDEX unexpected")
        )
        val backend = FakeMigrationBackend(events, source, exported = target)
        val migrator = PlaintextToSqlCipherMigrator(files, backend)
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = migrator.migrate(passphrase)

        assertEquals(
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.ValidationFailed),
            result
        )
        assertTrue("验证失败不得换入", "swap" !in events)
        assertTrue("换入前主库仍是原件，不得用 pre-image 覆盖", "rollback" !in events)
        assertTrue("失败 attempt 应持久化结束状态", "stage:ROLLED_BACK" in events)
        passphrase.close()
    }

    @Test
    fun reopenFailureAfterSwapRestoresPlaintextPreimage() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val snapshot = sampleSnapshot()
        val backend = FakeMigrationBackend(events, snapshot, reopenFailure = true)
        val migrator = PlaintextToSqlCipherMigrator(files, backend)
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = migrator.migrate(passphrase)

        assertEquals(
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.ReopenValidationFailed),
            result
        )
        assertTrue(events.indexOf("swap") < events.indexOf("rollback"))
        passphrase.close()
    }

    @Test
    fun incompletePriorAttemptIsRecoveredBeforeNewCheckpoint() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events, recovery = DatabaseMigrationRecovery.Recovered)
        val snapshot = sampleSnapshot()
        val migrator = PlaintextToSqlCipherMigrator(files, FakeMigrationBackend(events, snapshot))
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = migrator.migrate(passphrase)

        assertTrue(result is PlaintextToSqlCipherMigrationResult.Success)
        assertTrue(events.indexOf("recover") < events.indexOf("checkpoint"))
        passphrase.close()
    }

    @Test
    fun failedCrashRecoveryStopsBeforeDatabaseAccess() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events, recovery = DatabaseMigrationRecovery.Failed)
        val snapshot = sampleSnapshot()
        val migrator = PlaintextToSqlCipherMigrator(files, FakeMigrationBackend(events, snapshot))
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = migrator.migrate(passphrase)

        assertEquals(
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.CrashRecoveryFailed),
            result
        )
        assertTrue("恢复失败后不得访问数据库", "checkpoint" !in events)
        passphrase.close()
    }

    @Test
    fun checkpointFailureLeavesUntouchedMainInPlaceWithoutAttemptingPreimageRollback() {
        val events = mutableListOf<String>()
        val files = FakeMigrationFiles(events)
        val snapshot = sampleSnapshot()
        val backend = object : PlaintextToSqlCipherMigrationBackend by FakeMigrationBackend(events, snapshot) {
            override fun checkpointAndClosePlaintext(database: File) {
                events += "checkpoint"
                error("模拟 checkpoint 失败")
            }
        }
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 })

        val result = PlaintextToSqlCipherMigrator(files, backend).migrate(passphrase)

        assertEquals(
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.SourcePreparationFailed),
            result
        )
        assertTrue("pre-image 尚未创建时主库仍是原件，不得用不存在的副本回滚", "rollback" !in events)
        passphrase.close()
    }

    private fun sampleSnapshot(): DatabaseMigrationSnapshot = DatabaseMigrationSnapshot(
        userVersion = 5,
        autoVacuum = 1,
        schema = listOf(
            DatabaseSchemaIdentity("table", "semesters", "semesters", "CREATE TABLE semesters(id INTEGER)"),
            DatabaseSchemaIdentity("table", "courses", "courses", "CREATE TABLE courses(id INTEGER)"),
            DatabaseSchemaIdentity("index", "index_courses_semester", "courses", "CREATE INDEX index_courses_semester")
        ),
        userTableRowCounts = linkedMapOf("courses" to 2L, "semesters" to 1L)
    )

    private class FakeMigrationFiles(
        private val events: MutableList<String>,
        private val recovery: DatabaseMigrationRecovery = DatabaseMigrationRecovery.NoWork
    ) : DatabaseMigrationFileOperations {
        override fun <T> withExclusiveLock(block: () -> T): T {
            events += "lock"
            return try {
                block()
            } finally {
                events += "unlock"
            }
        }

        override fun recoverIncompleteMigration(): DatabaseMigrationRecovery {
            events += "recover"
            return recovery
        }

        override fun beginAttempt(): DatabaseMigrationAttempt {
            events += "begin"
            return DatabaseMigrationAttempt(
                id = "00000000-0000-0000-0000-000000000001",
                mainDatabase = File("main.db"),
                plaintextPreimage = File("preimage.db"),
                encryptedTemp = File("target.db")
            )
        }

        override fun createPlaintextPreimage(attempt: DatabaseMigrationAttempt) {
            events += "preimage"
        }

        override fun recordStage(attempt: DatabaseMigrationAttempt, stage: DatabaseMigrationStage) {
            events += "stage:$stage"
        }

        override fun swapEncryptedIntoMain(attempt: DatabaseMigrationAttempt) {
            events += "swap"
        }

        override fun rollbackToPlaintextPreimage(attempt: DatabaseMigrationAttempt): Boolean {
            events += "rollback"
            return true
        }
    }

    private class FakeMigrationBackend(
        private val events: MutableList<String>,
        private val source: DatabaseMigrationSnapshot,
        private val exported: DatabaseMigrationSnapshot = source,
        private val reopened: DatabaseMigrationSnapshot = exported,
        private val reopenFailure: Boolean = false
    ) : PlaintextToSqlCipherMigrationBackend {
        override fun checkpointAndClosePlaintext(database: File) {
            events += "checkpoint"
        }

        override fun inspectPlaintext(database: File): DatabaseMigrationVerification {
            events += "inspect-plaintext"
            return DatabaseMigrationVerification(source, integrityOk = true, cipherIntegrityOk = null)
        }

        override fun exportPlaintextToEncrypted(
            plaintextDatabase: File,
            encryptedDatabase: File,
            passphrase: SqlCipherPassphrase,
            sourceSnapshot: DatabaseMigrationSnapshot
        ): DatabaseMigrationVerification {
            events += "export"
            return DatabaseMigrationVerification(exported, integrityOk = true, cipherIntegrityOk = true)
        }

        override fun inspectEncrypted(
            database: File,
            passphrase: SqlCipherPassphrase
        ): DatabaseMigrationVerification {
            events += "inspect-encrypted"
            if (reopenFailure) error("模拟换入后重开失败")
            return DatabaseMigrationVerification(reopened, integrityOk = true, cipherIntegrityOk = true)
        }
    }
}
