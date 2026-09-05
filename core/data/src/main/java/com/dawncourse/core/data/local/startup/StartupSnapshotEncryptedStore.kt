package com.dawncourse.core.data.local.startup

import android.content.Context
import android.util.AtomicFile
import com.dawncourse.core.domain.model.MAX_COURSES
import com.dawncourse.core.domain.model.MAX_SECTION_TIMES
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
import com.dawncourse.core.domain.model.isStartupSnapshotSemanticallyValid
import com.dawncourse.core.domain.model.validateForStartup
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 独立启动快照文件的最小原子存储边界，读取路径不接触 Room。 */
interface StartupSnapshotArtifactStore {
    /** 读取、校验失败后的删除与写入必须共享这一跨进程互斥区。 */
    fun <T> withExclusiveLock(block: () -> T): T
    fun readOrNull(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
    fun deleteAtomically()
}

/** Android `noBackupFilesDir/startup/startup_snapshot_v1.bin` 的 AtomicFile 实现。 */
class AndroidStartupSnapshotArtifactStore(context: Context) : StartupSnapshotArtifactStore {
    private val file = File(context.noBackupFilesDir, SNAPSHOT_RELATIVE_PATH)
    private val delegate = AndroidAtomicByteStore(file)
    private val atomicFile = AtomicFile(file)

    override fun <T> withExclusiveLock(block: () -> T): T = delegate.withExclusiveLock(block)
    override fun readOrNull(): ByteArray? = delegate.readOrNull()
    override fun writeAtomically(bytes: ByteArray) = delegate.writeAtomically(bytes)
    override fun deleteAtomically() = AtomicFileArtifactProtocol.deleteAndConfirm(atomicFile)

    private companion object {
        const val SNAPSHOT_RELATIVE_PATH = "startup/startup_snapshot_v1.bin"
    }
}

/**
 * 启动快照的 AES-256-GCM 存储；只读路径永不创建 Key，任意失败均清理后 cache miss。
 */
open class StartupSnapshotEncryptedStore(
    atomicByteStore: StartupSnapshotArtifactStore,
    private val keyProvider: KeyEncryptionKeyProvider,
    private val aad: ByteArray = StartupSnapshotEnvelope.DEFAULT_AAD,
) {
    private val artifactStore = atomicByteStore
    fun read(
        expectedProfileId: Long?,
        nowEpochMillis: Long,
        expectedZoneId: String,
    ): StartupSnapshotReadResult = try {
        // 不能在锁外先判坏、再锁内删除：另一个进程可能恰好在这段间隙提交新的 base 文件。
        artifactStore.withExclusiveLock {
            readLocked(expectedProfileId, nowEpochMillis, expectedZoneId)
        }
    } catch (_: Throwable) {
        // 锁本身不可用时没有可靠的删除时机，只能 fail-closed 为 cache miss。
        StartupSnapshotReadResult.Missing
    }

    /** 调用方已持有 artifact 锁；所有无效分支在同一临界区内删除。 */
    private fun readLocked(
        expectedProfileId: Long?,
        nowEpochMillis: Long,
        expectedZoneId: String,
    ): StartupSnapshotReadResult {
        var encrypted: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            encrypted = artifactStore.readOrNull() ?: return StartupSnapshotReadResult.Missing
            val key = (keyProvider.getExisting(STARTUP_SNAPSHOT_KEY_ALIAS) as? KeyEncryptionKeyResult.Available)
                ?.key ?: return invalidReadLocked()
            plaintext = StartupSnapshotEnvelope.decrypt(encrypted, key, aad) ?: return invalidReadLocked()
            val snapshot = StartupSnapshotPayloadCodec.decode(plaintext) ?: return invalidReadLocked()
            if (snapshot.validateForStartup(expectedProfileId, nowEpochMillis, expectedZoneId) != StartupSnapshotValidity.VALID) {
                return invalidReadLocked()
            }
            return StartupSnapshotReadResult.Available(snapshot)
        } catch (_: Throwable) {
            return invalidReadLocked()
        } finally {
            encrypted?.fill(0)
            plaintext?.fill(0)
        }
    }

