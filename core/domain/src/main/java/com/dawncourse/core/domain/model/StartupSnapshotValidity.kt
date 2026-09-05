package com.dawncourse.core.domain.model

/** 读取前可纯函数校验的 cache-miss 条件。 */
enum class StartupSnapshotValidity {
    VALID,
    UNKNOWN_PROTOCOL,
    PROFILE_MISMATCH,
    EXPIRED,
    FUTURE,
    ZONE_MISMATCH,
    INVALID_SEMANTICS,
    REVISION_MISMATCH,
}

/**
 * 数据层在任何失败下都只能返回 cache miss，不能阻断数据库恢复。
 *
 * `expectedProfileId` 必须来自独立于 Room 的持久选择事实；因此这里不会、也不能通过
 * Repository 反查数据库来证明快照仍属当前 Profile。
 */
fun StartupSnapshot.validateForStartup(
    expectedProfileId: Long?,
    nowEpochMillis: Long,
    expectedZoneId: String,
): StartupSnapshotValidity = when {
    protocolVersion != StartupSnapshot.CURRENT_PROTOCOL_VERSION -> StartupSnapshotValidity.UNKNOWN_PROTOCOL
    expectedProfileId == null || profile.id != expectedProfileId -> StartupSnapshotValidity.PROFILE_MISMATCH
    zoneId != expectedZoneId -> StartupSnapshotValidity.ZONE_MISMATCH
    createdAtEpochMillis > nowEpochMillis -> StartupSnapshotValidity.FUTURE
    expiresAtEpochMillis <= nowEpochMillis -> StartupSnapshotValidity.EXPIRED
    expiresAtEpochMillis <= createdAtEpochMillis || !isStartupSnapshotSemanticallyValid() -> {
        StartupSnapshotValidity.INVALID_SEMANTICS
    }
    revision != StartupSnapshotRevision.create(this) -> StartupSnapshotValidity.REVISION_MISMATCH
    else -> StartupSnapshotValidity.VALID
}

/** 当前协议的领域语义边界；二进制 codec 也必须在解码时重复拒绝不可信输入。 */
fun StartupSnapshot.isStartupSnapshotSemanticallyValid(): Boolean {
    if (profile.id <= 0L || profile.uuid.isBlank() || zoneId.isBlank()) return false
    val currentSemester = semester
    // 课程投影必须锚定活动学期；否则渲染层只能使用哨兵 ID，可能在首帧混入跨学期内容。
    if (currentSemester == null && courses.isNotEmpty()) return false
    if (currentSemester != null && (
            currentSemester.id <= 0L || currentSemester.profileId != profile.id ||
                currentSemester.name.isBlank() || currentSemester.weekCount !in 1..MAX_WEEKS
        )
    ) return false
    if (courses.size > MAX_COURSES || visualSettings.sectionTimes.size > MAX_SECTION_TIMES) return false
    if (courses.any { course ->
            course.id <= 0L || course.name.isBlank() || course.dayOfWeek !in 1..7 ||
                course.startSection !in 1..MAX_SECTIONS || course.duration !in 1..MAX_SECTIONS ||
                course.startWeek !in 1..MAX_WEEKS || course.endWeek !in course.startWeek..MAX_WEEKS
        }
    ) return false
    return visualSettings.transparency.isFinite() && visualSettings.transparency in 0f..1f &&
        visualSettings.dividerWidthDp.isFinite() && visualSettings.dividerWidthDp in 0f..16f &&
        visualSettings.dividerAlpha.isFinite() && visualSettings.dividerAlpha in 0f..1f &&
        visualSettings.courseItemHeightDp in 24..160 &&
        visualSettings.maxDailySections in 1..MAX_SECTIONS &&
        visualSettings.cardCornerRadius in 0..64 &&
        visualSettings.cardAlpha.isFinite() && visualSettings.cardAlpha in 0f..1f &&
        visualSettings.backgroundBlur.isFinite() && visualSettings.backgroundBlur in 0f..100f &&
        visualSettings.backgroundBrightness.isFinite() && visualSettings.backgroundBrightness in 0f..1f &&
        visualSettings.sectionTimes.all { it.startTime.isNotBlank() && it.endTime.isNotBlank() }
}

/** 领域层公开的合理上限，避免任何调用方构造过大快照。 */
const val MAX_COURSES = 2_000
const val MAX_SECTION_TIMES = 64
const val MAX_SECTIONS = 64
const val MAX_WEEKS = 53
