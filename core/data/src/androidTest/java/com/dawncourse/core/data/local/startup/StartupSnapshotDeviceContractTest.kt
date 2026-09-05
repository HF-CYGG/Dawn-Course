package com.dawncourse.core.data.local.startup

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotProfile
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.StartupSnapshotThemeMode
import com.dawncourse.core.domain.model.StartupSnapshotVisualSettings
import com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Android Keystore、AAD 与 no-backup AtomicFile 的启动快照契约。 */
@RunWith(AndroidJUnit4::class)
class StartupSnapshotDeviceContractTest {
    private lateinit var snapshotFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        snapshotFile = File(context.noBackupFilesDir, "startup/startup_snapshot_v1.bin")
        cleanup()
    }

    @After
    fun tearDown() = cleanup()

    @Test
    fun keystoreAliasAndAadRoundTripUseAesGcmWithoutExportedKeyMaterial() {
        val provider = AndroidKeystoreAesGcmKeyProvider()
        val store = StartupSnapshotEncryptedStore(
            atomicByteStore = AndroidStartupSnapshotArtifactStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
            keyProvider = provider,
        )
        val snapshot = signedSnapshot()

        assertTrue(store.replace(snapshot))
        assertEquals(
            StartupSnapshotReadResult.Available(snapshot),
            store.read(expectedProfileId = 7L, nowEpochMillis = NOW, expectedZoneId = "Asia/Shanghai"),
        )

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(STARTUP_SNAPSHOT_KEY_ALIAS, null) as SecretKey
        assertTrue(keyStore.containsAlias(STARTUP_SNAPSHOT_KEY_ALIAS))
        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertEquals(null, key.encoded)

        val encrypted = snapshotFile.readBytes()
        assertEquals(null, StartupSnapshotEnvelope.decrypt(encrypted, key, "wrong-aad".toByteArray()))
    }

    @Test
    fun artifactStoreRestoresBackupAndRejectsInterruptedNewFile() {
        val store = AndroidStartupSnapshotArtifactStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val backupBytes = byteArrayOf(9, 8, 7)
        snapshotFile.parentFile?.mkdirs()
        File(snapshotFile.path + ".bak").writeBytes(backupBytes)

        val restored = store.readOrNull()

        assertTrue(restored != null && restored.contentEquals(backupBytes))
        assertTrue(snapshotFile.exists())

        File(snapshotFile.path + ".new").writeBytes(byteArrayOf(1))
        assertTrue(runCatching { store.readOrNull() }.isFailure)
    }

    private fun cleanup() {
        runCatching { snapshotFile.delete() }
        runCatching { File(snapshotFile.path + ".bak").delete() }
        runCatching { File(snapshotFile.path + ".new").delete() }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(STARTUP_SNAPSHOT_KEY_ALIAS)
        }
    }

    private fun signedSnapshot(): StartupSnapshot {
        val unsigned = StartupSnapshot(
            protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
            profile = StartupSnapshotProfile(7L, "profile-seven"),
            semester = null,
            courses = emptyList(),
            visualSettings = StartupSnapshotVisualSettings(
                dynamicColor = false,
                wallpaperUri = null,
                transparency = 0f,
                fontStyle = StartupSnapshotFontStyle.SYSTEM,
                dividerType = StartupSnapshotDividerType.SOLID,
                dividerWidthDp = 1f,
                dividerColor = "#ffffff",
                dividerAlpha = 1f,
                courseItemHeightDp = 64,
                maxDailySections = 12,
                sectionTimes = emptyList(),
                cardCornerRadius = 16,
                cardAlpha = 1f,
                showCourseIcons = true,
                wallpaperMode = StartupSnapshotWallpaperMode.CROP,
                themeMode = StartupSnapshotThemeMode.SYSTEM,
                showWeekend = true,
                showSidebarTime = true,
                showSidebarIndex = true,
                hideNonThisWeek = false,
                showDateInHeader = false,
                backgroundBlur = 0f,
                backgroundBrightness = 1f,
            ),
            createdAtEpochMillis = NOW - 1L,
            expiresAtEpochMillis = NOW + StartupSnapshot.TTL_MILLIS,
            zoneId = "Asia/Shanghai",
            revision = StartupSnapshotRevision("pending"),
        )
        return unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))
    }

    private companion object {
        const val NOW = 1_750_000_000_000L
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