    /** 完整重新加密后通过 AtomicFile 一次替换；旧文件在任意异常下保留。 */
    fun replace(snapshot: StartupSnapshot): Boolean {
        // 在申请/创建 Keystore key 之前完成严格 wire 预检，避免超量输入触发密文或大数组分配。
        if (!StartupSnapshotPayloadCodec.canEncode(snapshot)) return false
        return try {
            artifactStore.withExclusiveLock {
                val key = (keyProvider.getOrCreate(STARTUP_SNAPSHOT_KEY_ALIAS) as? KeyEncryptionKeyResult.Available)
                    ?.key ?: return@withExclusiveLock false
                val encrypted = StartupSnapshotEnvelope.encrypt(snapshot, key, aad)
                try {
                    artifactStore.writeAtomically(encrypted)
                    true
                } finally {
                    encrypted.fill(0)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun invalidate() = invalidateBestEffort()

    private fun invalidReadLocked(): StartupSnapshotReadResult {
        runCatching { artifactStore.deleteAtomically() }
        return StartupSnapshotReadResult.Missing
    }

    private fun invalidateBestEffort() {
        runCatching { artifactStore.withExclusiveLock { artifactStore.deleteAtomically() } }
    }
}

/** 由 Hilt 注入的 data 层 Repository；构造函数只依赖 Application Context，不会打开 Room。 */
class AndroidStartupSnapshotStore(context: Context) : StartupSnapshotEncryptedStore(
    atomicByteStore = AndroidStartupSnapshotArtifactStore(context),
    keyProvider = AndroidKeystoreAesGcmKeyProvider(),
)

/** 明确的二进制 payload 格式；不使用 JSON/Kotlin data class 序列化。 */
object StartupSnapshotPayloadCodec {
    fun encode(snapshot: StartupSnapshot): ByteArray {
        val payloadBytes = checkedPayloadBytes(snapshot)
        return ByteArrayOutputStream(payloadBytes).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(snapshot.protocolVersion)
                output.writeLong(snapshot.profile.id)
                output.writeText(snapshot.profile.uuid, MAX_UUID_BYTES)
                output.writePresence(snapshot.semester != null)
                snapshot.semester?.let { semester ->
                    output.writeLong(semester.id)
                    output.writeLong(semester.profileId)
                    output.writeText(semester.name, MAX_TEXT_BYTES)
                    output.writeLong(semester.startDateEpochMillis)
                    output.writeInt(semester.weekCount)
                }
                snapshot.courses
                    .sortedWith(STARTUP_SNAPSHOT_COURSE_WIRE_ORDER)
                    .also { courses -> output.writeInt(courses.size) }
                    .forEach { course -> output.writeCourse(course) }
                output.writeVisual(snapshot.visualSettings)
                output.writeLong(snapshot.createdAtEpochMillis)
                output.writeLong(snapshot.expiresAtEpochMillis)
                output.writeText(snapshot.zoneId, MAX_ZONE_BYTES)
                output.writeText(snapshot.revision.value, REVISION_HEX_BYTES)
                output.flush()
            }
            bytes.toByteArray()
        }
    }

    /** `replace` 在接触 Keystore 前调用；与 decoder 使用相同 UTF-8 字节上限和总量。 */
    fun canEncode(snapshot: StartupSnapshot): Boolean = runCatching {
        checkedPayloadBytes(snapshot)
    }.isSuccess

    /**
     * 先逐字段计算严格 UTF-8 长度，再允许分配完整 payload。`SnapshotWireSizer` 每次累加都
     * 立即检查 512 KiB 上限，因此 2000 门超长课程不会形成巨大的明文或密文数组。
     */
    private fun checkedPayloadBytes(snapshot: StartupSnapshot): Int {
        require(snapshot.protocolVersion == StartupSnapshot.CURRENT_PROTOCOL_VERSION)
        require(snapshot.isStartupSnapshotSemanticallyValid())
        val sizer = SnapshotWireSizer()
        sizer.raw(MAGIC.size)
        sizer.int()
        sizer.long()
        sizer.text(snapshot.profile.uuid, MAX_UUID_BYTES)
        sizer.presence()
        snapshot.semester?.let { semester ->
            sizer.long()
            sizer.long()
            sizer.text(semester.name, MAX_TEXT_BYTES)
            sizer.long()
            sizer.int()
        }
        sizer.int()
        snapshot.courses.sortedWith(STARTUP_SNAPSHOT_COURSE_WIRE_ORDER).forEach { course ->
            sizer.course(course)
        }
        sizer.visual(snapshot.visualSettings)
        sizer.long()
        sizer.long()
        sizer.text(snapshot.zoneId, MAX_ZONE_BYTES)
        sizer.text(snapshot.revision.value, REVISION_HEX_BYTES)
        require(snapshot.revision.value.matches(REVISION_HEX_REGEX))
        require(snapshot.revision == StartupSnapshotRevision.create(snapshot))
        return sizer.totalBytes
    }

    /** 长度、计数、枚举和字段语义任一不满足时拒绝，不为损坏输入补默认值。 */
    fun decode(bytes: ByteArray): StartupSnapshot? = runCatching {
        if (bytes.size !in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) return null
        val input = StrictSnapshotInput(bytes)
        if (!input.bytes(MAGIC.size).contentEquals(MAGIC)) return null
        if (input.int() != StartupSnapshot.CURRENT_PROTOCOL_VERSION) return null
        val profile = StartupSnapshotProfile(id = input.long(), uuid = input.text(MAX_UUID_BYTES))
        val semester = if (input.presence()) {
            StartupSnapshotSemester(
                id = input.long(),
                profileId = input.long(),
                name = input.text(MAX_TEXT_BYTES),
                startDateEpochMillis = input.long(),
                weekCount = input.int(),
            )
        } else null
        val courseCount = input.count(MAX_COURSES)
        val courses = List(courseCount) { input.course() }
        val visualSettings = input.visual()
        val unsigned = StartupSnapshot(
            protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
            profile = profile,
            semester = semester,
            courses = courses,
            visualSettings = visualSettings,
            createdAtEpochMillis = input.long(),
            expiresAtEpochMillis = input.long(),
            zoneId = input.text(MAX_ZONE_BYTES),
            revision = StartupSnapshotRevision(input.text(REVISION_HEX_BYTES)),
        )
        if (input.remaining() != 0 || !unsigned.isStartupSnapshotSemanticallyValid() ||
            !unsigned.revision.value.matches(REVISION_HEX_REGEX) ||
            unsigned.revision != StartupSnapshotRevision.create(unsigned)
        ) null else unsigned
    }.getOrNull()

    private fun DataOutputStream.writeCourse(course: StartupSnapshotCourse) {
        writeLong(course.id)
        writeText(course.name, MAX_TEXT_BYTES)
        writeText(course.teacher, MAX_TEXT_BYTES)
        writeText(course.location, MAX_TEXT_BYTES)
        writeInt(course.dayOfWeek)
        writeInt(course.startSection)
        writeInt(course.duration)
        writeInt(course.startWeek)
        writeInt(course.endWeek)
        writeInt(course.weekType.toWireCode())
        writeText(course.color, MAX_COLOR_BYTES)
    }

    private fun DataOutputStream.writeVisual(settings: StartupSnapshotVisualSettings) {
        writePresence(settings.dynamicColor)
        writeNullableText(settings.wallpaperUri, MAX_URI_BYTES)
        writeInt(settings.transparency.toRawBits())
        writeInt(settings.fontStyle.toWireCode())
        writeInt(settings.dividerType.toWireCode())
        writeInt(settings.dividerWidthDp.toRawBits())
        writeText(settings.dividerColor, MAX_COLOR_BYTES)
        writeInt(settings.dividerAlpha.toRawBits())
        writeInt(settings.courseItemHeightDp)
        writeInt(settings.maxDailySections)
        writeInt(settings.sectionTimes.size)
        settings.sectionTimes.forEach { section ->
            writeText(section.startTime, MAX_TIME_BYTES)
            writeText(section.endTime, MAX_TIME_BYTES)
        }
        writeInt(settings.cardCornerRadius)
        writeInt(settings.cardAlpha.toRawBits())
        writePresence(settings.showCourseIcons)
        writeInt(settings.wallpaperMode.toWireCode())
        writeInt(settings.themeMode.toWireCode())
        writePresence(settings.showWeekend)
        writePresence(settings.showSidebarTime)
        writePresence(settings.showSidebarIndex)
        writePresence(settings.hideNonThisWeek)
        writePresence(settings.showDateInHeader)
        writeInt(settings.backgroundBlur.toRawBits())
        writeInt(settings.backgroundBrightness.toRawBits())
    }

    private fun DataOutputStream.writePresence(value: Boolean) = writeByte(if (value) 1 else 0)
    private fun DataOutputStream.writeText(value: String, maxBytes: Int) {
        require(value.strictUtf8ByteLength() <= maxBytes)
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }
    private fun DataOutputStream.writeNullableText(value: String?, maxBytes: Int) {
        writePresence(value != null)
        value?.let { text -> writeText(text, maxBytes) }
    }

    private fun StrictSnapshotInput.course(): StartupSnapshotCourse = StartupSnapshotCourse(
        id = long(),
        name = text(MAX_TEXT_BYTES),
        teacher = text(MAX_TEXT_BYTES),
        location = text(MAX_TEXT_BYTES),
        dayOfWeek = int(),
        startSection = int(),
        duration = int(),
        startWeek = int(),
        endWeek = int(),
        weekType = int().toWeekType(),
        color = text(MAX_COLOR_BYTES),
    )

    private fun StrictSnapshotInput.visual(): StartupSnapshotVisualSettings = StartupSnapshotVisualSettings(
        dynamicColor = presence(),
        wallpaperUri = nullableText(MAX_URI_BYTES),
        transparency = Float.fromBits(int()),
        fontStyle = int().toFontStyle(),
        dividerType = int().toDividerType(),
        dividerWidthDp = Float.fromBits(int()),
        dividerColor = text(MAX_COLOR_BYTES),
        dividerAlpha = Float.fromBits(int()),
        courseItemHeightDp = int(),
        maxDailySections = int(),
        sectionTimes = List(count(MAX_SECTION_TIMES)) {
            StartupSnapshotSectionTime(text(MAX_TIME_BYTES), text(MAX_TIME_BYTES))
        },
        cardCornerRadius = int(),
        cardAlpha = Float.fromBits(int()),
        showCourseIcons = presence(),
        wallpaperMode = int().toWallpaperMode(),
        themeMode = int().toThemeMode(),
        showWeekend = presence(),
        showSidebarTime = presence(),
        showSidebarIndex = presence(),
        hideNonThisWeek = presence(),
        showDateInHeader = presence(),
        backgroundBlur = Float.fromBits(int()),
        backgroundBrightness = Float.fromBits(int()),
    )

    private fun StartupSnapshotWeekType.toWireCode(): Int = when (this) {
        StartupSnapshotWeekType.ALL -> 0
        StartupSnapshotWeekType.ODD -> 1
        StartupSnapshotWeekType.EVEN -> 2
    }
    private fun StartupSnapshotFontStyle.toWireCode(): Int = when (this) {
        StartupSnapshotFontStyle.SYSTEM -> 0
        StartupSnapshotFontStyle.SERIF -> 1
        StartupSnapshotFontStyle.MONOSPACE -> 2
    }
    private fun StartupSnapshotDividerType.toWireCode(): Int = when (this) {
        StartupSnapshotDividerType.SOLID -> 0
        StartupSnapshotDividerType.DASHED -> 1
        StartupSnapshotDividerType.DOTTED -> 2
    }
    private fun StartupSnapshotWallpaperMode.toWireCode(): Int = when (this) {
        StartupSnapshotWallpaperMode.CROP -> 0
        StartupSnapshotWallpaperMode.FILL -> 1
    }
    private fun StartupSnapshotThemeMode.toWireCode(): Int = when (this) {
        StartupSnapshotThemeMode.SYSTEM -> 0
        StartupSnapshotThemeMode.LIGHT -> 1
        StartupSnapshotThemeMode.DARK -> 2
    }

    private fun Int.toWeekType(): StartupSnapshotWeekType = when (this) {
        0 -> StartupSnapshotWeekType.ALL
        1 -> StartupSnapshotWeekType.ODD
        2 -> StartupSnapshotWeekType.EVEN
        else -> throw IllegalArgumentException("unknown week type")
    }
    private fun Int.toFontStyle(): StartupSnapshotFontStyle = when (this) {
        0 -> StartupSnapshotFontStyle.SYSTEM
        1 -> StartupSnapshotFontStyle.SERIF
        2 -> StartupSnapshotFontStyle.MONOSPACE
        else -> throw IllegalArgumentException("unknown font style")
    }
    private fun Int.toDividerType(): StartupSnapshotDividerType = when (this) {
        0 -> StartupSnapshotDividerType.SOLID
        1 -> StartupSnapshotDividerType.DASHED
        2 -> StartupSnapshotDividerType.DOTTED
        else -> throw IllegalArgumentException("unknown divider type")
    }
    private fun Int.toWallpaperMode(): StartupSnapshotWallpaperMode = when (this) {
        0 -> StartupSnapshotWallpaperMode.CROP
        1 -> StartupSnapshotWallpaperMode.FILL
        else -> throw IllegalArgumentException("unknown wallpaper mode")
    }
    private fun Int.toThemeMode(): StartupSnapshotThemeMode = when (this) {
        0 -> StartupSnapshotThemeMode.SYSTEM
        1 -> StartupSnapshotThemeMode.LIGHT
        2 -> StartupSnapshotThemeMode.DARK
        else -> throw IllegalArgumentException("unknown theme mode")
    }

    /** 不分配完整 byte array 的 wire 长度计数器；每一步都确认 decoder 同一上限。 */
    private class SnapshotWireSizer {
        var totalBytes: Int = 0
            private set

        fun raw(bytes: Int) {
            require(bytes >= 0)
            totalBytes = Math.addExact(totalBytes, bytes)
            require(totalBytes <= MAX_PAYLOAD_BYTES) { "启动快照 payload 超过 512 KiB" }
        }

        fun int() = raw(Int.SIZE_BYTES)
        fun long() = raw(Long.SIZE_BYTES)
        fun presence() = raw(1)

        fun text(value: String, maxBytes: Int) {
            val encodedBytes = value.strictUtf8ByteLength()
            require(encodedBytes <= maxBytes) { "启动快照文本字段超过 UTF-8 上限" }
            int()
            raw(encodedBytes)
        }

        fun nullableText(value: String?, maxBytes: Int) {
            presence()
            value?.let { text(it, maxBytes) }
        }

        fun course(course: StartupSnapshotCourse) {
            long()
            text(course.name, MAX_TEXT_BYTES)
            text(course.teacher, MAX_TEXT_BYTES)
            text(course.location, MAX_TEXT_BYTES)
            repeat(6) { int() }
            text(course.color, MAX_COLOR_BYTES)
        }

        fun visual(settings: StartupSnapshotVisualSettings) {
            presence()
            nullableText(settings.wallpaperUri, MAX_URI_BYTES)
            // transparency、fontStyle、dividerType、dividerWidth
            repeat(4) { int() }
            text(settings.dividerColor, MAX_COLOR_BYTES)
            // dividerAlpha、courseItemHeight、maxDailySections、sectionTimes count
            repeat(4) { int() }
            settings.sectionTimes.forEach { section ->
                text(section.startTime, MAX_TIME_BYTES)
                text(section.endTime, MAX_TIME_BYTES)
            }
            int()
            int()
            presence()
            int()
            int()
            repeat(5) { presence() }
            int()
            int()
        }
    }

    /** 与严格 decoder 相同的 UTF-8 语义，拒绝未配对 surrogate，且不为长度计算分配数组。 */
    private fun String.strictUtf8ByteLength(): Int {
        var length = 0
        var index = 0
        while (index < this.length) {
            val char = this[index]
            val encodedBytes = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() -> {
                    require(index + 1 < this.length && this[index + 1].isLowSurrogate()) {
                        "启动快照文本包含未配对 high surrogate"
                    }
                    index += 1
                    4
                }
                char.isLowSurrogate() -> throw IllegalArgumentException("启动快照文本包含未配对 low surrogate")
                else -> 3
            }
            length = Math.addExact(length, encodedBytes)
            index += 1
        }
        return length
    }

    /** payload 与 revision 对课程集采用同一显式全字段排序，避免输入列表顺序影响密文。 */
    private val STARTUP_SNAPSHOT_COURSE_WIRE_ORDER = compareBy<StartupSnapshotCourse>(
        { course -> course.id },
        { course -> course.dayOfWeek },
        { course -> course.startSection },
        { course -> course.duration },
        { course -> course.startWeek },
        { course -> course.endWeek },
        { course -> course.weekType.toWireCode() },
        { course -> course.name },
        { course -> course.teacher },
        { course -> course.location },
        { course -> course.color },
    )

    private const val MIN_PAYLOAD_BYTES = 8
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val MAX_UUID_BYTES = 256
    private const val MAX_ZONE_BYTES = 128
    private const val MAX_URI_BYTES = 16 * 1024
    private const val MAX_COLOR_BYTES = 64
    private const val MAX_TIME_BYTES = 32
    private const val REVISION_HEX_BYTES = 64
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())
    private val REVISION_HEX_REGEX = Regex("[0-9a-f]{64}")
}

