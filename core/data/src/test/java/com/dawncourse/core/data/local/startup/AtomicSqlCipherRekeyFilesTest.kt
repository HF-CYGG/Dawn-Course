package com.dawncourse.core.data.local.startup

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicSqlCipherRekeyFilesTest {
    @Test
    fun interruptionBeforeEnvelopeCommitAlwaysRestoresV1AndLegacyDatabase() {
        val stages = listOf(
            SqlCipherRekeyStage.INITIALIZED,
            SqlCipherRekeyStage.LEGACY_PREIMAGE_READY,
            SqlCipherRekeyStage.RAW_ENVELOPE_STAGED,
            SqlCipherRekeyStage.RAW_TEMP_VERIFIED,
            SqlCipherRekeyStage.SWAP_PENDING,
            SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED,
            SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED,
            SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING,
        )

        stages.forEach { interruptedAt ->
            val fixture = Fixture()
            fixture.advanceTo(interruptedAt, commitEnvelope = false)

            val recovered = fixture.newFiles().withExclusiveLock {
                fixture.newFiles().recoverIncompleteRekey()
            }

            assertEquals("stage=$interruptedAt", SqlCipherRekeyRecovery.RecoveredToLegacy, recovered)
            assertEquals("stage=$interruptedAt", "legacy-db", fixture.main.readText())
            assertEquals("stage=$interruptedAt", 1, envelopeVersion(fixture.envelope))
        }
    }

    @Test
    fun interruptionAfterV2EnvelopeCommitFinalizesRawPair() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING, commitEnvelope = true)

        val files = fixture.newFiles()
        val recovered = files.withExclusiveLock { files.recoverIncompleteRekey() }

        assertEquals(SqlCipherRekeyRecovery.RecoveredToRaw, recovered)
        assertEquals("raw-db", fixture.main.readText())
        assertEquals(2, envelopeVersion(fixture.envelope))
    }

    @Test
    fun rollbackPendingRepairsCrashBetweenLegacyDatabaseAndEnvelopeRestore() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING, commitEnvelope = true)
        val files = fixture.newFiles()
        val attempt = fixture.attempt()

        files.withExclusiveLock {
            files.recordStage(attempt, SqlCipherRekeyStage.ROLLBACK_PENDING)
        }
        // 模拟回滚先换回 legacy DB、但尚未来得及换回 v1 envelope 时进程死亡。
        fixture.main.writeText("legacy-db")
        assertEquals(2, envelopeVersion(fixture.envelope))

        val recovered = fixture.newFiles().withExclusiveLock {
            fixture.newFiles().recoverIncompleteRekey()
        }

        assertEquals(SqlCipherRekeyRecovery.RecoveredToLegacy, recovered)
        assertEquals("legacy-db", fixture.main.readText())
        assertEquals(1, envelopeVersion(fixture.envelope))
        assertTrue(fixture.journal.readText().contains("stage=ROLLED_BACK"))
    }

    @Test
    fun explicitRecoveryRetiresOldRekeyWithoutTouchingReplacementPair() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        fixture.main.writeText("replacement-db")
        fixture.envelope.writeBytes(encodeV2Envelope(ByteArray(32) { 8 }))

        val files = fixture.newFiles()
        assertTrue(files.withExclusiveLock { files.retireAfterExplicitRecovery() })

        assertEquals("replacement-db", fixture.main.readText())
        assertEquals(2, envelopeVersion(fixture.envelope))
        assertFalse(fixture.journal.exists())
        assertEquals(
            SqlCipherRekeyRecovery.NoWork,
            fixture.newFiles().withExclusiveLock { fixture.newFiles().recoverIncompleteRekey() }
        )
    }

    @Test
    fun explicitRecoveryRetiresCorruptJournalWithoutTouchingReplacementPair() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        fixture.journal.writeText("corrupt")
        fixture.main.writeText("replacement-db")
        fixture.envelope.writeBytes(encodeV2Envelope(ByteArray(32) { 8 }))

        val files = fixture.newFiles()
        assertTrue(files.withExclusiveLock { files.retireAfterExplicitRecovery() })

        assertEquals("replacement-db", fixture.main.readText())
        assertEquals(2, envelopeVersion(fixture.envelope))
        assertFalse(fixture.journal.exists())
        assertEquals(
            SqlCipherRekeyRecovery.NoWork,
            fixture.newFiles().withExclusiveLock { fixture.newFiles().recoverIncompleteRekey() },
        )
    }

    @Test
    fun explicitRecoveryRetiresOrphansWithoutJournalWithoutTouchingReplacementPair() {
        val fixture = Fixture()
        File(fixture.main.parentFile, "${fixture.main.name}.raw-temp.$ATTEMPT_ID").writeText("orphan")
        File(fixture.envelope.parentFile, "${fixture.envelope.name}.raw-staged.$ATTEMPT_ID").writeText("orphan")
        fixture.main.writeText("replacement-db")
        fixture.envelope.writeBytes(encodeV2Envelope(ByteArray(32) { 8 }))

        val files = fixture.newFiles()
        assertTrue(files.withExclusiveLock { files.retireAfterExplicitRecovery() })

        assertEquals("replacement-db", fixture.main.readText())
        assertEquals(2, envelopeVersion(fixture.envelope))
        assertEquals(
            SqlCipherRekeyRecovery.NoWork,
            fixture.newFiles().withExclusiveLock { fixture.newFiles().recoverIncompleteRekey() },
        )
    }

    @Test
    fun initialRollbackNeverPersistsPendingPhysicalRollbackWithoutPreimages() {
        val fixture = Fixture()
        val files = fixture.newFiles()
        val attempt = files.withExclusiveLock { files.beginAttempt() }

        assertTrue(files.withExclusiveLock { files.rollbackToLegacy(attempt) })

        assertTrue(fixture.journal.readText().contains("stage=ROLLED_BACK"))
        assertEquals("legacy-db", fixture.main.readText())
        assertEquals(1, envelopeVersion(fixture.envelope))
    }

    @Test
    fun initialStageTreatsEveryPartialPreimageCopyBoundaryAsUncommittedWork() {
        listOf(1, 2, 3).forEach { completedCopies ->
            val fixture = Fixture()
            val files = fixture.newFiles()
            val attempt = files.withExclusiveLock { files.beginAttempt() }
            if (completedCopies >= 1) attempt.legacyDatabasePreimage.writeText("legacy-db")
            if (completedCopies >= 2) {
                attempt.legacyEnvelopePreimage.writeBytes(fixture.envelope.readBytes())
            }
            if (completedCopies >= 3) attempt.rawDatabaseTemp.writeText("legacy-db")

            val recovered = fixture.newFiles().withExclusiveLock {
                fixture.newFiles().recoverIncompleteRekey()
            }

            assertEquals("copies=$completedCopies", SqlCipherRekeyRecovery.RecoveredToLegacy, recovered)
            assertEquals("copies=$completedCopies", "legacy-db", fixture.main.readText())
            assertEquals("copies=$completedCopies", 1, envelopeVersion(fixture.envelope))
            assertTrue(fixture.journal.readText().contains("stage=ROLLED_BACK"))
        }
    }

    @Test
    fun explicitRecoveryPreservesUnknownNeighborWithValidPrefixAndAttemptId() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        val unknownDatabaseNeighbor = File(
            fixture.main.parentFile,
            "${fixture.main.name}.raw-temp.$ATTEMPT_ID-user-file",
        ).apply { writeText("keep") }
        val unknownEnvelopeNeighbor = File(
            fixture.envelope.parentFile,
            "${fixture.envelope.name}.raw-staged.$ATTEMPT_ID-user-file",
        ).apply { writeText("keep") }
        fixture.main.writeText("replacement-db")
        fixture.envelope.writeBytes(encodeV2Envelope(ByteArray(32) { 8 }))

        val files = fixture.newFiles()
        assertTrue(files.withExclusiveLock { files.retireAfterExplicitRecovery() })

        assertEquals("keep", unknownDatabaseNeighbor.readText())
        assertEquals("keep", unknownEnvelopeNeighbor.readText())
        assertEquals(
            SqlCipherRekeyRecovery.NoWork,
            fixture.newFiles().withExclusiveLock { fixture.newFiles().recoverIncompleteRekey() },
        )
    }

    @Test
    fun normalTerminalCleanupPreservesUnknownNeighbors() {
        listOf(false, true).forEach { completeRaw ->
            val fixture = Fixture()
            fixture.advanceTo(
                SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING,
                commitEnvelope = completeRaw,
            )
            val files = fixture.newFiles()
            files.withExclusiveLock { files.recoverIncompleteRekey() }
            val unknownDatabaseNeighbor = File(
                fixture.main.parentFile,
                "${fixture.main.name}.raw-temp.$ATTEMPT_ID-user-file",
            ).apply { writeText("keep") }
            val unknownEnvelopeNeighbor = File(
                fixture.envelope.parentFile,
                "${fixture.envelope.name}.raw-staged.$ATTEMPT_ID-user-file",
            ).apply { writeText("keep") }

            if (completeRaw) {
                assertTrue(files.withExclusiveLock { files.cleanupAfterVerifiedColdOpen() })
            } else {
                files.withExclusiveLock { files.beginAttempt() }
            }

            assertEquals("completeRaw=$completeRaw", "keep", unknownDatabaseNeighbor.readText())
            assertEquals("completeRaw=$completeRaw", "keep", unknownEnvelopeNeighbor.readText())
        }
    }

    @Test
    fun corruptJournalAndMissingMainFailClosedWithoutCreatingDatabase() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        fixture.main.delete()
        fixture.journal.writeText("broken")

        val files = fixture.newFiles()
        val recovered = files.withExclusiveLock { files.recoverIncompleteRekey() }

        assertEquals(SqlCipherRekeyRecovery.Failed, recovered)
        assertFalse(fixture.main.exists())
    }

    @Test
    fun orphanRekeyArtifactWithoutJournalFailsClosed() {
        val fixture = Fixture()
        File(fixture.main.parentFile, "${fixture.main.name}.raw-temp.$ATTEMPT_ID").writeText("raw")

        val files = fixture.newFiles()
        val recovered = files.withExclusiveLock { files.recoverIncompleteRekey() }

        assertEquals(SqlCipherRekeyRecovery.Failed, recovered)
        assertEquals("legacy-db", fixture.main.readText())
        assertTrue(fixture.envelope.exists())
    }

    @Test
    fun nextAttemptCleansTerminalRollbackArtifactsBeforeReusingJournal() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        val files = fixture.newFiles()
        assertEquals(
            SqlCipherRekeyRecovery.RecoveredToLegacy,
            files.withExclusiveLock { files.recoverIncompleteRekey() }
        )

        val next = files.withExclusiveLock { files.beginAttempt() }

        assertEquals(ATTEMPT_ID, next.id)
        assertFalse(next.legacyDatabasePreimage.exists())
        assertFalse(next.stagedRawEnvelope.exists())
        assertTrue(fixture.journal.readText().contains("stage=INITIALIZED"))
    }

    @Test
    fun rollbackRetryDiscardsInterruptedRestoreWorkingCopy() {
        val fixture = Fixture()
        fixture.advanceTo(SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED, commitEnvelope = false)
        val restoreCopying = File(
            fixture.main.parentFile,
            "${fixture.main.name}.legacy-restore.$ATTEMPT_ID.copying",
        ).apply { writeText("partial") }
        fixture.newFiles().withExclusiveLock {
            fixture.newFiles().recordStage(fixture.attempt(), SqlCipherRekeyStage.ROLLBACK_PENDING)
        }

        val recovered = fixture.newFiles().withExclusiveLock {
            fixture.newFiles().recoverIncompleteRekey()
        }

        assertEquals(SqlCipherRekeyRecovery.RecoveredToLegacy, recovered)
        assertEquals("legacy-db", fixture.main.readText())
        assertEquals(1, envelopeVersion(fixture.envelope))
        assertFalse(restoreCopying.exists())
    }

    @Test
    fun isolatedInterruptedJournalTempBeforeAttemptIsSafelyDiscarded() {
        val fixture = Fixture()
        val journalTemp = File(fixture.journal.path + ".tmp").apply { writeText("partial") }

        val recovered = fixture.newFiles().withExclusiveLock {
            fixture.newFiles().recoverIncompleteRekey()
        }

        assertEquals(SqlCipherRekeyRecovery.NoWork, recovered)
        assertFalse(journalTemp.exists())
        assertEquals("legacy-db", fixture.main.readText())
        assertEquals(1, envelopeVersion(fixture.envelope))
    }

    private class Fixture {
        private val root = Files.createTempDirectory("dawn-rekey-test").toFile()
        private val databaseDirectory = File(root, "databases").apply { mkdirs() }
        private val envelopeDirectory = File(root, "no-backup/database").apply { mkdirs() }
        val main = File(databaseDirectory, "dawn_course.db").apply { writeText("legacy-db") }
        val envelope = File(envelopeDirectory, "dawn_course_key_envelope.bin").apply {
            writeBytes(encodeV1Envelope(ByteArray(32) { 1 }))
        }
        val journal = File(databaseDirectory, "${main.name}.sqlcipher-rekey.journal")

        fun newFiles(): AtomicSqlCipherRekeyFiles = AtomicSqlCipherRekeyFiles(
            databaseFile = main,
            activeEnvelopeFile = envelope,
            keyProvider = FixedKeyProvider,
            randomByteSource = SecureRandomByteSource { size -> ByteArray(size) { 2 } },
            attemptIdSource = { ATTEMPT_ID },
            atomicByteStoreFactory = ::TestAtomicByteStore,
        )

        fun attempt(): SqlCipherRekeyAttempt = SqlCipherRekeyAttempt(
            id = ATTEMPT_ID,
            mainDatabase = main,
            legacyDatabasePreimage = File(databaseDirectory, "${main.name}.legacy-preimage.$ATTEMPT_ID"),
            rawDatabaseTemp = File(databaseDirectory, "${main.name}.raw-temp.$ATTEMPT_ID"),
            legacyEnvelopePreimage = File(envelopeDirectory, "${envelope.name}.legacy-preimage.$ATTEMPT_ID"),
            stagedRawEnvelope = File(envelopeDirectory, "${envelope.name}.raw-staged.$ATTEMPT_ID"),
        )

        fun advanceTo(target: SqlCipherRekeyStage, commitEnvelope: Boolean) {
            val files = newFiles()
            files.withExclusiveLock {
                val attempt = files.beginAttempt()
                if (target == SqlCipherRekeyStage.INITIALIZED) return@withExclusiveLock
                files.createLegacyPreimages(attempt)
                files.recordStage(attempt, SqlCipherRekeyStage.LEGACY_PREIMAGE_READY)
                if (target == SqlCipherRekeyStage.LEGACY_PREIMAGE_READY) return@withExclusiveLock
                files.stageRawEnvelope(attempt).close()
                files.recordStage(attempt, SqlCipherRekeyStage.RAW_ENVELOPE_STAGED)
                if (target == SqlCipherRekeyStage.RAW_ENVELOPE_STAGED) return@withExclusiveLock
                attempt.rawDatabaseTemp.writeText("raw-db")
                files.recordStage(attempt, SqlCipherRekeyStage.RAW_TEMP_VERIFIED)
                if (target == SqlCipherRekeyStage.RAW_TEMP_VERIFIED) return@withExclusiveLock
                files.recordStage(attempt, SqlCipherRekeyStage.SWAP_PENDING)
                if (target == SqlCipherRekeyStage.SWAP_PENDING) return@withExclusiveLock
                files.swapRawIntoMain(attempt)
                files.recordStage(attempt, SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED)
                if (target == SqlCipherRekeyStage.RAW_SWAPPED_NOT_ROOM_VERIFIED) return@withExclusiveLock
                files.recordStage(attempt, SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED)
                if (target == SqlCipherRekeyStage.ROOM_REOPEN_VERIFIED) return@withExclusiveLock
                files.recordStage(attempt, SqlCipherRekeyStage.ENVELOPE_COMMIT_PENDING)
                if (commitEnvelope) files.commitRawEnvelope(attempt)
            }
        }
    }

    private class TestAtomicByteStore(private val file: File) : AtomicByteStore {
        override fun <T> withExclusiveLock(block: () -> T): T = block()

        override fun readOrNull(): ByteArray? = file.takeIf(File::isFile)?.readBytes()

        override fun writeAtomically(bytes: ByteArray) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private object FixedKeyProvider : KeyEncryptionKeyProvider {
        val key = SecretKeySpec(ByteArray(32) { 4 }, "AES")

        override fun getExisting(alias: String): KeyEncryptionKeyResult =
            KeyEncryptionKeyResult.Available(key)

        override fun getOrCreate(alias: String): KeyEncryptionKeyResult =
            KeyEncryptionKeyResult.Available(key)
    }

    private companion object {
        const val ATTEMPT_ID = "00000000-0000-0000-0000-000000000001"

        fun encodeV1Envelope(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, FixedKeyProvider.key)
            cipher.updateAAD("dawn-course/database-key-envelope/v1".toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(20 + cipher.iv.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
                .putInt(1)
                .putInt(1)
                .putInt(cipher.iv.size)
                .putInt(ciphertext.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
        }

        fun encodeV2Envelope(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, FixedKeyProvider.key)
            cipher.updateAAD("dawn-course/database-key-envelope/v2".toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(20 + cipher.iv.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()))
                .putInt(2)
                .putInt(1)
                .putInt(cipher.iv.size)
                .putInt(ciphertext.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
        }

        fun envelopeVersion(file: File): Int = ByteBuffer.wrap(file.readBytes(), 4, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
    }
}
