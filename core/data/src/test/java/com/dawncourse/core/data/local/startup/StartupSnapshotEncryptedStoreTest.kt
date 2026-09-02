package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotCourse
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotProfile
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.StartupSnapshotSemester
import com.dawncourse.core.domain.model.StartupSnapshotThemeMode
import com.dawncourse.core.domain.model.StartupSnapshotValidity
import com.dawncourse.core.domain.model.StartupSnapshotVisualSettings
import com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode
import com.dawncourse.core.domain.model.StartupSnapshotWeekType
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/** 加密启动快照的 envelope、AAD 与原子替换必须全部 fail-closed。 */
class StartupSnapshotEncryptedStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private val now = 1_750_000_000_000L

    @Test
    fun envelopeRejectsMagicVersionAndUnknownPayloadEnum() {
        assertEquals(null, StartupSnapshotPayloadCodec.decode(byteArrayOf(0, 1, 2)))
        assertEquals(null, StartupSnapshotPayloadCodec.decode(payloadWithUnknownProtocolVersion()))
        assertEquals(null, StartupSnapshotPayloadCodec.decode(payloadWithUnknownThemeEnum()))
    }

    @Test
    fun ciphertextIvAndAadTamperingBecomeCacheMisses() {
        val original = signedSnapshot()
        listOf(
            { bytes: ByteArray -> bytes[0] = (bytes[0].toInt() xor 1).toByte() },
            { bytes: ByteArray -> bytes[StartupSnapshotEnvelope.HEADER_BYTES] = (bytes[StartupSnapshotEnvelope.HEADER_BYTES].toInt() xor 1).toByte() },
            { bytes: ByteArray -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() },
        ).forEach { tamper ->
            val bytes = StartupSnapshotEnvelope.encrypt(original, key, StartupSnapshotEnvelope.DEFAULT_AAD)
            tamper(bytes)
            assertEquals(null, StartupSnapshotEnvelope.decrypt(bytes, key, StartupSnapshotEnvelope.DEFAULT_AAD))
        }

        val bytes = StartupSnapshotEnvelope.encrypt(original, key, StartupSnapshotEnvelope.DEFAULT_AAD)
        assertEquals(null, StartupSnapshotEnvelope.decrypt(bytes, key, "other-aad".toByteArray()))
    }

    @Test
    fun missingKeyAndFailedAtomicWriteKeepPriorArtifact() {
        val byteStore = InMemoryAtomicByteStore()
        val store = StartupSnapshotEncryptedStore(
            atomicByteStore = byteStore,
            keyProvider = FixedKeyProvider(key),
        )
        val original = signedSnapshot()
        assertTrue(store.replace(original))

        byteStore.failWrites = true
        assertFalse(store.replace(signedSnapshot(zoneId = "UTC")))
        val retained = store.read(expectedProfileId = 7L, nowEpochMillis = now, expectedZoneId = "Asia/Shanghai")
        assertEquals(original, (retained as StartupSnapshotReadResult.Available).snapshot)

        val missingKeyStore = StartupSnapshotEncryptedStore(
            atomicByteStore = byteStore.copyReadable(),
            keyProvider = object : KeyEncryptionKeyProvider {
                override fun getExisting(alias: String) = KeyEncryptionKeyResult.MissingOrInvalid
                override fun getOrCreate(alias: String) = KeyEncryptionKeyResult.MissingOrInvalid
            },
        )
        assertEquals(
            StartupSnapshotReadResult.Missing,
            missingKeyStore.read(expectedProfileId = 7L, nowEpochMillis = now, expectedZoneId = "Asia/Shanghai"),
        )
    }

    @Test
    fun payloadEncodingIsStableWhenCourseInputOrderChanges() {
        val first = StartupSnapshotCourse(
            id = 9L,
            name = "高等数学",
            teacher = "张老师",
            location = "A101",
            dayOfWeek = 1,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 18,
            weekType = StartupSnapshotWeekType.ALL,
            color = "#112233",
        )
        val second = first.copy(id = 3L, name = "大学英语", dayOfWeek = 3)
        val snapshot = signedSnapshot(courses = listOf(first, second))

        assertTrue(
            StartupSnapshotPayloadCodec.encode(snapshot)
                .contentEquals(StartupSnapshotPayloadCodec.encode(snapshot.copy(courses = snapshot.courses.reversed())))
        )
    }

    private fun signedSnapshot(
        zoneId: String = "Asia/Shanghai",
        courses: List<StartupSnapshotCourse> = emptyList(),
    ): StartupSnapshot {
        val unsigned = StartupSnapshot(
            protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
            profile = StartupSnapshotProfile(7L, "profile-seven"),
            semester = courses.takeIf { it.isNotEmpty() }?.let {
                StartupSnapshotSemester(
                    id = 13L,
                    profileId = 7L,
                    name = "2026 秋",
                    startDateEpochMillis = now - 1L,
                    weekCount = 18,
                )
            },
            courses = courses,
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
            createdAtEpochMillis = now - 1,
            expiresAtEpochMillis = now + StartupSnapshot.TTL_MILLIS,
            zoneId = zoneId,
            revision = StartupSnapshotRevision("pending"),
        )
        return unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))
    }

    private fun payloadWithUnknownProtocolVersion(): ByteArray = byteArrayOf(
        'D'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(),
        0, 0, 0, 2,
    )

    private fun payloadWithUnknownThemeEnum(): ByteArray = byteArrayOf(
        'D'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(),
        0, 0, 0, 1,
        // Remaining bytes are deliberately incomplete. Strict decoder must reject rather than infer defaults.
        0, 0, 0, 99,
    )

    private class FixedKeyProvider(private val key: javax.crypto.SecretKey) : KeyEncryptionKeyProvider {
        override fun getExisting(alias: String) = KeyEncryptionKeyResult.Available(key)
        override fun getOrCreate(alias: String) = KeyEncryptionKeyResult.Available(key)
    }

    private class InMemoryAtomicByteStore(
        private var bytes: ByteArray? = null,
    ) : StartupSnapshotArtifactStore {
        var failWrites: Boolean = false

        override fun <T> withExclusiveLock(block: () -> T): T = block()
        override fun readOrNull(): ByteArray? = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            if (failWrites) error("simulated atomic write failure")
            this.bytes = bytes.copyOf()
        }

        override fun deleteAtomically() {
            bytes = null
        }

        fun copyReadable(): InMemoryAtomicByteStore = InMemoryAtomicByteStore(bytes?.copyOf())
    }
}