/** 不可信 payload 的无分配过载保护读取器。 */
private class StrictSnapshotInput(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    fun remaining(): Int = buffer.remaining()
    fun int(): Int = requireRemaining(Int.SIZE_BYTES).let { buffer.int }
    fun long(): Long = requireRemaining(Long.SIZE_BYTES).let { buffer.long }
    fun bytes(length: Int): ByteArray {
        require(length >= 0)
        requireRemaining(length)
        return ByteArray(length).also(buffer::get)
    }
    fun presence(): Boolean = when (requireRemaining(1).let { buffer.get().toInt() }) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("invalid presence")
    }
    fun count(max: Int): Int = int().also { require(it in 0..max) }
    fun text(maxBytes: Int): String {
        val length = int()
        require(length in 0..maxBytes)
        val bytes = bytes(length)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("invalid utf8")
        }
    }
    fun nullableText(maxBytes: Int): String? = if (presence()) text(maxBytes) else null
    private fun requireRemaining(size: Int) {
        require(size >= 0 && buffer.remaining() >= size) { "truncated snapshot" }
    }
}

/** 固定 magic/version/alias/IV/密文长度的 AES-256-GCM envelope。 */
object StartupSnapshotEnvelope {
    const val HEADER_BYTES = 20
    val DEFAULT_AAD: ByteArray = "dawn-course/startup-snapshot/v1".toByteArray(Charsets.UTF_8)

