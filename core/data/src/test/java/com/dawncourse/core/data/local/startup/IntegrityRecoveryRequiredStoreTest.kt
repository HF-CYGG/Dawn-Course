package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 专用完整性恢复 marker 不复用备份原因，且损坏时保持安全侧。 */
class IntegrityRecoveryRequiredStoreTest {
    @Test
    fun markerRoundTripContainsOnlyFixedVersionedResponsibility() {
        val persistence = MemoryIntegrityStatePersistence()
        val store = IntegrityRecoveryRequiredStore(persistence)

        assertFalse(store.requiresRecovery())
        store.markRequiredAndConfirm()
        assertTrue(store.requiresRecovery())
        val marker = persistence.bytes?.toString(Charsets.UTF_8).orEmpty()
        assertFalse(marker.contains("/"))
        assertFalse(marker.contains("\\"))
        assertFalse(marker.contains("key", ignoreCase = true))

        store.clearRequiredAndConfirm()
        assertFalse(store.requiresRecovery())
    }

    @Test
    fun damagedMarkerStillRequiresRecoveryAndCanBeReplacedByRetry() {
        val persistence = MemoryIntegrityStatePersistence("damaged".toByteArray())
        val store = IntegrityRecoveryRequiredStore(persistence)

        assertTrue(store.requiresRecovery())
        store.markRequiredAndConfirm()
        assertTrue(store.requiresRecovery())
    }

    @Test
    fun markerWriteFailureIsObservableAndPreservesPreviousBytes() {
        val persistence = MemoryIntegrityStatePersistence("previous".toByteArray()).apply {
            failWrites = true
        }
        val store = IntegrityRecoveryRequiredStore(persistence)

        assertThrows(IllegalStateException::class.java) { store.markRequiredAndConfirm() }

        assertTrue("previous".toByteArray().contentEquals(persistence.bytes))
    }

    private class MemoryIntegrityStatePersistence(
        initialBytes: ByteArray? = null,
    ) : IntegrityVerificationStatePersistence {
        var bytes: ByteArray? = initialBytes?.copyOf()
        var failWrites: Boolean = false

        override fun <T> withExclusiveLock(block: () -> T): T = block()

        override fun readOrNull(): ByteArray? = bytes?.copyOf()

        override fun writeAtomically(bytes: ByteArray) {
            if (failWrites) error("模拟 marker 写入失败")
            this.bytes = bytes.copyOf()
        }

        override fun deleteAtomically() {
            bytes = null
        }
    }
}
