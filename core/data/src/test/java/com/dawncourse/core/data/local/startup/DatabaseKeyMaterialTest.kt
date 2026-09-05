package com.dawncourse.core.data.local.startup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseKeyMaterialTest {
    @Test
    fun legacyPassphraseSuppliesOriginalBytes() {
        val input = ByteArray(32) { it.toByte() }
        val material = DatabaseKeyMaterial.LegacyPassphrase.fromBytes(input)

        material.useSqlCipherBytes { supplied ->
            assertTrue(supplied.contentEquals(input))
        }

        material.close()
    }

    @Test
    fun rawKeySuppliesSqlCipherRawLiteralInsteadOfBareKeyBytes() {
        val input = ByteArray(32) { it.toByte() }
        val material = DatabaseKeyMaterial.RawKeyLiteral.fromBytes(input)

        material.useSqlCipherBytes { supplied ->
            assertEquals(
                "x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'",
                supplied.toString(Charsets.US_ASCII)
            )
            assertEquals(67, supplied.size)
        }

        material.close()
    }

    @Test
    fun newlyProvisionedEnvelopeUsesV2RawKeyMode() {
        val bytes = MemoryAtomicByteStore()
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = bytes,
            keyProvider = FixedKeyProvider,
            randomByteSource = SecureRandomByteSource { size -> ByteArray(size) { it.toByte() } }
        )

        val created = store.createNew() as NewPassphraseResult.Available

        assertTrue(created.keyMaterial is DatabaseKeyMaterial.RawKeyLiteral)
        val serialized = requireNotNull(bytes.bytes)
        assertEquals(2, ByteBuffer.wrap(serialized, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        created.keyMaterial.close()
    }

    @Test
    fun v1EnvelopeLoadsAsLegacyPassphrase() {
        val legacy = ByteArray(32) { (it + 1).toByte() }
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = MemoryAtomicByteStore(encodeV1Envelope(legacy)),
            keyProvider = FixedKeyProvider,
            randomByteSource = SecureRandomByteSource { ByteArray(it) }
        )

        val loaded = store.loadExisting() as ExistingPassphraseResult.Available

        assertTrue(loaded.keyMaterial is DatabaseKeyMaterial.LegacyPassphrase)
        loaded.keyMaterial.useSqlCipherBytes { assertTrue(it.contentEquals(legacy)) }
        loaded.keyMaterial.close()
    }

    @Test
    fun rekeyStagingUsesExistingKekAndNeverProvisionsAlias() {
        val provider = TrackingKeyProvider(existingAvailable = true)
        val bytes = MemoryAtomicByteStore()
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = bytes,
            keyProvider = provider,
            randomByteSource = SecureRandomByteSource { size -> ByteArray(size) { 9 } },
        )

        val created = store.createNewWithExistingKey() as NewPassphraseResult.Available

        assertEquals(1, provider.getExistingCalls)
        assertEquals(0, provider.getOrCreateCalls)
        assertTrue(created.keyMaterial is DatabaseKeyMaterial.RawKeyLiteral)
        created.keyMaterial.close()
    }

    @Test
    fun rekeyStagingAbortsWithoutWritingWhenExistingKekIsUnavailable() {
        val provider = TrackingKeyProvider(existingAvailable = false)
        val bytes = MemoryAtomicByteStore()
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = bytes,
            keyProvider = provider,
            randomByteSource = SecureRandomByteSource { size -> ByteArray(size) { 9 } },
        )

        val created = store.createNewWithExistingKey()

        assertTrue(created is NewPassphraseResult.Failed)
        assertEquals(1, provider.getExistingCalls)
        assertEquals(0, provider.getOrCreateCalls)
        assertTrue(bytes.bytes == null)
    }

    private fun encodeV1Envelope(plaintext: ByteArray): ByteArray {
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

    private object FixedKeyProvider : KeyEncryptionKeyProvider {
        val key = SecretKeySpec(ByteArray(32) { 4 }, "AES")

        override fun getExisting(alias: String): KeyEncryptionKeyResult =
            KeyEncryptionKeyResult.Available(key)

        override fun getOrCreate(alias: String): KeyEncryptionKeyResult =
            KeyEncryptionKeyResult.Available(key)
    }

    private class TrackingKeyProvider(
        private val existingAvailable: Boolean,
    ) : KeyEncryptionKeyProvider {
        var getExistingCalls: Int = 0
        var getOrCreateCalls: Int = 0

        override fun getExisting(alias: String): KeyEncryptionKeyResult {
            getExistingCalls += 1
            return if (existingAvailable) {
                KeyEncryptionKeyResult.Available(FixedKeyProvider.key)
            } else {
                KeyEncryptionKeyResult.MissingOrInvalid
            }
        }

        override fun getOrCreate(alias: String): KeyEncryptionKeyResult {
            getOrCreateCalls += 1
            return KeyEncryptionKeyResult.Available(FixedKeyProvider.key)
        }
    }

    private class MemoryAtomicByteStore(initial: ByteArray? = null) : AtomicByteStore {
        var bytes: ByteArray? = initial?.copyOf()

        override fun <T> withExclusiveLock(block: () -> T): T = block()

        override fun readOrNull(): ByteArray? = bytes?.copyOf()

        override fun writeAtomically(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }
    }
}
