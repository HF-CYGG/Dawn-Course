package com.dawncourse.core.domain.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * 冷启动首帧可使用的最小、版本化课表投影。
 *
 * 它不是 Room 的备份，也不包含备注、来源关联、凭据、WebDAV 配置或诊断信息；数据库
 * Ready 后必须整体切换回实时数据。`revision` 是其余持久字段的确定性摘要，故不能将
 * 自身再次纳入摘要输入。
 */
data class StartupSnapshot(
    val protocolVersion: Int,
    val profile: StartupSnapshotProfile,
    val semester: StartupSnapshotSemester?,
    val courses: List<StartupSnapshotCourse>,
    val visualSettings: StartupSnapshotVisualSettings,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val zoneId: String,
    val revision: StartupSnapshotRevision,
) {
    companion object {
        /** 当前二进制与领域协议版本。 */
        const val CURRENT_PROTOCOL_VERSION = 1

        /** 启动加速最多保留七天，过期后只能回退数据库实时路径。 */
        const val TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/** 以 DataStore `active_profile_id` 可复核的活动课表身份；uuid 用于跨设备身份追踪。 */
data class StartupSnapshotProfile(
    val id: Long,
    val uuid: String,
)

/** 活动学期的首帧与 Widget 所需最小身份和周次信息。 */
data class StartupSnapshotSemester(
    val id: Long,
    val profileId: Long,
    val name: String,
    val startDateEpochMillis: Long,
    val weekCount: Int,
)

/** 首帧网格和 Widget 实际使用的课程投影；故意不包含 note、originId、isModified。 */
data class StartupSnapshotCourse(
    val id: Long,
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val duration: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: StartupSnapshotWeekType,
    val color: String,
)

/** 快照中可显示的周次类型，禁止依赖 Room 整数或 enum ordinal。 */
enum class StartupSnapshotWeekType {
    ALL,
    ODD,
    EVEN,
}

/** 首帧课表实际使用的视觉设置与 Widget 所需节次时间投影。 */
data class StartupSnapshotVisualSettings(
    val dynamicColor: Boolean,
    val wallpaperUri: String?,
    val transparency: Float,
    val fontStyle: StartupSnapshotFontStyle,
    val dividerType: StartupSnapshotDividerType,
    val dividerWidthDp: Float,
    val dividerColor: String,
    val dividerAlpha: Float,
    val courseItemHeightDp: Int,
    val maxDailySections: Int,
    val sectionTimes: List<StartupSnapshotSectionTime>,
    val cardCornerRadius: Int,
    val cardAlpha: Float,
    val showCourseIcons: Boolean,
    val wallpaperMode: StartupSnapshotWallpaperMode,
    val themeMode: StartupSnapshotThemeMode,
    val showWeekend: Boolean,
    val showSidebarTime: Boolean,
    val showSidebarIndex: Boolean,
    val hideNonThisWeek: Boolean,
    val showDateInHeader: Boolean,
    val backgroundBlur: Float,
    val backgroundBrightness: Float,
)

/** 节次时间按列表下标表达节次，顺序本身属于渲染语义。 */
data class StartupSnapshotSectionTime(
    val startTime: String,
    val endTime: String,
)

enum class StartupSnapshotFontStyle { SYSTEM, SERIF, MONOSPACE }
enum class StartupSnapshotDividerType { SOLID, DASHED, DOTTED }
enum class StartupSnapshotWallpaperMode { CROP, FILL }
enum class StartupSnapshotThemeMode { SYSTEM, LIGHT, DARK }

/** 已持久化的快照摘要；值是小写 SHA-256 十六进制字符串。 */
data class StartupSnapshotRevision(val value: String) {
    companion object {
        /**
         * 使用明确的字段顺序、长度前缀和枚举 wire code 编码所有非派生持久字段。
         * 不复用 `hashCode()`、data class 序列化或 `ScheduleRevision`，以免演进时静默漏字段。
         */
        fun create(snapshot: StartupSnapshot): StartupSnapshotRevision {
            val bytes = StartupSnapshotCanonicalEncoder.encode(snapshot)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return StartupSnapshotRevision(digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            })
        }
    }
}

/**
 * 与 [StartupSnapshotRevision] 同源、但剔除了创建与过期时间的内容指纹。
 *
 * [StartupSnapshotRevision] 必须覆盖全部持久字段（含时间戳）才能充当读取时的完整性校验，
 * 因此两份内容完全相同、只是生成时刻不同的快照，其 revision 必然不同，不能用于判重。
 * 写入方需要的是"课表与视觉设置是否真的变了"，故此处把两个时间字段归零后复用同一编码器。
 */
fun StartupSnapshot.contentIdentity(): StartupSnapshotRevision = StartupSnapshotRevision.create(
    copy(createdAtEpochMillis = 0L, expiresAtEpochMillis = 0L),
)

/** 版本摘要使用的私有 canonical wire encoder。 */
private object StartupSnapshotCanonicalEncoder {
    fun encode(snapshot: StartupSnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(snapshot.protocolVersion)
            output.writeLong(snapshot.profile.id)
            output.writeText(snapshot.profile.uuid)
            output.writeBoolean(snapshot.semester != null)
            snapshot.semester?.let { semester ->
                output.writeLong(semester.id)
                output.writeLong(semester.profileId)
                output.writeText(semester.name)
                output.writeLong(semester.startDateEpochMillis)
                output.writeInt(semester.weekCount)
            }
            snapshot.courses
                .sortedWith(STARTUP_COURSE_COMPARATOR)
                .also { courses -> output.writeInt(courses.size) }
                .forEach { course ->
                    output.writeLong(course.id)
                    output.writeText(course.name)
                    output.writeText(course.teacher)
                    output.writeText(course.location)
                    output.writeInt(course.dayOfWeek)
                    output.writeInt(course.startSection)
                    output.writeInt(course.duration)
                    output.writeInt(course.startWeek)
                    output.writeInt(course.endWeek)
                    output.writeInt(course.weekType.wireCode())
                    output.writeText(course.color)
                }
            output.writeVisualSettings(snapshot.visualSettings)
            output.writeLong(snapshot.createdAtEpochMillis)
            output.writeLong(snapshot.expiresAtEpochMillis)
            output.writeText(snapshot.zoneId)
            output.flush()
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeVisualSettings(settings: StartupSnapshotVisualSettings) {
        writeBoolean(settings.dynamicColor)
        writeNullableText(settings.wallpaperUri)
        writeInt(settings.transparency.toRawBits())
        writeInt(settings.fontStyle.wireCode())
        writeInt(settings.dividerType.wireCode())
        writeInt(settings.dividerWidthDp.toRawBits())
        writeText(settings.dividerColor)
        writeInt(settings.dividerAlpha.toRawBits())
        writeInt(settings.courseItemHeightDp)
        writeInt(settings.maxDailySections)
        writeInt(settings.sectionTimes.size)
        settings.sectionTimes.forEach { section ->
            writeText(section.startTime)
            writeText(section.endTime)
        }
        writeInt(settings.cardCornerRadius)
        writeInt(settings.cardAlpha.toRawBits())
        writeBoolean(settings.showCourseIcons)
        writeInt(settings.wallpaperMode.wireCode())
        writeInt(settings.themeMode.wireCode())
        writeBoolean(settings.showWeekend)
        writeBoolean(settings.showSidebarTime)
        writeBoolean(settings.showSidebarIndex)
        writeBoolean(settings.hideNonThisWeek)
        writeBoolean(settings.showDateInHeader)
        writeInt(settings.backgroundBlur.toRawBits())
        writeInt(settings.backgroundBrightness.toRawBits())
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        value?.let { text -> writeText(text) }
    }

    private fun StartupSnapshotWeekType.wireCode(): Int = when (this) {
        StartupSnapshotWeekType.ALL -> 0
        StartupSnapshotWeekType.ODD -> 1
        StartupSnapshotWeekType.EVEN -> 2
    }

    private fun StartupSnapshotFontStyle.wireCode(): Int = when (this) {
        StartupSnapshotFontStyle.SYSTEM -> 0
        StartupSnapshotFontStyle.SERIF -> 1
        StartupSnapshotFontStyle.MONOSPACE -> 2
    }

    private fun StartupSnapshotDividerType.wireCode(): Int = when (this) {
        StartupSnapshotDividerType.SOLID -> 0
        StartupSnapshotDividerType.DASHED -> 1
        StartupSnapshotDividerType.DOTTED -> 2
    }

    private fun StartupSnapshotWallpaperMode.wireCode(): Int = when (this) {
        StartupSnapshotWallpaperMode.CROP -> 0
        StartupSnapshotWallpaperMode.FILL -> 1
    }

    private fun StartupSnapshotThemeMode.wireCode(): Int = when (this) {
        StartupSnapshotThemeMode.SYSTEM -> 0
        StartupSnapshotThemeMode.LIGHT -> 1
        StartupSnapshotThemeMode.DARK -> 2
    }

    private val STARTUP_COURSE_COMPARATOR = compareBy<StartupSnapshotCourse>(
        { it.id }, { it.dayOfWeek }, { it.startSection }, { it.duration }, { it.startWeek },
        { it.endWeek }, { it.weekType.wireCode() }, { it.name }, { it.teacher }, { it.location }, { it.color },
    )
}
