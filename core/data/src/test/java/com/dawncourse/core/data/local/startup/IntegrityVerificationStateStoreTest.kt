package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** no-backup 原子状态协议的崩溃责任与损坏降级测试。 */
class IntegrityVerificationStateStoreTest {
    @Test
    fun firstStartupIsMarkedInProgressAndSuccessfulCompletionIsVisibleToNextStartup() {
        val persistence = MemoryIntegrityStatePersistence()
        val store = IntegrityVerificationStateStore(persistence)

        val first = store.beginStartup()
        val secondWithoutCompletion = store.beginStartup()
        store.completeSuccessfulVerification(123_456L)
        val third = store.beginStartup()

        assertFalse(first.previousDatabaseStartupIncomplete)
        assertEquals(null, first.lastSuccessfulVerificationEpochMillis)
        assertTrue(secondWithoutCompletion.previousDatabaseStartupIncomplete)
        assertFalse(third.previousDatabaseStartupIncomplete)
        assertEquals(123_456L, third.lastSuccessfulVerificationEpochMillis)
    }

    @Test
    fun corruptedOrUnreadableStateIsConservativelyTreatedAsIncomplete() {
        val corruptedPersistence = MemoryIntegrityStatePersistence("damaged".toByteArray())
        val corrupted = IntegrityVerificationStateStore(corruptedPersistence).beginStartup()
        val unreadablePersistence = MemoryIntegrityStatePersistence().apply { readFailuresRemaining = 1 }
        val unreadable = IntegrityVerificationStateStore(unreadablePersistence).beginStartup()

        assertTrue(corrupted.previousDatabaseStartupIncomplete)
        assertTrue(corrupted.persistentStateUnreadable)
        assertEquals(null, corrupted.lastSuccessfulVerificationEpochMillis)
        assertTrue(unreadable.previousDatabaseStartupIncomplete)
        assertTrue(unreadable.persistentStateUnreadable)
    }

    @Test
    fun failedAtomicBeginDoesNotReplaceThePreviouslyCompletedState() {
        val persistence = MemoryIntegrityStatePersistence()
        val store = IntegrityVerificationStateStore(persistence)
        store.beginStartup()
        store.completeSuccessfulVerification(987_654L)
        val completedBytes = persistence.bytes?.copyOf()
        persistence.failWrites = true

        assertThrows(IllegalStateException::class.java) { store.beginStartup() }

        assertTrue(completedBytes.contentEquals(persistence.bytes))
    }

    @Test
    fun successfulCompletionAtomicallyUpdatesTimestampAndClearsResponsibility() {
        val persistence = MemoryIntegrityStatePersistence()
        val store = IntegrityVerificationStateStore(persistence)
        store.beginStartup()

        store.completeSuccessfulVerification(444_555L)
        val next = store.beginStartup()

        assertFalse(next.previousDatabaseStartupIncomplete)
        assertEquals(444_555L, next.lastSuccessfulVerificationEpochMillis)
        assertEquals(3, persistence.successfulWrites)
    }

    private class MemoryIntegrityStatePersistence(
        initialBytes: ByteArray? = null,
    ) : IntegrityVerificationStatePersistence {
        var bytes: ByteArray? = initialBytes?.copyOf()
        var readFailuresRemaining: Int = 0
        var failWrites: Boolean = false
        var successfulWrites: Int = 0

        override fun <T> withExclusiveLock(block: () -> T): T = block()

        override fun readOrNull(): ByteArray? {
            if (readFailuresRemaining > 0) {
                readFailuresRemaining -= 1
                error("模拟读取失败")
            }
            return bytes?.copyOf()
        }

        override fun writeAtomically(bytes: ByteArray) {
            if (failWrites) error("模拟原子写入失败")
            this.bytes = bytes.copyOf()
            successfulWrites += 1
        }

        override fun deleteAtomically() {
            bytes = null
        }
    }
}
