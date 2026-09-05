package com.dawncourse.core.data.local.startup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherRawKeyRekeyInstrumentedTest {
    @Test
    fun legacyRoomDatabaseRekeysToRawAndReopensWithCommittedV2Envelope() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "raw-rekey-instrumented.db"
        val databaseFile = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        val envelope = File(context.cacheDir, "raw-rekey-envelope.bin").also(::deleteAtomicFamily)
        envelope.writeBytes(encodeV1Envelope(ByteArray(32) { 1 }))
        val legacy = DatabaseKeyMaterial.LegacyPassphrase.fromBytes(ByteArray(32) { 1 })
        val roomFactory = SqlCipherRoomDatabaseFactory(context)
        val initial = roomFactory.open(databaseName, legacy)
        initial.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS rekey_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
        )
        initial.openHelper.writableDatabase.execSQL(
            "INSERT INTO rekey_probe(id, value) VALUES(1, 'preserved')"
        )
        initial.close()

        val files = AtomicSqlCipherRekeyFiles(
            databaseFile = databaseFile,
            activeEnvelopeFile = envelope,
            keyProvider = FixedKeyProvider,
            randomByteSource = SecureRandomByteSource { size -> ByteArray(size) { 2 } },
        )
        val migrator = SqlCipherRekeyMigrator(files, AndroidSqlCipherRekeyBackend())

        val rekey = migrator.rekey(legacy) as SqlCipherRekeyResult.Success
        legacy.close()
        val rawRoom = roomFactory.open(databaseName, rekey.rawKeyMaterial)
        roomFactory.verifyIntegrity(rawRoom)
        rawRoom.openHelper.readableDatabase.query("SELECT value FROM rekey_probe WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }
        assertTrue(migrator.confirmRoomVerifiedAndCommit(rekey.attempt))
        rawRoom.close()
        rekey.rawKeyMaterial.close()

        val loaded = DatabaseKeyEnvelopeStore(
            atomicByteStore = AndroidAtomicByteStore(envelope),
            keyProvider = FixedKeyProvider,
        ).loadExisting() as ExistingPassphraseResult.Available
        assertTrue(loaded.keyMaterial is DatabaseKeyMaterial.RawKeyLiteral)
        val reopened = roomFactory.open(databaseName, loaded.keyMaterial)
        reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rekey_probe").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        reopened.close()
        loaded.keyMaterial.close()

        context.deleteDatabase(databaseName)
        deleteAtomicFamily(envelope)
    }

    private fun deleteAtomicFamily(file: File) {
        listOf("", ".bak", ".new", ".lock", ".rekey.lock").forEach { suffix ->
            File(file.path + suffix).delete()
        }
        file.parentFile?.listFiles()
            ?.filter { it.name.startsWith(file.name + ".legacy-preimage.") || it.name.startsWith(file.name + ".raw-staged.") }
            ?.forEach(File::delete)
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
}
