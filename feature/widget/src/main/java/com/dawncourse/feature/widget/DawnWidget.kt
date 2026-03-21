package com.dawncourse.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.size
import androidx.glance.layout.ColumnScope
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import androidx.glance.text.TextAlign
import com.dawncourse.core.domain.model.SectionTime
import java.time.format.DateTimeFormatter

// 莫兰迪/马卡龙色系 (Day) / 深色适配 (Night)
private val WidgetCourseColors = listOf(
    ColorProvider(day = Color(0xFFE8DEF8), night = Color(0xFF4A4458)), // 浅紫 -> 深灰紫
    ColorProvider(day = Color(0xFFC4E7FF), night = Color(0xFF004A77)), // 浅蓝 -> 深蓝
    ColorProvider(day = Color(0xFFC3EED0), night = Color(0xFF0F5223)), // 浅绿 -> 深绿
    ColorProvider(day = Color(0xFFFDE2E4), night = Color(0xFF8C1D18)), // 浅粉 -> 深红
    ColorProvider(day = Color(0xFFFFF4DE), night = Color(0xFF5C4F00)), // 浅黄 -> 深黄
    ColorProvider(day = Color(0xFFE1E0FF), night = Color(0xFF303FA2))  // 淡靛 -> 深靛
)

// Widget Semantic Colors (Day/Night)
private object WidgetColors {
    val Background = ColorProvider(day = Color.White, night = Color(0xFF1C1B1F))
    val Surface = ColorProvider(day = Color.White, night = Color(0xFF1C1B1F))
    val SurfaceVariant = ColorProvider(day = Color(0xFFF3F3F3), night = Color(0xFF2B2930)) // Slightly lighter than background for cards
    
    val Primary = ColorProvider(day = Color(0xFF6750A4), night = Color(0xFFD0BCFF))
    val OnPrimary = ColorProvider(day = Color.White, night = Color(0xFF381E72))
    
    val PrimaryContainer = ColorProvider(day = Color(0xFFEADDFF), night = Color(0xFF4F378B))
    val OnPrimaryContainer = ColorProvider(day = Color(0xFF21005D), night = Color(0xFFEADDFF))

    val TextPrimary = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
    val TextSecondary = ColorProvider(day = Color(0xFF49454F), night = Color(0xFFCAC4D0))
    val TextTertiary = ColorProvider(day = Color(0xFF79747E), night = Color(0xFF938F99))
    
    val Divider = ColorProvider(day = Color(0xFFE0E0E0), night = Color(0xFF49454F))
    val DateBadgeBackground = ColorProvider(day = Color(0xFFF1F3F4), night = Color(0xFF3C4043))
    val ActiveBackground = ColorProvider(day = Color(0xFFFFF0F0), night = Color(0xFF3A2828))
    val ActiveTextPrimary = ColorProvider(day = Color(0xFF1A1A1A), night = Color.White)
    val ActiveTextSecondary = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFFAAAAAA))
    val UpcomingText = ColorProvider(day = Color(0xFF3C4043), night = Color(0xFFE8EAED))
    val UpcomingSubText = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFFAAAAAA))
    
    val IconTint = TextSecondary // Icons follow secondary text color usually
}

/**
 * 桌面小组件 (Widget) 主入口
 *
 * 使用 Jetpack Glance 构建。
 * 负责展示当天的课程信息，支持多尺寸响应式布局。
 *
 * 主要功能：
 * 1. 获取当前学期、设置和今日课程数据
 * 2. 根据 Widget 尺寸自动切换布局 (FocusCourseItem, CourseListLayout)
 * 3. 过滤非当前周次或非当日的课程
 */
class DawnWidget : GlanceAppWidget() {

    companion object {
        private val SMALL_SQUARE = DpSize(100.dp, 100.dp)       // 1x1
        private val HORIZONTAL_RECTANGLE = DpSize(240.dp, 70.dp) // 4x1, 3x1 (Height < 100dp)
        private val VERTICAL_RECTANGLE = DpSize(140.dp, 200.dp) // 2x3, 2x4 (Width ~140-160, Height > 200)
        private val BIG_SQUARE = DpSize(250.dp, 250.dp)         // 3x3, 4x4
    }

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.courseRepository()
        val semesterRepository = entryPoint.semesterRepository()
        val settingsRepository = entryPoint.settingsRepository()

        val today = LocalDate.now()
        
