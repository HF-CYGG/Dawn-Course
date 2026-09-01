package com.dawncourse.core.data.local.startup

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** journal、同目录原子换入和崩溃恢复的真实文件系统测试。 */
class AtomicDatabaseMigrationFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun crashAfterSwapRestoresRetainedPlaintextPreimage() {
        val main = temporaryFolder.newFile("dawn_course.db").apply { writeText("plaintext-source") }
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        lateinit var attempt: DatabaseMigrationAttempt
        files.withExclusiveLock {
            attempt = files.beginAttempt()
            files.createPlaintextPreimage(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
            attempt.encryptedTemp.writeText("encrypted-target")
            files.recordStage(attempt, DatabaseMigrationStage.ENCRYPTED_TEMP_READY)
            files.recordStage(attempt, DatabaseMigrationStage.SWAP_PENDING)
            files.swapEncryptedIntoMain(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.SWAPPED_NOT_VERIFIED)
        }
        assertEquals("encrypted-target", main.readText())

        val recovery = files.withExclusiveLock { files.recoverIncompleteMigration() }

        assertEquals(DatabaseMigrationRecovery.Recovered, recovery)
        assertEquals("plaintext-source", main.readText())
        assertEquals("plaintext-source", attempt.plaintextPreimage.readText())
        assertTrue("失败加密目标应保留用于审计", File(main.parentFile, "${main.name}.failed-encrypted.$ATTEMPT_ONE").exists())
    }

    @Test
    fun crashBeforeSwapKeepsTheOriginalMainInsteadOfReplacingItFromPreimage() {
        val main = temporaryFolder.newFile("before-swap.db").apply { writeText("original-main") }
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        lateinit var attempt: DatabaseMigrationAttempt
        files.withExclusiveLock {
            attempt = files.beginAttempt()
            files.createPlaintextPreimage(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
        }
        // 模拟 pre-image 在落盘后被外部介质损坏；换入前主库仍是唯一可信原件。
        attempt.plaintextPreimage.writeText("damaged-preimage")

        val recovery = files.withExclusiveLock { files.recoverIncompleteMigration() }

        assertEquals(DatabaseMigrationRecovery.Recovered, recovery)
        assertEquals("original-main", main.readText())
        assertFalse(File(main.parentFile, "${main.name}.failed-encrypted.$ATTEMPT_ONE").exists())
    }

    @Test
    fun invalidJournalFailsClosedWithoutMutatingMainDatabase() {
        val main = temporaryFolder.newFile("invalid.db").apply { writeText("original") }
        File(main.parentFile, "${main.name}.sqlcipher-migration.journal").writeText("../../malicious")
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }

        val recovery = files.withExclusiveLock { files.recoverIncompleteMigration() }

        assertEquals(DatabaseMigrationRecovery.Failed, recovery)
        assertEquals("original", main.readText())
    }

    @Test
    fun swapArchivesAllLegacySidecarsInsteadOfLeavingThemBesideEncryptedMain() {
        val main = temporaryFolder.newFile("sidecars.db").apply { writeText("plaintext") }
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(main.path + suffix).writeText("legacy-$suffix")
        }
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        lateinit var attempt: DatabaseMigrationAttempt

        files.withExclusiveLock {
            attempt = files.beginAttempt()
            files.createPlaintextPreimage(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
            attempt.encryptedTemp.writeText("encrypted")
            files.recordStage(attempt, DatabaseMigrationStage.ENCRYPTED_TEMP_READY)
            files.recordStage(attempt, DatabaseMigrationStage.SWAP_PENDING)
            files.swapEncryptedIntoMain(attempt)
        }

        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(File(main.path + suffix).exists())
            assertTrue(File(attempt.plaintextPreimage.path + suffix + ".at-swap").exists())
        }
    }

    @Test
    fun completedOrRolledBackAttemptDoesNotReuseArtifactNames() {
        val main = temporaryFolder.newFile("repeat.db").apply { writeText("plaintext") }
        val ids = ArrayDeque(listOf(ATTEMPT_ONE, ATTEMPT_TWO))
        val files = AtomicDatabaseMigrationFiles(main) { ids.removeFirst() }
        lateinit var first: DatabaseMigrationAttempt
        lateinit var second: DatabaseMigrationAttempt

        files.withExclusiveLock {
            first = files.beginAttempt()
            files.createPlaintextPreimage(first)
            files.recordStage(first, DatabaseMigrationStage.ROLLED_BACK)
            second = files.beginAttempt()
        }

        assertNotEquals(first.plaintextPreimage, second.plaintextPreimage)
        assertTrue(first.plaintextPreimage.exists())
    }

    @Test
    fun sameProcessContendersAreSerializedBeforeTakingCrossProcessFileLock() {
        val main = temporaryFolder.newFile("lock.db")
        val first = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        val second = AtomicDatabaseMigrationFiles(main) { ATTEMPT_TWO }
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstThread = Thread {
            first.withExclusiveLock {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        val secondThread = Thread {
            firstEntered.await(5, TimeUnit.SECONDS)
            second.withExclusiveLock { secondEntered.countDown() }
        }

        firstThread.start()
        secondThread.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        assertFalse("第二线程不得越过同一数据库锁", secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun initializedCrashRemovesPlaintextCopyingResidueWhenOriginalMainStillExists() {
        val main = temporaryFolder.newFile("copying-crash.db").apply { writeText("original-main") }
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        lateinit var attempt: DatabaseMigrationAttempt
        files.withExclusiveLock {
            attempt = files.beginAttempt()
            File(attempt.plaintextPreimage.path + ".copying").writeText("plaintext-pii")
        }

        val recovery = files.withExclusiveLock { files.recoverIncompleteMigration() }

        assertEquals(DatabaseMigrationRecovery.Recovered, recovery)
        assertEquals("original-main", main.readText())
        assertFalse(File(attempt.plaintextPreimage.path + ".copying").exists())
    }

    @Test
    fun retainedPlaintextIsDeletedOnlyAfterLaterVerifiedColdOpen() {
        val main = temporaryFolder.newFile("retained.db").apply { writeText("encrypted-main") }
        val files = AtomicDatabaseMigrationFiles(main) { ATTEMPT_ONE }
        lateinit var attempt: DatabaseMigrationAttempt
        files.withExclusiveLock {
            attempt = files.beginAttempt()
            attempt.plaintextPreimage.writeText("plaintext-pii")
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
            files.recordStage(attempt, DatabaseMigrationStage.ENCRYPTED_TEMP_READY)
            files.recordStage(attempt, DatabaseMigrationStage.SWAP_PENDING)
            files.recordStage(attempt, DatabaseMigrationStage.SWAPPED_NOT_VERIFIED)
            files.recordStage(attempt, DatabaseMigrationStage.COMPLETE)
        }

        assertTrue(attempt.plaintextPreimage.exists())
        val cleaned = files.withExclusiveLock { files.cleanupAfterVerifiedColdOpen() }

        assertTrue(cleaned)
        assertFalse(attempt.plaintextPreimage.exists())
    }

    private companion object {
        const val ATTEMPT_ONE = "00000000-0000-0000-0000-000000000001"
        const val ATTEMPT_TWO = "00000000-0000-0000-0000-000000000002"
    }
}
