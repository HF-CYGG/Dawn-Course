package com.dawncourse.core.domain.model

/**
 * 从已稳定的实时课表聚合构造启动快照。
 *
 * 这个工厂只投影首帧和 Widget 消费的字段；调用方负责在非主线程调用并持久化。没有学期
 * 时不会把任何课程写入快照，以免跨课表或跨学期混入首帧。
 */
fun createStartupSnapshot(
    activeContext: ActiveTimetableContext,
    courses: List<Course>,
    settings: AppSettings,
    createdAtEpochMillis: Long,
    zoneId: String,
): StartupSnapshot {
    val semester = activeContext.semester
    val projectedCourses = if (semester == null) {
        emptyList()
    } else {
        courses
            .asSequence()
            .filter { course -> course.semesterId == semester.id }
            .map { course -> course.toStartupSnapshotCourse() }
            .sortedWith(STARTUP_SNAPSHOT_COURSE_ORDER)
            .toList()
    }
    val unsigned = StartupSnapshot(
        protocolVersion = StartupSnapshot.CURRENT_PROTOCOL_VERSION,
        profile = StartupSnapshotProfile(
            id = activeContext.profile.id,
            uuid = activeContext.profile.uuid,
        ),
        semester = semester?.let { value ->
            StartupSnapshotSemester(
                id = value.id,
                profileId = value.profileId,
                name = value.name,
                startDateEpochMillis = value.startDate,
                weekCount = value.weekCount,
            )
        },
        courses = projectedCourses,
        visualSettings = settings.toStartupSnapshotVisualSettings(),
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = createdAtEpochMillis + StartupSnapshot.TTL_MILLIS,
        zoneId = zoneId,
        revision = StartupSnapshotRevision("pending"),
    )
    return unsigned.copy(revision = StartupSnapshotRevision.create(unsigned))
}

private fun Course.toStartupSnapshotCourse(): StartupSnapshotCourse = StartupSnapshotCourse(
    id = id,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    duration = duration,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = when (weekType) {
        Course.WEEK_TYPE_ALL -> StartupSnapshotWeekType.ALL
        Course.WEEK_TYPE_ODD -> StartupSnapshotWeekType.ODD
        Course.WEEK_TYPE_EVEN -> StartupSnapshotWeekType.EVEN
        else -> throw IllegalArgumentException("unsupported course week type: $weekType")
    },
    color = color,
)

private fun AppSettings.toStartupSnapshotVisualSettings(): StartupSnapshotVisualSettings =
    StartupSnapshotVisualSettings(
        dynamicColor = dynamicColor,
        wallpaperUri = wallpaperUri,
        transparency = transparency,
        fontStyle = when (fontStyle) {
            AppFontStyle.SYSTEM -> StartupSnapshotFontStyle.SYSTEM
            AppFontStyle.SERIF -> StartupSnapshotFontStyle.SERIF
            AppFontStyle.MONOSPACE -> StartupSnapshotFontStyle.MONOSPACE
        },
        dividerType = when (dividerType) {
            DividerType.SOLID -> StartupSnapshotDividerType.SOLID
            DividerType.DASHED -> StartupSnapshotDividerType.DASHED
            DividerType.DOTTED -> StartupSnapshotDividerType.DOTTED
        },
        dividerWidthDp = dividerWidthDp,
        dividerColor = dividerColor,
        dividerAlpha = dividerAlpha,
        courseItemHeightDp = courseItemHeightDp,
        maxDailySections = maxDailySections,
        sectionTimes = sectionTimes.map { section ->
            StartupSnapshotSectionTime(section.startTime, section.endTime)
        },
        cardCornerRadius = cardCornerRadius,
        cardAlpha = cardAlpha,
        showCourseIcons = showCourseIcons,
        wallpaperMode = when (wallpaperMode) {
            WallpaperMode.CROP -> StartupSnapshotWallpaperMode.CROP
            WallpaperMode.FILL -> StartupSnapshotWallpaperMode.FILL
        },
        themeMode = when (themeMode) {
            AppThemeMode.SYSTEM -> StartupSnapshotThemeMode.SYSTEM
            AppThemeMode.LIGHT -> StartupSnapshotThemeMode.LIGHT
            AppThemeMode.DARK -> StartupSnapshotThemeMode.DARK
        },
        showWeekend = showWeekend,
        showSidebarTime = showSidebarTime,
        showSidebarIndex = showSidebarIndex,
        hideNonThisWeek = hideNonThisWeek,
        showDateInHeader = showDateInHeader,
        backgroundBlur = backgroundBlur,
        backgroundBrightness = backgroundBrightness,
    )

private val STARTUP_SNAPSHOT_COURSE_ORDER = compareBy<StartupSnapshotCourse>(
    { course -> course.id },
    { course -> course.dayOfWeek },
    { course -> course.startSection },
    { course -> course.duration },
    { course -> course.startWeek },
    { course -> course.endWeek },
    { course -> course.weekType.sortCode() },
    { course -> course.name },
    { course -> course.teacher },
    { course -> course.location },
    { course -> course.color },
)

private fun StartupSnapshotWeekType.sortCode(): Int = when (this) {
    StartupSnapshotWeekType.ALL -> 0
    StartupSnapshotWeekType.ODD -> 1
    StartupSnapshotWeekType.EVEN -> 2
}