        val semester = withContext(Dispatchers.IO) {
            semesterRepository.getCurrentSemester().first()
        }

        val settings = withContext(Dispatchers.IO) {
            settingsRepository.settings.first()
        }
        val sectionTimes = settings.sectionTimes

        val termStartDate = semester?.let {
            Instant.ofEpochMilli(it.startDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
        // 是否处于开学前（假期中）
        val isBeforeSemesterStart = termStartDate != null && today.isBefore(termStartDate)
        // 距离开学还有多少天（开学前才有意义）
        val daysUntilSemesterStart = if (isBeforeSemesterStart && termStartDate != null) {
            ChronoUnit.DAYS.between(today, termStartDate)
        } else {
            null
        }
        val currentWeek = if (semester != null && termStartDate != null && !isBeforeSemesterStart) {
            val daysDiff = ChronoUnit.DAYS.between(termStartDate, today)
            (daysDiff / 7).toInt() + 1
        } else {
            0 // 未开学或无学期时不显示周次
        }
        
        val isSemesterEnded = semester != null && !isBeforeSemesterStart && currentWeek > semester.weekCount
        
        val currentDayOfWeek = today.dayOfWeek.value // 1 (Mon) - 7 (Sun)

        val allCourses = withContext(Dispatchers.IO) {
            if (semester != null) {
                repository.getCoursesBySemester(semester.id).first()
            } else {
                emptyList()
            }
        }

        val courses = if (isSemesterEnded || isBeforeSemesterStart) {
            emptyList()
        } else {
            allCourses.filter { course ->
                // 1. 匹配星期
                if (course.dayOfWeek != currentDayOfWeek) return@filter false
                
                // 2. 匹配周次范围
                if (currentWeek < course.startWeek || currentWeek > course.endWeek) return@filter false
                
                // 3. 匹配单双周
                when (course.weekType) {
                    Course.WEEK_TYPE_ALL -> true
                    Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
                    Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
                    else -> true
                }
            }.groupBy { "${it.startSection}-${it.name}" } // 临时修复：去重逻辑
             .map { (_, courses) ->
                 // 如果同一时间有同名课程（例如数据库中有重复条目），优先保留有地点的那个
                 // groupBy 的 value 理论上不会为空，但仍做兜底，避免异常数据导致崩溃。
                 courses.maxByOrNull { if (it.location.isNotBlank()) 1 else 0 } ?: courses.first()
             }
             .sortedBy { it.startSection }
        }

        // 计算无课提示语
        val emptyMessage = if (courses.isNotEmpty()) {
            ""
        } else {
            if (isBeforeSemesterStart) {
                if (daysUntilSemesterStart != null) {
                    if (daysUntilSemesterStart == 0L) {
                        "明天就要接受知识的洗礼了"
                    } else {
                        "距开学还有 ${daysUntilSemesterStart} 天"
                    }
                } else {
                    "还未开学哦"
                }
            } else if (isSemesterEnded) {
                "学期已结束 🎉"
            } else {
                val hasCourseThisWeek = allCourses.any { course ->
                    if (currentWeek < course.startWeek || currentWeek > course.endWeek) return@any false
                    when (course.weekType) {
                        Course.WEEK_TYPE_ALL -> true
                        Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
                        Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
                        else -> true
                    }
                }
    
                if (hasCourseThisWeek) {
                    "今日已无课 ☕"
                } else {
                    val hasFutureCourses = allCourses.any { it.endWeek > currentWeek }
                    if (hasFutureCourses) {
                        "本周无课 🌴"
                    } else {
                        "好好享受假期吧 🎉"
                    }
                }
            }
        }
        
        val now = LocalTime.now()
        val displayCourses = if (courses.isEmpty()) {
            courses
        } else {
            courses.filter { course ->
                isCourseCurrentOrFuture(course, sectionTimes, now)
            }
        }
        val finalEmptyMessage = if (displayCourses.isNotEmpty()) {
            ""
        } else if (courses.isNotEmpty()) {
            "今日课程已结束 🌙"
        } else {
            emptyMessage
        }
        val nextUpdateMillis = computeNextCourseEndMillis(courses, sectionTimes, today, now)
        if (nextUpdateMillis != null) {
            WidgetSyncManager.scheduleNextCourseUpdate(context, nextUpdateMillis)
        }

        provideContent {
            GlanceTheme {
                TimetableWidgetContent(displayCourses, today, currentWeek, sectionTimes, finalEmptyMessage, isBeforeSemesterStart)
            }
        }
    }

    /**
     * Hilt EntryPoint 用于在 GlanceAppWidget 中注入依赖
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun courseRepository(): CourseRepository
        fun semesterRepository(): SemesterRepository
        fun settingsRepository(): SettingsRepository
    }

    @Composable
    fun TimetableWidgetContent(
        courses: List<Course>,
        today: LocalDate,
        currentWeek: Int,
        sectionTimes: List<SectionTime>,
        emptyMessage: String,
        isBeforeSemesterStart: Boolean
    ) {
        val size = LocalSize.current
        val height = size.height

        // List Mode Threshold:
        // 1x4 (height ~50-90dp) -> Focus Mode
        // 2x4 (height ~110-150dp) -> List Mode (User requested multiple courses)
        // 3x4 (height ~200dp+) -> List Mode
        val isListMode = height >= 110.dp

        if (isListMode) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetColors.Background)
                    .appWidgetBackground()
                    .cornerRadius(24.dp)
                    .padding(
                        horizontal = 20.dp,
                        vertical = if (height < 160.dp) 16.dp else 20.dp
                    )
                    .clickable(actionStartActivity(getMainActivityClassName())),
                verticalAlignment = Alignment.Top // 强制置顶！
            ) {
                val isVeryCompact = height < 160.dp

                // Header
                if (isVeryCompact) {
                     Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                         Text(
                            text = if (isBeforeSemesterStart) "假期中" else "周${getDayOfWeekText(today.dayOfWeek.value)}",
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary)
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            text = "${today.monthValue}月${today.dayOfMonth}日",
                            style = TextStyle(fontSize = 13.sp, color = WidgetColors.TextSecondary)
                        )
                    }
                } else {
                    val weekTitle = if (isBeforeSemesterStart) "假期中" else "第${currentWeek}周"
                    WidgetHeader(weekTitle, "${today.monthValue}月${today.dayOfMonth}日")
                }

                if (courses.isEmpty()) {
                    EmptyCourseView(emptyMessage)
                } else {
                    // 关键：LazyColumn 必须设置 weight(1f)，否则可能撑不开
                    ScheduleList(courses, sectionTimes)
                }
            }
        } else {
             // 1x4 & 2x4 -> Focus Mode (Horizontal)
             // 直接渲染，不包裹在 Column 中，以便 FocusCourseItem 控制背景和圆角
             FocusCourseItem(courses, sectionTimes, today = today, emptyMessage = emptyMessage, isBeforeSemesterStart = isBeforeSemesterStart)
        }
    }

    @Composable
    fun FocusCourseItem(
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        today: LocalDate,
        emptyMessage: String,
        isBeforeSemesterStart: Boolean
    ) {
        val now = LocalTime.now()
        val nextCourse = courses.firstOrNull { course ->
             isCourseCurrentOrFuture(course, sectionTimes, now)
        }
        
        // 1x4 极简高级感方案 (Linear Horizontal Flow)
        // [日期] | [课程] | [图标]
        
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Background) // 使用统一背景，视觉上更清爽
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(getMainActivityClassName()))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 左侧日期/状态指示 (小而美)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = GlanceModifier.width(40.dp) // 稍微加宽一点以容纳更大的字体
            ) {
                Text(
                    text = if (isBeforeSemesterStart) "假期中" else "周${getDayOfWeekText(today.dayOfWeek.value)}",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WidgetColors.Primary)
                )
                Text(
                    text = "${today.monthValue}.${today.dayOfMonth}",
                    style = TextStyle(fontSize = 12.sp, color = WidgetColors.TextSecondary)
                )
            }

            // 分割线
            Box(
                modifier = GlanceModifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(WidgetColors.Divider)
                    .padding(horizontal = 8.dp)
            ) {}

            Spacer(GlanceModifier.width(8.dp))

            // 2. 中间核心内容
            if (nextCourse != null) {
                val isCurrent = isCourseActive(nextCourse, sectionTimes, now)
                val startTime = getSectionStartTime(nextCourse.startSection, sectionTimes) ?: ""
                
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = nextCourse.name,
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary),
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$startTime ",
                            style = TextStyle(fontSize = 13.sp, color = if (isCurrent) WidgetColors.Primary else WidgetColors.TextSecondary, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        )
                        Text(
                            text = "· ${nextCourse.location}",
                            style = TextStyle(fontSize = 13.sp, color = WidgetColors.TextSecondary),
                            maxLines = 1
                        )
                    }
                }
                
                // 3. 右侧状态图标 (例如：距离上课还有多久，或者单纯的装饰)
                 if (isCurrent) {
                     // 上课中：显示呼吸点
                     Box(modifier = GlanceModifier.size(8.dp).background(WidgetColors.Primary).cornerRadius(4.dp)) {}
                 } else {
                      // 未开始：显示箭头 (如果没有箭头图标，暂时用 Text ">")
                      Text(
                        text = ">",
                        style = TextStyle(fontSize = 18.sp, color = WidgetColors.TextSecondary, fontWeight = FontWeight.Bold)
                    )
                  }
             } else {
                // 无课状态
                val displayMessage = if (emptyMessage.isNotEmpty()) emptyMessage else "今日课程已结束 🌙"
                Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                     Text(displayMessage, style = TextStyle(fontSize = 16.sp, color = WidgetColors.TextSecondary))
                }
            }
        }
    }

    @Composable
    fun WidgetHeader(weekInfo: String, dateInfo: String) {
        val today = LocalDate.now()
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = weekInfo,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary)
            )
            
            Spacer(GlanceModifier.width(8.dp))
            
            Text(
                text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                style = TextStyle(fontSize = 15.sp, color = WidgetColors.TextSecondary, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.padding(bottom = 2.dp)
            )
            
            Spacer(GlanceModifier.defaultWeight())
            
            Box(
                modifier = GlanceModifier
                    .background(WidgetColors.DateBadgeBackground)
                    .cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = dateInfo,
                    style = TextStyle(fontSize = 12.sp, color = WidgetColors.ActiveTextSecondary, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
    
    @Composable
    fun EmptyCourseView(message: String) {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = TextStyle(color = WidgetColors.TextSecondary, fontSize = 16.sp)
            )
        }
    }

    @Composable
    fun ColumnScope.ScheduleList(courses: List<Course>, sectionTimes: List<SectionTime>) {
        val now = LocalTime.now()
        val focusIndex = courses.indexOfFirst { course ->
            isCourseCurrentOrFuture(course, sectionTimes, now)
        }.takeIf { it != -1 } ?: 0

        LazyColumn(
        modifier = GlanceModifier.defaultWeight()
    ) {
        itemsIndexed(courses.sortedBy { it.startSection }) { index, course ->
                val isFocus = index == focusIndex
                val isPast = index < focusIndex

                if (isPast) {
                    // 已结束的课程：隐藏
                } else if (isFocus) {
                    ExpandedCourseItem(course, sectionTimes)
                } else {
                    CompactCourseItem(course, sectionTimes)
                }
                
                if (index >= focusIndex) {
                    Spacer(GlanceModifier.height(8.dp))
                }
            }
            item {
                Spacer(GlanceModifier.height(16.dp))
            }
        }
    }

    @Composable
    fun ExpandedCourseItem(course: Course, sectionTimes: List<SectionTime>) {
        val timePrimaryColor = WidgetColors.UpcomingText
        val timeSecondaryColor = WidgetColors.UpcomingSubText
        val activeBackground = WidgetColors.ActiveBackground
        val activeTextPrimary = WidgetColors.ActiveTextPrimary
        val activeTextSecondary = WidgetColors.ActiveTextSecondary
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"
        val endTimeStr = getSectionEndTime(course, sectionTimes)

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = GlanceModifier.width(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = startTime,
                    style = TextStyle(
                        color = timePrimaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1
                )
                if (endTimeStr != null) {
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = endTimeStr,
                        style = TextStyle(color = timeSecondaryColor, fontSize = 12.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
                        maxLines = 1
                    )
                }
            }

            Spacer(GlanceModifier.width(14.dp))

            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(activeBackground)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(
                        text = course.name,
                        style = TextStyle(
                            color = activeTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )

                    Spacer(GlanceModifier.height(6.dp))

                    val detailText = listOf(course.location, course.teacher)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (detailText.isNotBlank()) {
                        Text(
                            text = detailText,
                            style = TextStyle(color = activeTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CompactCourseItem(course: Course, sectionTimes: List<SectionTime>) {
        val textColor = WidgetColors.UpcomingText
        val subTextColor = WidgetColors.UpcomingSubText
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = startTime,
                style = TextStyle(color = subTextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                modifier = GlanceModifier.width(48.dp)
            )
            
            Spacer(GlanceModifier.width(14.dp))
            
            Box(modifier = GlanceModifier.width(2.dp).height(14.dp).background(WidgetColors.Divider).cornerRadius(1.dp)) {}
            
            Spacer(GlanceModifier.width(14.dp))

            Row(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val locationText = course.location.takeIf { it.isNotBlank() }
                val courseText = if (locationText != null) {
                    "${course.name} @${locationText}"
                } else {
                    course.name
                }
                Text(
                    text = courseText,
                    style = TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }
    }
    
    // --- 辅助函数 ---

    private fun getSectionEndTime(course: Course, sectionTimes: List<SectionTime>): String? {
         if (sectionTimes.isEmpty()) return null
         val endSectionNum = course.startSection + course.duration - 1
         val index = endSectionNum - 1
         if (index in sectionTimes.indices) {
             return sectionTimes[index].endTime
         }
         return null
    }

    private fun getSectionStartTime(section: Int, sectionTimes: List<SectionTime>): String? {
        if (sectionTimes.isEmpty()) return null
        val index = section - 1
        if (index in sectionTimes.indices) {
            return sectionTimes[index].startTime
        }
        return null
    }
    
    private fun getCourseTimeString(course: Course, sectionTimes: List<SectionTime>): String {
         if (sectionTimes.isEmpty()) {
             return "${course.startSection}-${course.startSection + course.duration - 1}节"
         }
         
         val startStr = getSectionStartTime(course.startSection, sectionTimes) ?: ""
          val endSectionNum = course.startSection + course.duration - 1
         val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
             sectionTimes[endSectionNum - 1].endTime
         } else ""
         
         if (startStr.isNotEmpty() && endStr.isNotEmpty()) {
             return "$startStr - $endStr"
         }
         return "${course.startSection}-${endSectionNum}节"
    }

    // 兼容不同时间格式（例如 8:00 / 08:00），避免解析失败导致课程一直被视为未结束
    private fun parseSectionTime(value: String): LocalTime? {
        if (value.isBlank()) return null
        val formatters = listOf(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm")
        )
        for (formatter in formatters) {
            runCatching { return LocalTime.parse(value.trim(), formatter) }
        }
        return null
    }

    private fun computeNextCourseEndMillis(
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        today: LocalDate,
        now: LocalTime
    ): Long? {
        if (courses.isEmpty() || sectionTimes.isEmpty()) return null
        var nextEndTime: LocalTime? = null
        courses.forEach { course ->
            val endSectionNum = course.startSection + course.duration - 1
            val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
                sectionTimes[endSectionNum - 1].endTime
            } else {
                ""
            }
            val endTime = parseSectionTime(endStr) ?: return@forEach
            if (endTime.isAfter(now)) {
                if (nextEndTime == null || endTime.isBefore(nextEndTime)) {
                    nextEndTime = endTime
                }
            }
        }
        val targetTime = nextEndTime ?: return null
        val triggerAt = today.atTime(targetTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nowMillis = java.time.Instant.now().toEpochMilli()
        return if (triggerAt > nowMillis) triggerAt else null
    }

    private fun isCourseActive(course: Course, sectionTimes: List<SectionTime>, now: LocalTime): Boolean {
        if (sectionTimes.isEmpty()) return false
        
        val startStr = getSectionStartTime(course.startSection, sectionTimes) ?: return false
        val endSectionNum = course.startSection + course.duration - 1
        val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
             sectionTimes[endSectionNum - 1].endTime
         } else return false
         
        try {
            val startTime = parseSectionTime(startStr) ?: return false
            val endTime = parseSectionTime(endStr) ?: return false
            
            return !now.isBefore(startTime) && !now.isAfter(endTime)
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun isCourseCurrentOrFuture(course: Course, sectionTimes: List<SectionTime>, now: LocalTime): Boolean {
         if (sectionTimes.isEmpty()) return true
         
         val endSectionNum = course.startSection + course.duration - 1
         val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
             sectionTimes[endSectionNum - 1].endTime
         } else return true
         
         try {
            val endTime = parseSectionTime(endStr) ?: return true
             return now.isBefore(endTime)
         } catch (e: Exception) {
             return true
         }
    }

    private fun getDayOfWeekText(day: Int): String {
        return when (day) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7 -> "日"
            else -> ""
        }
    }
    
    private fun getMainActivityClassName(): android.content.ComponentName {
         return android.content.ComponentName("com.dawncourse.app", "com.dawncourse.app.MainActivity")
    }
}
