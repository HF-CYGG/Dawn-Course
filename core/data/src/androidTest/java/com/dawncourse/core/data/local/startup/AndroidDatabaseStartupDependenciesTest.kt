package com.dawncourse.core.data.local.startup

import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.data.repository.AndroidBackupRecoveryRequiredMarker
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun atomicStoreRecoversLegacyBackupAndRejectsInterruptedNewArtifact() {
        val restoredBase = File(testDirectory, "legacy-backup.bin")
        val expected = byteArrayOf(7, 8, 9)
        File(restoredBase.path + ".bak").writeBytes(expected)

        val restored = AndroidAtomicByteStore(restoredBase).readOrNull()

        assertTrue(restored != null && restored.contentEquals(expected))
        assertTrue("读取 .bak 后必须由 AtomicFile 恢复 base", restoredBase.exists())

        val interruptedBase = File(testDirectory, "interrupted-new.bin")
        interruptedBase.writeBytes(byteArrayOf(1))
        File(interruptedBase.path + ".new").writeBytes(byteArrayOf(2))

        assertThrows(IllegalStateException::class.java) {
            AndroidAtomicByteStore(interruptedBase).readOrNull()
        }
    }

    @Test
    fun integrityStateAndDedicatedMarkerRespectAtomicArtifacts() {
        val previousStartup = File(testDirectory, "integrity-state-v1")
        File(previousStartup.path + ".bak").writeText(
            "DAWN_DATABASE_INTEGRITY_STATE_V1\n" +
                "startup_in_progress=true\n" +
                "last_success_epoch_millis=123\n",
        )

        val recoveredSnapshot = IntegrityVerificationStateStore(
            AndroidIntegrityVerificationStatePersistence(previousStartup),
        ).beginStartup()

        assertTrue("仅 .bak 的完整性状态仍必须承担上次启动责任", recoveredSnapshot.previousDatabaseStartupIncomplete)

        val interruptedState = File(testDirectory, "integrity-state-interrupted-v1")
        interruptedState.writeText(
            "DAWN_DATABASE_INTEGRITY_STATE_V1\n" +
                "startup_in_progress=false\n" +
                "last_success_epoch_millis=123\n",
        )
        File(interruptedState.path + ".new").writeText("incomplete")

        val interruptedSnapshot = IntegrityVerificationStateStore(
            AndroidIntegrityVerificationStatePersistence(interruptedState),
        ).beginStartup()

        assertTrue("base+.new 必须按不可读状态 fail closed", interruptedSnapshot.persistentStateUnreadable)

        val dedicatedMarker = File(testDirectory, "integrity-recovery-required-v1")
        File(dedicatedMarker.path + ".bak").writeText("previous recovery responsibility")
        val dedicatedStore = IntegrityRecoveryRequiredStore(
            AndroidIntegrityVerificationStatePersistence(dedicatedMarker),
        )

        assertTrue("专用完整性 marker 的 .bak 不能被忽略", dedicatedStore.requiresRecovery())

        val backupMarker = AndroidBackupRecoveryRequiredMarker(File(testDirectory, "backup-recovery-required-v1"))
        File(testDirectory, "backup-recovery-required-v1.bak").writeText("previous backup responsibility")

        assertTrue("备份 marker 的 .bak 不能被忽略", backupMarker.isRequired())
    }

    @Test
    fun bootstrapAndRecoveryMarkersTreatBackupAndNewArtifactsAsResponsibilities() {
        val isolatedContext = object : ContextWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ) {
            override fun getNoBackupFilesDir(): File = testDirectory
        }
        val attemptId = "123e4567-e89b-12d3-a456-426614174000"
        val recoveryDirectory = File(testDirectory, "database-recovery")
        val bootstrap = File(recoveryDirectory, "bootstrap-install-v1")
        File(bootstrap.path + ".bak").apply {
            parentFile?.mkdirs()
            writeText(
                "DAWN_RECOVERY_BOOTSTRAP_V1\n" +
                    "attempt=$attemptId\n" +
                    "stage=SETTINGS_APPLIED",
            )
        }

        val journal = AndroidDatabaseRecoveryInstallJournal(isolatedContext)
        assertEquals(
            DatabaseRecoveryInstallAttempt(attemptId, DatabaseRecoveryInstallStage.SETTINGS_APPLIED),
            journal.current(),
        )

        val recoveryMarker = File(recoveryDirectory, "recovery-state-v1")
        File(recoveryMarker.path + ".bak").writeText(
            "DAWN_DATABASE_RECOVERY_V1\nIntegrityVerificationFailed",
        )
        val recoveryFiles = AndroidDatabaseRecoveryFiles(
            isolatedContext,
            File(testDirectory, "database/dawn_course.db"),
        )
        assertEquals(DatabaseRecoveryReason.IntegrityVerificationFailed, recoveryFiles.readRecoveryReason())

        File(bootstrap.path + ".new").writeText("incomplete write")
        assertTrue("base+.new 的 bootstrap journal 必须仍被识别", journal.exists())
        assertEquals("中断 journal 不得被当成可提交状态", null, journal.current())
    }

    @Test
    fun atomicCleanupMustConfirmBaseBackupAndNewAreAllGone() {
        listOf(".bak", ".new").forEach { residualSuffix ->
            val marker = File(testDirectory, "residual-marker-$residualSuffix")
            val residual = File(marker.path + residualSuffix)
            marker.writeText("marker")
            residual.writeText("residual")

            assertThrows(IllegalStateException::class.java) {
                AtomicFileArtifactProtocol.deleteAndConfirm(AtomicFile(marker)) {
                    // 模拟底层 API 只移除 base，却错误遗留 .bak 或 .new。
                    marker.delete()
                }
            }
            assertTrue("确认失败不得忽略 $residualSuffix 残留", residual.exists())
        }
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
            val createdBytes = created.keyMaterial.useStoredBytes { it.copyOf() }
            loaded.keyMaterial.useStoredBytes { loadedBytes ->
                assertTrue(loadedBytes.contentEquals(createdBytes))
            }
            createdBytes.fill(0)
            created.keyMaterial.close()
            loaded.keyMaterial.close()
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
