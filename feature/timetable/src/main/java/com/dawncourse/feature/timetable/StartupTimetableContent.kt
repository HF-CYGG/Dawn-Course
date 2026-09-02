package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dawncourse.core.domain.model.AppFontStyle
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.model.StartupSnapshotCourse
import com.dawncourse.core.domain.model.StartupSnapshotDividerType
import com.dawncourse.core.domain.model.StartupSnapshotFontStyle
import com.dawncourse.core.domain.model.StartupSnapshotThemeMode
import com.dawncourse.core.domain.model.StartupSnapshotVisualSettings
import com.dawncourse.core.domain.model.StartupSnapshotWallpaperMode
import com.dawncourse.core.domain.model.StartupSnapshotWeekType
import com.dawncourse.core.domain.model.WallpaperMode
import com.dawncourse.core.ui.theme.LocalWallpaperContrastColor
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * 数据库尚未 Ready 时的纯首帧课表内容。
 *
 * 调用者只需提供经过完整性和身份校验的 [StartupSnapshot]；此组件没有导航、ViewModel
 * 或数据库入口。它以静态、不可操作的课表画面占位，数据库 Ready 后由应用根整体替换。
 */
@Composable
fun StartupTimetableContent(
    snapshot: StartupSnapshot,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val presentation = remember(snapshot.revision, today) {
        snapshot.toStartupTimetablePresentation(today)
    }
    val textColor = LocalWallpaperContrastColor.current.let { contrast ->
        if (contrast == Color.Unspecified) MaterialTheme.colorScheme.onSurface else contrast
    }
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 1f - presentation.settings.transparency)),
    ) {
        if (presentation.currentWeek !in 1..presentation.totalWeeks) {
            HolidayView(
                modifier = Modifier.fillMaxSize(),
                isBeforeSemesterStart = presentation.currentWeek == 0,
                daysUntilSemesterStart = presentation.daysUntilSemesterStart,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(top = 20.dp)) {
                Text(
                    text = "第 ${presentation.currentWeek} 周",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = textColor,
                )
                WeekHeader(
                    isCurrentWeek = true,
                    displayedWeek = presentation.currentWeek,
                    semesterStartDate = presentation.semesterStartDate,
                    textColor = textColor,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        // 保持版面完整但不接受滚动手势；快照不承担实时交互职责。
                        .verticalScroll(scrollState, enabled = false),
                ) {
                    TimeColumnIndicator(textColor = textColor)
                    TimetableGrid(
                        courses = presentation.courses,
                        currentWeek = presentation.currentWeek,
                        modifier = Modifier.weight(1f),
                        interactive = false,
                        onCourseClick = {},
                    )
                }
            }
        }
    }
}

/** 快照渲染所需的纯数据，计算在 remember 之外也可被 JVM 测试覆盖。 */
data class StartupTimetablePresentation(
    val settings: AppSettings,
    val courses: List<Course>,
    val currentWeek: Int,
    val totalWeeks: Int,
    val semesterStartDate: LocalDate?,
    val daysUntilSemesterStart: Long?,
)

fun StartupSnapshot.toStartupTimetablePresentation(today: LocalDate): StartupTimetablePresentation {
    val zone = ZoneId.of(zoneId)
    val semesterStart = semester?.startDateEpochMillis
        ?.let { epoch -> java.time.Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate() }
    val rawWeek = semesterStart?.let { start ->
        if (today.isBefore(start)) 0 else ChronoUnit.DAYS.between(start, today).div(7).toInt() + 1
    } ?: 0
    val totalWeeks = max(
        semester?.weekCount ?: 0,
        courses.maxOfOrNull { course -> course.endWeek } ?: 0,
    ).coerceIn(1, 53)
    val daysUntilStart = semesterStart
        ?.takeIf { start -> today.isBefore(start) }
        ?.let { start -> ChronoUnit.DAYS.between(today, start) }

    return StartupTimetablePresentation(
        settings = visualSettings.toAppSettings(),
        courses = courses.map { course -> course.toCourse(semester?.id ?: 0L) },
        currentWeek = rawWeek,
        totalWeeks = totalWeeks,
        semesterStartDate = semesterStart,
        daysUntilSemesterStart = daysUntilStart,
    )
}

fun StartupSnapshotVisualSettings.toAppSettings(): AppSettings = AppSettings(
    dynamicColor = dynamicColor,
    wallpaperUri = wallpaperUri,
    transparency = transparency,
    fontStyle = when (fontStyle) {
        StartupSnapshotFontStyle.SYSTEM -> AppFontStyle.SYSTEM
        StartupSnapshotFontStyle.SERIF -> AppFontStyle.SERIF
        StartupSnapshotFontStyle.MONOSPACE -> AppFontStyle.MONOSPACE
    },
    dividerType = when (dividerType) {
        StartupSnapshotDividerType.SOLID -> DividerType.SOLID
        StartupSnapshotDividerType.DASHED -> DividerType.DASHED
        StartupSnapshotDividerType.DOTTED -> DividerType.DOTTED
    },
    dividerWidthDp = dividerWidthDp,
    dividerColor = dividerColor,
    dividerAlpha = dividerAlpha,
    courseItemHeightDp = courseItemHeightDp,
    maxDailySections = maxDailySections,
    sectionTimes = sectionTimes.map { section -> SectionTime(section.startTime, section.endTime) },
    cardCornerRadius = cardCornerRadius,
    cardAlpha = cardAlpha,
    showCourseIcons = showCourseIcons,
    wallpaperMode = when (wallpaperMode) {
        StartupSnapshotWallpaperMode.CROP -> WallpaperMode.CROP
        StartupSnapshotWallpaperMode.FILL -> WallpaperMode.FILL
    },
    themeMode = when (themeMode) {
        StartupSnapshotThemeMode.SYSTEM -> AppThemeMode.SYSTEM
        StartupSnapshotThemeMode.LIGHT -> AppThemeMode.LIGHT
        StartupSnapshotThemeMode.DARK -> AppThemeMode.DARK
    },
    showWeekend = showWeekend,
    showSidebarTime = showSidebarTime,
    showSidebarIndex = showSidebarIndex,
    hideNonThisWeek = hideNonThisWeek,
    showDateInHeader = showDateInHeader,
    backgroundBlur = backgroundBlur,
    backgroundBrightness = backgroundBrightness,
)

private fun StartupSnapshotCourse.toCourse(semesterId: Long): Course = Course(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    duration = duration,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = when (weekType) {
        StartupSnapshotWeekType.ALL -> Course.WEEK_TYPE_ALL
        StartupSnapshotWeekType.ODD -> Course.WEEK_TYPE_ODD
        StartupSnapshotWeekType.EVEN -> Course.WEEK_TYPE_EVEN
    },
    color = color,
)
