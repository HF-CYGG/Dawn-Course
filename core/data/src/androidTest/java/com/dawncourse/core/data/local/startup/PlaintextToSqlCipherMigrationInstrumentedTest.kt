package com.dawncourse.core.data.local.startup

import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLCipher 4.18.0 真实导出、原子换入和崩溃恢复的 Android 16 契约测试。 */
@RunWith(AndroidJUnit4::class)
class PlaintextToSqlCipherMigrationInstrumentedTest {
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDirectory = File(context.noBackupFilesDir, "sqlcipher-migration-${UUID.randomUUID()}")
        assertTrue(testDirectory.mkdirs())
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun realV5ishPlaintextDatabaseIsExportedAndReopenedWithBoundQuotedPath() {
        val main = File(testDirectory, "legacy-'quoted'.db")
        createV5ishPlaintextDatabase(main)
        val backend = AndroidPlaintextToSqlCipherMigrationBackend()
        val files = AtomicDatabaseMigrationFiles(main)
        val passphrase = testPassphrase()

        val result = PlaintextToSqlCipherMigrator(files, backend).migrate(passphrase)

        assertTrue(result is PlaintextToSqlCipherMigrationResult.Success)
        assertFalse("换入后的主库不得保留 SQLite 明文头", hasPlaintextSqliteHeader(main))
        val success = result as PlaintextToSqlCipherMigrationResult.Success
        assertTrue("明文 pre-image 必须保留", hasPlaintextSqliteHeader(success.retainedPlaintextPreimage))
        val source = backend.inspectPlaintext(success.retainedPlaintextPreimage)
        val target = backend.inspectEncrypted(main, passphrase)
        assertEquals(5, target.snapshot.userVersion)
        assertEquals(source.snapshot, target.snapshot)
        assertTrue(target.integrityOk)
        assertEquals(true, target.cipherIntegrityOk)
        passphrase.close()
    }

    @Test
    fun injectedSwapFailureLeavesMainDatabaseOpenableAsPlaintext() {
        val main = File(testDirectory, "swap-failure.db")
        createV5ishPlaintextDatabase(main)
        val delegate = AtomicDatabaseMigrationFiles(main)
        val failingFiles = object : DatabaseMigrationFileOperations by delegate {
            override fun swapEncryptedIntoMain(attempt: DatabaseMigrationAttempt) {
                error("模拟原子换入失败")
            }
        }
        val passphrase = testPassphrase()

        val result = PlaintextToSqlCipherMigrator(
            files = failingFiles,
            backend = AndroidPlaintextToSqlCipherMigrationBackend()
        ).migrate(passphrase)

        assertEquals(
            PlaintextToSqlCipherMigrationResult.RecoveryRequired(DatabaseMigrationFailure.SwapFailed),
            result
        )
        assertTrue(hasPlaintextSqliteHeader(main))
        PlatformSQLiteDatabase.openDatabase(
            main.path,
            null,
            PlatformSQLiteDatabase.OPEN_READONLY
        ).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM courses", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
        passphrase.close()
    }

    @Test
    fun crashAfterSwapIsRecoveredBeforeARepeatMigration() {
        val main = File(testDirectory, "crash-recovery.db")
        createV5ishPlaintextDatabase(main)
        val ids = ArrayDeque(listOf(ATTEMPT_ONE, ATTEMPT_TWO))
        val files = AtomicDatabaseMigrationFiles(main) { ids.removeFirst() }
        val backend = AndroidPlaintextToSqlCipherMigrationBackend()
        val passphrase = testPassphrase()

        files.withExclusiveLock {
            val attempt = files.beginAttempt()
            backend.checkpointAndClosePlaintext(main)
            files.createPlaintextPreimage(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.PREIMAGE_READY)
            val source = backend.inspectPlaintext(attempt.plaintextPreimage)
            backend.exportPlaintextToEncrypted(
                attempt.plaintextPreimage,
                attempt.encryptedTemp,
                passphrase,
                source.snapshot
            )
            files.recordStage(attempt, DatabaseMigrationStage.ENCRYPTED_TEMP_READY)
            files.recordStage(attempt, DatabaseMigrationStage.SWAP_PENDING)
            files.swapEncryptedIntoMain(attempt)
            files.recordStage(attempt, DatabaseMigrationStage.SWAPPED_NOT_VERIFIED)
        }
        assertFalse(hasPlaintextSqliteHeader(main))

        val repeated = PlaintextToSqlCipherMigrator(files, backend).migrate(passphrase)

        assertTrue(repeated is PlaintextToSqlCipherMigrationResult.Success)
        assertFalse(hasPlaintextSqliteHeader(main))
        val verification = backend.inspectEncrypted(main, passphrase)
        assertEquals(
            linkedMapOf("android_metadata" to 1L, "courses" to 2L, "semesters" to 1L),
            verification.snapshot.userTableRowCounts
        )
        passphrase.close()
    }

    /** 创建带版本、两张用户表和索引的真实明文 SQLite v5-ish 数据库。 */
    private fun createV5ishPlaintextDatabase(file: File) {
        PlatformSQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE semesters (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, startDate INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE courses (id INTEGER PRIMARY KEY NOT NULL, semesterId INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "dayOfWeek INTEGER NOT NULL, startSection INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                    "location TEXT NOT NULL DEFAULT '')"
            )
            database.execSQL("CREATE INDEX index_courses_semesterId ON courses(semesterId)")
            database.execSQL("INSERT INTO semesters(id, name, startDate) VALUES(1, '2026 秋', 1788105600000)")
            database.execSQL(
                "INSERT INTO courses(id, semesterId, name, dayOfWeek, startSection, duration, location) " +
                    "VALUES(10, 1, '高等数学', 1, 1, 2, 'A101')"
            )
            database.execSQL(
                "INSERT INTO courses(id, semesterId, name, dayOfWeek, startSection, duration, location) " +
                    "VALUES(11, 1, '大学英语', 3, 3, 2, 'B202')"
            )
            database.version = 5
        }
    }

    /** 返回由测试持有并最终清零的固定 32-byte SQLCipher 口令。 */
    private fun testPassphrase(): DatabaseKeyMaterial =
        DatabaseKeyMaterial.RawKeyLiteral.fromBytes(ByteArray(32) { index -> (index + 1).toByte() })

    /** 只读取 SQLite 固定文件头，不尝试输出文件内容。 */
    private fun hasPlaintextSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        val actual = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < actual.size) {
                val read = input.read(actual, offset, actual.size - offset)
                if (read <= 0) return false
                offset += read
            }
        }
        return actual.contentEquals(SQLITE_HEADER)
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        const val ATTEMPT_ONE = "00000000-0000-0000-0000-000000000001"
        const val ATTEMPT_TWO = "00000000-0000-0000-0000-000000000002"
    }
}