    fun encrypt(snapshot: StartupSnapshot, key: SecretKey, aad: ByteArray): ByteArray {
        val payload = StartupSnapshotPayloadCodec.encode(snapshot)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(payload)
            require(cipher.iv.size == IV_BYTES && ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES)
            return ByteBuffer.allocate(HEADER_BYTES + cipher.iv.size + ciphertext.size).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(ENVELOPE_VERSION)
                .putInt(KEY_ALIAS_VERSION)
                .putInt(cipher.iv.size)
                .putInt(ciphertext.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
        } finally {
            payload.fill(0)
        }
    }

    fun decrypt(bytes: ByteArray, key: SecretKey, aad: ByteArray): ByteArray? = runCatching {
        if (bytes.size !in (HEADER_BYTES + IV_BYTES + GCM_TAG_BYTES)..MAX_ENVELOPE_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(input::get)
        if (!magic.contentEquals(MAGIC) || input.int != ENVELOPE_VERSION || input.int != KEY_ALIAS_VERSION) return null
        val ivLength = input.int
        val ciphertextLength = input.int
        if (ivLength != IV_BYTES || ciphertextLength !in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES ||
            input.remaining() != ivLength + ciphertextLength
        ) return null
        val iv = ByteArray(ivLength).also(input::get)
        val ciphertext = ByteArray(ciphertextLength).also(input::get)
        try {
            Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(aad)
            }.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }.getOrNull()

    private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte())
    private const val ENVELOPE_VERSION = 1
    private const val KEY_ALIAS_VERSION = 1
    private const val IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 512 * 1024 + GCM_TAG_BYTES
    private const val MAX_ENVELOPE_BYTES = HEADER_BYTES + IV_BYTES + MAX_CIPHERTEXT_BYTES
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
}

/** 独立 alias，不能复用数据库口令的 Keystore key。 */
const val STARTUP_SNAPSHOT_KEY_ALIAS = "com.dawncourse.startup.snapshot.key.v1"
