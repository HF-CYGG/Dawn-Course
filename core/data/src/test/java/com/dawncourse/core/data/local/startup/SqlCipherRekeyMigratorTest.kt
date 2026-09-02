package com.dawncourse.core.data.local.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlCipherRekeyMigratorTest {
    @Test
    fun rekeyStopsBeforeEnvelopeCommitUntilRoomHasVerifiedRawDatabase() {
        val events = mutableListOf<String>()
        val files = FakeFiles(events)
        val backend = FakeBackend(events)
        val migrator = SqlCipherRekeyMigrator(files, backend)
        val legacy = DatabaseKeyMaterial.LegacyPassphrase.fromBytes(ByteArray(32) { 1 })

        val result = migrator.rekey(legacy)

        assertTrue(result is SqlCipherRekeyResult.Success)
        result as SqlCipherRekeyResult.Success
        assertEquals(DatabaseKeyMaterial.Mode.RAW_KEY_LITERAL, result.rawKeyMaterial.mode)
        assertEquals(
            listOf(
                "lock", "recover", "begin", "checkpoint", "preimages",
                "stage:LEGACY_PREIMAGE_READY", "stage-envelope",
                "stage:RAW_ENVELOPE_STAGED", "rekey-copy",
                "stage:RAW_TEMP_VERIFIED", "stage:SWAP_PENDING", "swap",
                "stage:RAW_SWAPPED_NOT_ROOM_VERIFIED", "unlock"
            ),
            events
        )
        assertTrue("commit-envelope" !in events)

        assertTrue(migrator.confirmRoomVerifiedAndCommit(result.attempt))
        assertEquals(
            listOf(
                "lock", "stage:ROOM_REOPEN_VERIFIED", "stage:ENVELOPE_COMMIT_PENDING",
                "commit-envelope", "stage:COMPLETE", "unlock"
            ),
            events.takeLast(6)
        )

        result.rawKeyMaterial.close()
        legacy.close()
    }

    @Test
    fun failedRawCopyRollsBackBothDatabaseAndEnvelope() {
        val events = mutableListOf<String>()
        val files = FakeFiles(events)
        val backend = FakeBackend(events, failRekey = true)
        val migrator = SqlCipherRekeyMigrator(files, backend)
        val legacy = DatabaseKeyMaterial.LegacyPassphrase.fromBytes(ByteArray(32) { 1 })

        val result = migrator.rekey(legacy)

        assertTrue(result is SqlCipherRekeyResult.RecoveryRequired)
        assertTrue("rollback" in events)
        assertTrue("commit-envelope" !in events)
        legacy.close()
    }

    private class FakeFiles(
        private val events: MutableList<String>
    ) : SqlCipherRekeyFileOperations {
        private val attempt = SqlCipherRekeyAttempt(
            id = "00000000-0000-0000-0000-000000000001",
            mainDatabase = File("main.db"),
            legacyDatabasePreimage = File("legacy.db"),
            rawDatabaseTemp = File("raw.db"),
            legacyEnvelopePreimage = File("legacy-envelope"),
            stagedRawEnvelope = File("raw-envelope")
        )

        override fun <T> withExclusiveLock(block: () -> T): T {
            events += "lock"
            return try {
                block()
            } finally {
                events += "unlock"
            }
        }

        override fun recoverIncompleteRekey(): SqlCipherRekeyRecovery {
            events += "recover"
            return SqlCipherRekeyRecovery.NoWork
        }

        override fun beginAttempt(): SqlCipherRekeyAttempt {
            events += "begin"
            return attempt
        }

        override fun createLegacyPreimages(attempt: SqlCipherRekeyAttempt) {
            events += "preimages"
        }

        override fun stageRawEnvelope(attempt: SqlCipherRekeyAttempt): DatabaseKeyMaterial.RawKeyLiteral {
            events += "stage-envelope"
            return DatabaseKeyMaterial.RawKeyLiteral.fromBytes(ByteArray(32) { 2 })
        }

        override fun recordStage(attempt: SqlCipherRekeyAttempt, stage: SqlCipherRekeyStage) {
            events += "stage:$stage"
        }

        override fun swapRawIntoMain(attempt: SqlCipherRekeyAttempt) {
            events += "swap"
        }

        override fun commitRawEnvelope(attempt: SqlCipherRekeyAttempt) {
            events += "commit-envelope"
        }

        override fun rollbackToLegacy(attempt: SqlCipherRekeyAttempt): Boolean {
            events += "rollback"
            return true
        }

        override fun retireAfterExplicitRecovery(): Boolean = true
    }

    private class FakeBackend(
        private val events: MutableList<String>,
        private val failRekey: Boolean = false
    ) : SqlCipherRekeyBackend {
        override fun checkpointAndCloseLegacy(database: File, legacy: DatabaseKeyMaterial.LegacyPassphrase) {
            events += "checkpoint"
        }

        override fun rekeyCopyAndVerify(
            legacyDatabase: File,
            rawDatabase: File,
            legacy: DatabaseKeyMaterial.LegacyPassphrase,
            raw: DatabaseKeyMaterial.RawKeyLiteral
        ) {
            events += "rekey-copy"
            if (failRekey) error("模拟 raw rekey 失败")
        }
    }
}
