package com.dawncourse.core.data.local.startup

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据库启动安全状态机的契约测试。 */
class DatabaseStartupCoordinatorTest {
    @Test
    fun opaqueDatabaseWithoutEnvelopeRequiresRecoveryInsteadOfCreatingPassphrase() {
        val envelopeStore = FakeEnvelopeStore(existing = ExistingPassphraseResult.Missing)
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.OpaqueData),
            envelopeStore = envelopeStore
        )

        val result = coordinator.prepare()

        assertEquals(
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid),
            result
        )
        assertEquals(0, envelopeStore.createCalls)
    }

    @Test
    fun plaintextDatabaseWithoutEnvelopeCreatesPassphraseOnlyForAtomicMigration() {
        val envelopeStore = FakeEnvelopeStore(existing = ExistingPassphraseResult.Missing)
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.PlaintextSqlite),
            envelopeStore = envelopeStore
        )

        val result = coordinator.prepare()

        assertTrue(result is DatabaseStartupPlan.EncryptPlaintextDatabase)
        assertEquals(1, envelopeStore.createCalls)
    }

    @Test
    fun corruptDatabaseRequiresRecoveryWithoutTouchingEnvelope() {
        val envelopeStore = FakeEnvelopeStore(existing = ExistingPassphraseResult.Missing)
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.CorruptOrUnknown),
            envelopeStore = envelopeStore
        )

        val result = coordinator.prepare()

        assertEquals(
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.CorruptOrUnknown),
            result
        )
        assertEquals(0, envelopeStore.loadCalls)
        assertEquals(0, envelopeStore.createCalls)
    }

    @Test
    fun noDatabaseWithoutEnvelopeCreatesNewEncryptedDatabase() {
        val envelopeStore = FakeEnvelopeStore(existing = ExistingPassphraseResult.Missing)
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.NoDatabase),
            envelopeStore = envelopeStore
        )

        val result = coordinator.prepare()

        assertTrue(result is DatabaseStartupPlan.CreateNewEncryptedDatabase)
        assertEquals(1, envelopeStore.createCalls)
    }

    @Test
    fun unwrapFailureRequiresRecoveryAndNeverProvisionsReplacementPassphrase() {
        val atomicByteStore = MemoryAtomicByteStore(initial = null)
        val creator = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStore,
            keyProvider = FakeKeyProvider(),
            randomByteSource = FixedRandomByteSource()
        )
        (creator.createNew() as NewPassphraseResult.Available).passphrase.close()
        val keyProvider = FakeKeyProvider(existingResult = KeyEncryptionKeyResult.MissingOrInvalid)
        val envelopeStore = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStore,
            keyProvider = keyProvider,
            randomByteSource = FixedRandomByteSource()
        )
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.OpaqueData),
            envelopeStore = envelopeStore
        )

        val result = coordinator.prepare()

        assertEquals(
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid),
            result
        )
        assertEquals(0, keyProvider.createCalls)
    }

    @Test
    fun validOpaqueDatabaseUsesExistingUnwrappedPassphrase() {
        val atomicByteStore = MemoryAtomicByteStore(initial = null)
        val keyProvider = FakeKeyProvider()
        val creator = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStore,
            keyProvider = keyProvider,
            randomByteSource = FixedRandomByteSource()
        )
        (creator.createNew() as NewPassphraseResult.Available).passphrase.close()
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.OpaqueData),
            envelopeStore = DatabaseKeyEnvelopeStore(
                atomicByteStore = atomicByteStore,
                keyProvider = keyProvider,
                randomByteSource = FixedRandomByteSource()
            )
        )

        val result = coordinator.prepare()

        assertTrue(result is DatabaseStartupPlan.OpenEncryptedDatabase)
        (result as DatabaseStartupPlan.OpenEncryptedDatabase).passphrase.useBytes { bytes ->
            assertTrue(bytes.contentEquals(ByteArray(32) { it.toByte() }))
        }
        result.passphrase.close()
    }

    @Test
    fun stateResolverMapsOpaqueMissingKeyToRecoveryState() {
        val state = DatabaseStartupStateResolver.resolve(
            inspection = DatabaseFileInspection.OpaqueData,
            existingPassphrase = ExistingPassphraseResult.Missing
        )

        assertEquals(DatabaseStartupState.DatabasePresentButKeyMissingOrInvalid, state)
    }

    @Test
    fun fileInspectionFailureIsMappedToRecoveryInsteadOfEscapingStartup() {
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = DatabaseFileInspector { error("测试文件读取失败") },
            envelopeStore = FakeEnvelopeStore(existing = ExistingPassphraseResult.Missing)
        )

        val result = coordinator.prepare()

        assertEquals(
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.CorruptOrUnknown),
            result
        )
    }

    @Test
    fun envelopeReadFailureIsMappedToRecoveryInsteadOfCreatingPassphrase() {
        val coordinator = DatabaseStartupCoordinator(
            fileInspector = FakeFileInspector(DatabaseFileInspection.OpaqueData),
            envelopeStore = object : DatabasePassphraseEnvelopeStore {
                override fun loadExisting(): ExistingPassphraseResult = error("测试信封读取失败")

                override fun createNew(): NewPassphraseResult = error("不应创建替代口令")
            }
        )

        val result = coordinator.prepare()

        assertEquals(
            DatabaseStartupPlan.RecoveryRequired(DatabaseRecoveryReason.KeyMissingOrInvalid),
            result
        )
    }

    @Test
    fun createNewRefusesToOverwriteExistingEnvelope() {
        val atomicByteStore = MemoryAtomicByteStore(byteArrayOf(9, 9, 9))
        val keyProvider = FakeKeyProvider()
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStore,
            keyProvider = keyProvider,
            randomByteSource = FixedRandomByteSource()
        )

        val result = store.createNew()

        assertEquals(NewPassphraseResult.ExistingEnvelope, result)
        assertEquals(0, keyProvider.createCalls)
        assertTrue(atomicByteStore.bytes.contentEquals(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun contenderRechecksEnvelopeAfterTakingExclusiveLockAndDoesNotProvisionAnotherKey() {
        val keyProvider = FakeKeyProvider()
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = object : AtomicByteStore {
                private var bytes: ByteArray? = null

                override fun <T> withExclusiveLock(block: () -> T): T {
                    // 模拟另一个进程已在本进程取得锁之前完成信封写入。
                    bytes = byteArrayOf(9, 9, 9)
                    return block()
                }

                override fun readOrNull(): ByteArray? = bytes?.copyOf()

                override fun writeAtomically(bytes: ByteArray) = error("已有信封时不得写入")

            },
            keyProvider = keyProvider,
            randomByteSource = FixedRandomByteSource()
        )

        val result = store.createNew()

        assertEquals(NewPassphraseResult.ExistingEnvelope, result)
        assertEquals(0, keyProvider.createCalls)
    }

    @Test
    fun keyProvisioningAndEnvelopeWriteShareOneExclusiveSection() {
        val events = mutableListOf<String>()
        val atomicByteStore = object : AtomicByteStore {
            override fun <T> withExclusiveLock(block: () -> T): T {
                events += "lock"
                return try {
                    block()
                } finally {
                    events += "unlock"
                }
            }

            override fun readOrNull(): ByteArray? {
                events += "read"
                return null
            }

            override fun writeAtomically(bytes: ByteArray) {
                events += "write"
            }

        }
        val keyProvider = object : KeyEncryptionKeyProvider {
            override fun getExisting(alias: String): KeyEncryptionKeyResult =
                KeyEncryptionKeyResult.MissingOrInvalid

            override fun getOrCreate(alias: String): KeyEncryptionKeyResult {
                events += "key"
                return KeyEncryptionKeyResult.Available(SecretKeySpec(ByteArray(32) { 4 }, "AES"))
            }
        }
        val store = DatabaseKeyEnvelopeStore(
            atomicByteStore = atomicByteStore,
            keyProvider = keyProvider,
            randomByteSource = FixedRandomByteSource()
        )

        val result = store.createNew()

        assertTrue(result is NewPassphraseResult.Available)
        (result as NewPassphraseResult.Available).passphrase.close()
        assertEquals(listOf("lock", "read", "key", "write", "unlock"), events)
    }

    private class FakeFileInspector(
        private val inspection: DatabaseFileInspection
    ) : DatabaseFileInspector {
        override fun inspect(): DatabaseFileInspection = inspection
    }

    private class FakeEnvelopeStore(
        private val existing: ExistingPassphraseResult
    ) : DatabasePassphraseEnvelopeStore {
        var loadCalls: Int = 0
        var createCalls: Int = 0

        override fun loadExisting(): ExistingPassphraseResult {
            loadCalls += 1
            return existing
        }

        override fun createNew(): NewPassphraseResult {
            createCalls += 1
            return NewPassphraseResult.Available(SqlCipherPassphrase.fromBytes(ByteArray(32) { 7 }))
        }
    }

    private class MemoryAtomicByteStore(initial: ByteArray?) : AtomicByteStore {
        var bytes: ByteArray? = initial?.copyOf()

        override fun <T> withExclusiveLock(block: () -> T): T = block()

        override fun readOrNull(): ByteArray? = bytes?.copyOf()

        override fun writeAtomically(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

    }

    private class FakeKeyProvider(
        private val existingResult: KeyEncryptionKeyResult = KeyEncryptionKeyResult.Available(
            SecretKeySpec(ByteArray(32) { 4 }, "AES")
        )
    ) : KeyEncryptionKeyProvider {
        var createCalls: Int = 0

        override fun getExisting(alias: String): KeyEncryptionKeyResult = existingResult

        override fun getOrCreate(alias: String): KeyEncryptionKeyResult {
            createCalls += 1
            return KeyEncryptionKeyResult.Available(SecretKeySpec(ByteArray(32) { 4 }, "AES"))
        }
    }

    private class FixedRandomByteSource : SecureRandomByteSource {
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { index -> index.toByte() }
    }
}
