package com.dawncourse.feature.timetable

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    // 与实时课表使用同一背景亮度回调，避免首帧与实时 Root 出现相反的文字对比度。
    var isWallpaperLight by remember(presentation.settings.wallpaperUri) { mutableStateOf(false) }
    val textColor = if (presentation.settings.wallpaperUri.isNullOrBlank()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        if (isWallpaperLight) Color(0xFF1A1A1A) else Color.White
    }
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        TimetableBackground(
            wallpaperUri = presentation.settings.wallpaperUri,
            wallpaperMode = presentation.settings.wallpaperMode,
            backgroundBlur = presentation.settings.backgroundBlur,
            backgroundBrightness = presentation.settings.backgroundBrightness,
            transparency = presentation.settings.transparency,
            onWallpaperLightChanged = { isWallpaperLight = it },
        )

        when (presentation.mode) {
            StartupTimetablePresentationMode.NoSemester -> {
                StartupNoSemesterView(
                    modifier = Modifier.fillMaxSize(),
                    textColor = textColor,
                )
            }

            StartupTimetablePresentationMode.BeforeSemester,
            StartupTimetablePresentationMode.AfterSemester,
            -> {
                HolidayView(
                    modifier = Modifier.fillMaxSize(),
                    isBeforeSemesterStart = presentation.mode == StartupTimetablePresentationMode.BeforeSemester,
                    daysUntilSemesterStart = presentation.daysUntilSemesterStart,
                )
            }

            StartupTimetablePresentationMode.InSemester -> {
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
}

/** 未选择学期与“尚未开学”是不同状态，必须避免误导用户。 */
@Composable
private fun StartupNoSemesterView(
    modifier: Modifier,
    textColor: Color,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "尚未设置学期",
                style = MaterialTheme.typography.titleLarge,
                color = textColor,
            )
            Text(
                text = "数据库就绪后可在设置中创建或选择学期",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
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
    val mode: StartupTimetablePresentationMode,
)

/** 快照是静态投影；学期缺失不能借用开学前或学期结束的假期语义。 */
enum class StartupTimetablePresentationMode {
    NoSemester,
    BeforeSemester,
    InSemester,
    AfterSemester,
}

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
    val mode = when {
        semesterStart == null -> StartupTimetablePresentationMode.NoSemester
        today.isBefore(semesterStart) -> StartupTimetablePresentationMode.BeforeSemester
        rawWeek > totalWeeks -> StartupTimetablePresentationMode.AfterSemester
        else -> StartupTimetablePresentationMode.InSemester
    }

    return StartupTimetablePresentation(
        settings = visualSettings.toAppSettings(),
        courses = courses.map { course -> course.toCourse(semester?.id ?: 0L) },
        currentWeek = if (mode == StartupTimetablePresentationMode.NoSemester) -1 else rawWeek,
        totalWeeks = totalWeeks,
        semesterStartDate = semesterStart,
        daysUntilSemesterStart = daysUntilStart,
        mode = mode,
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
