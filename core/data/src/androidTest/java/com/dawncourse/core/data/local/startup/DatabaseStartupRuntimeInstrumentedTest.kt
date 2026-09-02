package com.dawncourse.core.data.local.startup

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.data.repository.ActiveProfileSelectionStore
import com.dawncourse.core.data.repository.BackupRecoveryRequiredStore
import com.dawncourse.core.data.repository.SettingsRepositoryImpl
import com.dawncourse.core.data.repository.settingsDataStore
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfile
import com.google.gson.GsonBuilder
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 新建、重开、缺失密钥、SAF 恢复与明确放弃的真实 Android SQLCipher 启动测试。 */
@RunWith(AndroidJUnit4::class)
class DatabaseStartupRuntimeInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseFile = context.getDatabasePath(DatabaseStartupRuntime.DATABASE_NAME)
        cleanupRuntimeArtifacts()
    }

    @After
    fun tearDown() {
        cleanupRuntimeArtifacts()
    }

    @Test
    fun newEncryptedDatabaseCanBeReopenedByAnotherColdRuntime() = runBlocking {
        val first = newRuntime().also(DatabaseStartupRuntime::start)
        assertEquals(DatabaseRuntimeState.Ready, awaitTerminal(first))
        assertFalse(hasPlaintextHeader(databaseFile))
        first.requireReadyDatabase().close()

        val reopened = newRuntime().also(DatabaseStartupRuntime::start)

        assertEquals(DatabaseRuntimeState.Ready, awaitTerminal(reopened))
        assertFalse(hasPlaintextHeader(databaseFile))
        reopened.requireReadyDatabase().close()
    }

    @Test
    fun existingEncryptedDatabaseWithMissingKeystoreKeyEntersRecoveryWithoutNewMain() = runBlocking {
        val first = newRuntime().also(DatabaseStartupRuntime::start)
        assertEquals(DatabaseRuntimeState.Ready, awaitTerminal(first))
        first.requireReadyDatabase().close()
        deleteDatabaseKeyAlias()

        val missingKey = newRuntime().also(DatabaseStartupRuntime::start)
        val state = awaitTerminal(missingKey)

        assertTrue(state is DatabaseRuntimeState.RecoveryRequired)
        assertFalse("不得用新密钥静默创建主库", databaseFile.exists())
        assertTrue(File(databaseFile.path + ".recovery-quarantine").exists())
    }

    @Test
    fun validatedSafBackupRestoresIntoNewEncryptedDatabaseWithoutOpeningCorruptMain() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(ByteArray(256) { 7 })
        val runtime = newRuntime().also(DatabaseStartupRuntime::start)
        assertTrue(awaitTerminal(runtime) is DatabaseRuntimeState.RecoveryRequired)
        val backupFile = File(context.cacheDir, "runtime-recovery-backup.json")
        backupFile.writeText(GsonBuilder().create().toJson(sampleBackup()))

        val result = runtime.restoreFromLocalBackup(Uri.fromFile(backupFile))

        assertEquals(DatabaseRecoveryActionResult.RestartRequired, result)
        assertTrue(databaseFile.isFile)
        assertFalse(hasPlaintextHeader(databaseFile))
        val reopened = newRuntime().also(DatabaseStartupRuntime::start)
        assertEquals(DatabaseRuntimeState.Ready, awaitTerminal(reopened))
        assertEquals(1, reopened.requireReadyDatabase().semesterDao().getAllSemestersOnce().size)
        assertEquals(1, reopened.requireReadyDatabase().courseDao().getAllCoursesOnce().size)
        assertEquals(
            1L,
            ActiveProfileSelectionStore(context.settingsDataStore).rawActiveProfileId.first(),
        )
        reopened.requireReadyDatabase().close()
        backupFile.delete()
        Unit
    }

    @Test
    fun explicitAbandonCreatesEmptyEncryptedDatabaseAndNeverOverwritesBeforeConsent() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(ByteArray(256) { 3 })
        val runtime = newRuntime().also(DatabaseStartupRuntime::start)
        assertTrue(awaitTerminal(runtime) is DatabaseRuntimeState.RecoveryRequired)
        assertFalse(databaseFile.exists())

        val result = runtime.abandonInaccessibleData()

        assertEquals(DatabaseRecoveryActionResult.RestartRequired, result)
        assertTrue(databaseFile.isFile)
        assertFalse(hasPlaintextHeader(databaseFile))
        val reopened = newRuntime().also(DatabaseStartupRuntime::start)
        assertEquals(DatabaseRuntimeState.Ready, awaitTerminal(reopened))
        assertTrue(reopened.requireReadyDatabase().semesterDao().getAllSemestersOnce().isEmpty())
        reopened.requireReadyDatabase().close()
    }

    private fun newRuntime(): DatabaseStartupRuntime = DatabaseStartupRuntime(
        context = context,
        settingsRepository = SettingsRepositoryImpl(context),
        activeProfileSelectionStore = ActiveProfileSelectionStore(context.settingsDataStore),
        backupRecoveryRequiredStore = BackupRecoveryRequiredStore(context)
    )

    private suspend fun awaitTerminal(runtime: DatabaseStartupRuntime): DatabaseRuntimeState =
        withTimeout(30_000L) {
            runtime.state.filter { it !is DatabaseRuntimeState.Starting }.first()
        }

    private fun sampleBackup(): LocalBackupData = LocalBackupData(
        version = 4,
        exportTime = System.currentTimeMillis(),
        appVersionName = "instrumentation",
        settings = AppSettings(),
        semesters = listOf(
            Semester(
                id = 1,
                profileId = 1,
                name = "恢复测试学期",
                startDate = 1L,
                weekCount = 16,
                isCurrent = true,
            )
        ),
        courses = listOf(
            Course(
                id = 1,
                semesterId = 1,
                name = "恢复测试课程",
                location = "A101",
                dayOfWeek = 1,
                startSection = 1,
                duration = 2,
                startWeek = 1,
                endWeek = 16
            )
        ),
        sourceBindings = emptyList(),
        profiles = listOf(
            TimetableProfile(
                id = 1,
                uuid = "a5a7e256-11e0-4e4a-92d8-b4cf3f7b2d01",
                name = "恢复测试课表",
                activeSemesterId = 1,
            )
        ),
        activeProfileId = 1L,
        selectedSemesterId = null,
    )

    private fun hasPlaintextHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return file.inputStream().use { input ->
            val actual = ByteArray(SQLITE_HEADER.size)
            input.read(actual) == actual.size && actual.contentEquals(SQLITE_HEADER)
        }
    }

    private fun cleanupRuntimeArtifacts() {
        runCatching { context.deleteDatabase(DatabaseStartupRuntime.DATABASE_NAME) }
        context.databaseList()
            .filter { it.startsWith(DatabaseStartupRuntime.DATABASE_NAME) }
            .forEach { name -> runCatching { context.deleteDatabase(name) } }
        databaseFile.parentFile?.listFiles()
            ?.filter { it.name.startsWith(DatabaseStartupRuntime.DATABASE_NAME) }
            ?.forEach { it.delete() }
        File(context.noBackupFilesDir, "database").deleteRecursively()
        File(context.noBackupFilesDir, "database-recovery").deleteRecursively()
        deleteDatabaseKeyAlias()
    }

    private fun deleteDatabaseKeyAlias() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("com.dawncourse.database.key.v1")
        }
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
