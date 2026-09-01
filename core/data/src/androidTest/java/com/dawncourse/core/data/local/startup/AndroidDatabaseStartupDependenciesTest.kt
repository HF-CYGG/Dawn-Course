package com.dawncourse.core.data.local.startup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android Keystore 与 AtomicFile 适配器的最小设备契约测试。 */
@RunWith(AndroidJUnit4::class)
class AndroidDatabaseStartupDependenciesTest {
    private lateinit var testDirectory: File
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDirectory = File(context.noBackupFilesDir, "database-startup-test-${UUID.randomUUID()}")
        keyAlias = "com.dawncourse.test.database.${UUID.randomUUID()}"
        testDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        }
        testDirectory.deleteRecursively()
    }

    @Test
    fun keystoreKeyCanBeCreatedAndReadWithoutExportingEncodedMaterial() {
        val provider = AndroidKeystoreAesGcmKeyProvider()

        val created = provider.getOrCreate(keyAlias)
        val existing = provider.getExisting(keyAlias)

        assertTrue(created is KeyEncryptionKeyResult.Available)
        assertTrue(existing is KeyEncryptionKeyResult.Available)
    }

    @Test
    fun atomicStoreWritesAndReadsEnvelopeBytes() {
        val store = AndroidAtomicByteStore(File(testDirectory, "envelope.bin"))
        val expected = byteArrayOf(1, 2, 3, 4)

        store.writeAtomically(expected)
        val actual = store.readOrNull()

        assertTrue(actual != null && actual.contentEquals(expected))
    }

    @Test
    fun keystoreEnvelopeRoundTripKeepsPassphraseInsideManagedContainer() {
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = AndroidAtomicByteStore(File(testDirectory, "key-envelope.bin")),
            keyProvider = AndroidKeystoreAesGcmKeyProvider()
        )

        val created = store.createNew()
        val loaded = store.loadExisting()

        assertTrue(created is NewPassphraseResult.Available)
        assertTrue(loaded is ExistingPassphraseResult.Available)
        if (created is NewPassphraseResult.Available && loaded is ExistingPassphraseResult.Available) {
            val createdBytes = created.passphrase.useBytes { it.copyOf() }
            loaded.passphrase.useBytes { loadedBytes ->
                assertTrue(loadedBytes.contentEquals(createdBytes))
            }
            createdBytes.fill(0)
            created.passphrase.close()
            loaded.passphrase.close()
        }
    }

    @Test
    fun sameProcessEnvelopeStoreContendersWaitForTheSharedFileLock() {
        val envelopeFile = File(testDirectory, "concurrent-envelope.bin")
        val firstStore = AndroidAtomicByteStore(envelopeFile)
        val secondStore = AndroidAtomicByteStore(envelopeFile)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstThread = Thread {
            firstStore.withExclusiveLock {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        val secondThread = Thread {
            firstEntered.await(5, TimeUnit.SECONDS)
            secondStore.withExclusiveLock { secondEntered.countDown() }
        }

        firstThread.start()
        secondThread.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        assertTrue(!secondEntered.await(200, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
    }

    private companion object {
        /** Android Keystore Provider 名称。 */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
