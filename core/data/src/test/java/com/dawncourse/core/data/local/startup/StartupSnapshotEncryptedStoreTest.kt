package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotCourse
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotProfile
import com.dawncourse.core.domain.model.StartupSnapshotRevision
import com.dawncourse.core.domain.model.StartupSnapshotSectionTime
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
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

    @Test
    fun readWaitsForConcurrentAtomicCommitAndKeepsTheNewSnapshot() {
        val artifactStore = CommitBarrierArtifactStore()
        val store = StartupSnapshotEncryptedStore(
            atomicByteStore = artifactStore,
            keyProvider = FixedKeyProvider(key),
        )
        val oldSnapshot = signedSnapshot(zoneId = "Asia/Shanghai")
        val newSnapshot = signedSnapshot(zoneId = "UTC")
        assertTrue(store.replace(oldSnapshot))

        val executor = Executors.newFixedThreadPool(2)
        try {
            artifactStore.blockNextCommit()
            val replace = executor.submit<Boolean> { store.replace(newSnapshot) }
            assertTrue("replace 应停在 AtomicFile 提交前", artifactStore.commitStarted.await(2, TimeUnit.SECONDS))

            val read = executor.submit<StartupSnapshotReadResult> {
                store.read(expectedProfileId = 7L, nowEpochMillis = now, expectedZoneId = "UTC")
            }
            // 正确实现会在 artifact 锁外阻塞，因而此处不能触碰 `.new`。旧实现则会锁外
            // 读取并立即命中该屏障，随后在写入提交后执行迟到删除。
            assertFalse(
                "read 不得在 AtomicFile 提交前绕过 artifact 锁",
                artifactStore.readAttempted.await(200, TimeUnit.MILLISECONDS),
            )
            artifactStore.allowCommit.countDown()

            assertTrue(replace.get(2, TimeUnit.SECONDS))
            assertEquals(
                StartupSnapshotReadResult.Available(newSnapshot),
                read.get(2, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun encoderPreflightRejectsAnyFieldThatDecoderWouldRejectByUtf8ByteLength() {
        val course = StartupSnapshotCourse(
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
        val valid = signedSnapshot(courses = listOf(course))
        val oversizedUri = "u".repeat(16 * 1024 + 1)

        listOf(
            "uuid" to valid.copy(profile = valid.profile.copy(uuid = "u".repeat(257))),
            "course color" to valid.copy(courses = listOf(course.copy(color = "c".repeat(65)))),
            "divider color" to valid.copy(visualSettings = valid.visualSettings.copy(dividerColor = "c".repeat(65))),
            "section time" to valid.copy(visualSettings = valid.visualSettings.copy(
                sectionTimes = listOf(StartupSnapshotSectionTime("s".repeat(33), "08:00")),
            )),
            "zone" to valid.copy(zoneId = "z".repeat(129)),
            "wallpaper uri" to valid.copy(visualSettings = valid.visualSettings.copy(wallpaperUri = oversizedUri)),
        ).forEach { (field, snapshot) ->
            assertFalse(
                "$field 超过 decoder UTF-8 上限时 encode 必须拒绝",
                runCatching { StartupSnapshotPayloadCodec.encode(snapshot) }.isSuccess,
            )
        }
    }

    @Test
    fun replaceRejectsTwoThousandLargeCoursesBeforeCreatingAKeyOrPayload() {
        val largeName = "课".repeat(5_461) // 16,383 UTF-8 bytes，单字段合法但组合必超 512 KiB。
        val course = StartupSnapshotCourse(
            id = 1L,
            name = largeName,
            teacher = "",
            location = "",
            dayOfWeek = 1,
            startSection = 1,
            duration = 1,
            startWeek = 1,
            endWeek = 18,
            weekType = StartupSnapshotWeekType.ALL,
            color = "#112233",
        )
        val oversized = signedSnapshot(courses = (1L..2_000L).map { course.copy(id = it) })
        val keyProvider = CountingKeyProvider(key)
        val store = StartupSnapshotEncryptedStore(InMemoryAtomicByteStore(), keyProvider)

        assertFalse(store.replace(oversized))
        assertEquals("总 payload 超限不得触及 Keystore", 0, keyProvider.createCalls)
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

    private class CountingKeyProvider(private val key: javax.crypto.SecretKey) : KeyEncryptionKeyProvider {
        var createCalls = 0

        override fun getExisting(alias: String) = KeyEncryptionKeyResult.Available(key)

        override fun getOrCreate(alias: String): KeyEncryptionKeyResult.Available {
            createCalls += 1
            return KeyEncryptionKeyResult.Available(key)
        }
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

    /**
     * 真实互斥区模拟：write 已创建 `.new` 但尚未提交时，任何锁外读取都会认为工件中断。
     * 这能复现“读失败后迟到删除”吞掉新 base 文件的跨线程竞态。
     */
    private class CommitBarrierArtifactStore : StartupSnapshotArtifactStore {
        private val lock = ReentrantLock()
        private var bytes: ByteArray? = null
        private var writeInProgress = false
        private var shouldBlockCommit = false
        val commitStarted = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val readAttempted = CountDownLatch(1)

        override fun <T> withExclusiveLock(block: () -> T): T = lock.run {
            lock()
            try {
                block()
            } finally {
                unlock()
            }
        }

        override fun readOrNull(): ByteArray? {
            readAttempted.countDown()
            check(!writeInProgress) { "模拟 AtomicFile .new 尚未提交" }
            return bytes?.copyOf()
        }

        override fun writeAtomically(bytes: ByteArray) {
            writeInProgress = true
            if (shouldBlockCommit) {
                commitStarted.countDown()
                check(allowCommit.await(2, TimeUnit.SECONDS)) { "测试未释放提交屏障" }
            }
            this.bytes = bytes.copyOf()
            writeInProgress = false
        }

        override fun deleteAtomically() {
            bytes = null
            writeInProgress = false
        }

        fun blockNextCommit() {
            shouldBlockCommit = true
        }
    }
}
