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
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SettingsRepository
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
import androidx.glance.ColorFilter
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
    val DateBadgeBackground = ColorProvider(day = Color(0xFFE7E0EC), night = Color(0xFF4A4458))
    
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

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL_SQUARE, HORIZONTAL_RECTANGLE, VERTICAL_RECTANGLE, BIG_SQUARE)
    )

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

        val currentWeek = if (semester != null) {
            val termStartDate = Instant.ofEpochMilli(semester.startDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val daysDiff = ChronoUnit.DAYS.between(termStartDate, today)
            (daysDiff / 7).toInt() + 1
        } else {
            1 // 没读取到数据时的保底值
        }
        
        val currentDayOfWeek = today.dayOfWeek.value // 1 (Mon) - 7 (Sun)

        val courses = withContext(Dispatchers.IO) {
            if (semester != null) {
                repository.getCoursesBySemester(semester.id).first()
            } else {
                emptyList()
            }
        }.filter { course ->
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
        }.sortedBy { it.startSection }

        provideContent {
            GlanceTheme {
                TimetableWidgetContent(courses, today, currentWeek, sectionTimes)
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
        sectionTimes: List<SectionTime>
    ) {
        val size = LocalSize.current
        val width = size.width
        val height = size.height

        // 逻辑修正：
        // 1x4 典型高度为 70dp-90dp
        // 2x4 典型高度为 130dp-150dp
        // 我们将阈值设为 110dp
        val isListMode = height >= 110.dp

        if (isListMode) {
             // 2x4, 3x4, 4x4 -> List Layout
             // 如果高度小于 160dp (典型 2x4)，我们就压缩 Padding
             val isVeryCompact = height < 160.dp
             CourseListLayout(courses, today, currentWeek, sectionTimes, isCompact = isVeryCompact)
        } else {
             // 1x4 -> Focus Mode (Horizontal)
             FocusCourseItem(courses, sectionTimes, isHorizontal = true)
        }
    }

    @Composable
    fun FocusCourseItem(
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        isHorizontal: Boolean
    ) {
        val now = LocalTime.now()
        val nextCourse = courses.firstOrNull { course ->
             isCourseCurrentOrFuture(course, sectionTimes, now)
        }
        
        val modifier = GlanceModifier
            .fillMaxSize()
            .background(if (isHorizontal) WidgetColors.Background else WidgetColors.PrimaryContainer)
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .padding(if (isHorizontal) 8.dp else 16.dp)
            .clickable(actionStartActivity(getMainActivityClassName()))

        Box(
            modifier = modifier,
            contentAlignment = if (isHorizontal) Alignment.CenterStart else Alignment.Center
        ) {
            if (nextCourse != null) {
                val isCurrent = isCourseActive(nextCourse, sectionTimes, now)
                
                if (isHorizontal) {
                    // --- Horizontal Layout (4x1) ---
                    val colorIndex = kotlin.math.abs(nextCourse.name.hashCode()) % WidgetCourseColors.size
                    val baseColor = WidgetCourseColors[colorIndex]
                    val bg = if (isCurrent) baseColor else WidgetColors.Surface
                    
                    Row(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(bg)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 时间
                        val startTime = getSectionStartTime(nextCourse.startSection, sectionTimes) ?: ""
                        Column(verticalAlignment = Alignment.CenterVertically) {
                             Text(
                                text = startTime,
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary)
                            )
                            if (isCurrent) {
                                Text(
                                    text = "上课中",
                                    style = TextStyle(fontSize = 10.sp, color = WidgetColors.Primary, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Spacer(GlanceModifier.width(12.dp))
                        Box(modifier = GlanceModifier.width(1.dp).height(24.dp).background(WidgetColors.Divider)) {}
                        Spacer(GlanceModifier.width(12.dp))

                        // 课程信息
                        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = nextCourse.name,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary),
                                maxLines = 1
                            )
                            Spacer(GlanceModifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                 Image(
                                    provider = ImageProvider(R.drawable.ic_location_on),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(12.dp),
                                    colorFilter = ColorFilter.tint(WidgetColors.IconTint)
                                )
                                Spacer(GlanceModifier.width(4.dp))
                                 Text(
                                    text = nextCourse.location,
                                    style = TextStyle(fontSize = 12.sp, color = WidgetColors.TextSecondary),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                } else {
                    // --- Vertical/Square Layout (2x2) ---
                    Column {
                        if (isCurrent) {
                             Text(
                                text = "正在上课",
                                style = TextStyle(color = WidgetColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.padding(bottom = 4.dp)
                            )
                        } else {
                             Text(
                                text = "接下来",
                                style = TextStyle(color = WidgetColors.OnPrimaryContainer, fontSize = 12.sp),
                                modifier = GlanceModifier.padding(bottom = 4.dp)
                            )
                        }

                        Text(
                            text = nextCourse.name,
                            style = TextStyle(color = WidgetColors.OnPrimaryContainer, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            maxLines = 2
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_location_on),
                                contentDescription = null,
                                modifier = GlanceModifier.size(12.dp),
                                colorFilter = ColorFilter.tint(WidgetColors.OnPrimaryContainer)
                            )
                            Spacer(GlanceModifier.width(4.dp))
                            Text(
                                text = nextCourse.location.ifEmpty { "未知地点" },
                                style = TextStyle(color = WidgetColors.OnPrimaryContainer, fontSize = 14.sp)
                            )
                        }
                        
                        val timeStr = getCourseTimeString(nextCourse, sectionTimes)
                        Text(
                            text = timeStr,
                            style = TextStyle(color = WidgetColors.Primary, fontSize = 14.sp),
                            modifier = GlanceModifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                // 无课状态
                if (isHorizontal) {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "☕", style = TextStyle(fontSize = 20.sp))
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            text = "今日课程已结束",
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = WidgetColors.TextSecondary)
                        )
                    }
                } else {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "今日课程已结束",
                            style = TextStyle(color = WidgetColors.OnPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        )
                         Text(
                            text = "好好休息",
                            style = TextStyle(color = WidgetColors.OnPrimaryContainer, fontSize = 12.sp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CourseListLayout(
        courses: List<Course>,
        today: LocalDate,
        currentWeek: Int,
        sectionTimes: List<SectionTime>,
        isCompact: Boolean
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Background)
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .padding(if (isCompact) 8.dp else 12.dp)
                .clickable(actionStartActivity(getMainActivityClassName())),
            horizontalAlignment = Alignment.Start
        ) {
            // Header
            if (isCompact) {
                 Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                     Text(
                        text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary)
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = "${today.monthValue}月${today.dayOfMonth}日",
                        style = TextStyle(fontSize = 12.sp, color = WidgetColors.TextSecondary)
                    )
                }
            } else {
                WidgetHeader("第${currentWeek}周", "${today.monthValue}月${today.dayOfMonth}日")
            }

            if (courses.isEmpty()) {
                EmptyCourseView()
            } else {
                ScheduleList(courses, sectionTimes, isCompact)
            }
        }
    }

    @Composable
    fun WidgetHeader(weekInfo: String, dateInfo: String) {
        val today = LocalDate.now()
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = weekInfo,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WidgetColors.TextPrimary)
            )
            
            Spacer(GlanceModifier.width(8.dp))
            
            Text(
                text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                style = TextStyle(fontSize = 16.sp, color = WidgetColors.TextSecondary, fontWeight = FontWeight.Medium),
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
                    style = TextStyle(fontSize = 12.sp, color = WidgetColors.TextSecondary, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
    
    @Composable
    fun EmptyCourseView() {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "今日无课",
                style = TextStyle(color = WidgetColors.TextSecondary, fontSize = 16.sp)
            )
        }
    }

    @Composable
    fun ScheduleList(courses: List<Course>, sectionTimes: List<SectionTime>, isCompact: Boolean) {
        val now = LocalTime.now()
        val focusIndex = courses.indexOfFirst { course ->
            isCourseCurrentOrFuture(course, sectionTimes, now)
        }.takeIf { it != -1 } ?: 0

        LazyColumn(
            modifier = GlanceModifier.fillMaxSize()
        ) {
            itemsIndexed(courses.sortedBy { it.startSection }) { index, course ->
                val isFocus = index == focusIndex
                val isPast = index < focusIndex

                if (isPast) {
                    // 已结束的课程：隐藏
                } else if (isFocus) {
                    val isCurrent = isCourseActive(course, sectionTimes, now)
                    ExpandedCourseItem(course, isCurrent, sectionTimes, isCompact)
                } else {
                    CompactCourseItem(course, sectionTimes, isCompact)
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
    fun ExpandedCourseItem(course: Course, isCurrent: Boolean, sectionTimes: List<SectionTime>, isCompact: Boolean) {
        val colorIndex = kotlin.math.abs(course.name.hashCode()) % WidgetCourseColors.size
        val baseColor = WidgetCourseColors[colorIndex]
        val backgroundColor = baseColor
        val textColor = WidgetColors.TextPrimary
        val subTextColor = WidgetColors.TextSecondary
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"
        val endTimeStr = getSectionEndTime(course, sectionTimes)

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间列 (Compact 模式下隐藏结束时间，缩小宽度)
            Column(
                modifier = GlanceModifier.width(if (isCompact) 40.dp else 48.dp),
                horizontalAlignment = Alignment.End
            ) {
                val timeColor = if (isCurrent) WidgetColors.Primary else subTextColor
                Text(
                    text = startTime,
                    style = TextStyle(color = timeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
                    maxLines = 1
                )
                if (!isCompact && endTimeStr != null) {
                    Text(
                        text = endTimeStr,
                        style = TextStyle(color = subTextColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End),
                        maxLines = 1
                    )
                }
            }

            Spacer(GlanceModifier.width(8.dp))

            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(backgroundColor)
                    .cornerRadius(16.dp)
                    .padding(horizontal = if (isCompact) 12.dp else 16.dp, vertical = if (isCompact) 8.dp else 12.dp)
            ) {
                Text(
                    text = course.name,
                    style = TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                
                Spacer(GlanceModifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_location_on),
                        contentDescription = null,
                        modifier = GlanceModifier.size(12.dp),
                        colorFilter = ColorFilter.tint(WidgetColors.IconTint)
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = "${course.location}" + if (!isCompact) " · ${course.teacher}" else "",
                        style = TextStyle(color = subTextColor, fontSize = 12.sp),
                        maxLines = 1
                    )
                }
            }
            
            if (isCurrent) {
                Spacer(GlanceModifier.width(8.dp))
                Box(
                    modifier = GlanceModifier.size(6.dp).background(WidgetColors.Primary).cornerRadius(3.dp)
                ) {}
            }
        }
    }

    @Composable
    fun CompactCourseItem(course: Course, sectionTimes: List<SectionTime>, isCompact: Boolean) {
        val textColor = WidgetColors.TextPrimary
        val subTextColor = WidgetColors.TextSecondary
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(WidgetColors.Surface)
                .cornerRadius(12.dp)
                .padding(horizontal = if (isCompact) 8.dp else 12.dp, vertical = if (isCompact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = startTime,
                style = TextStyle(color = subTextColor, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.width(40.dp)
            )
            
            Box(modifier = GlanceModifier.width(2.dp).height(12.dp).background(WidgetColors.Divider)) {}
            
            Spacer(GlanceModifier.width(8.dp))

            Row(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = course.name,
                    style = TextStyle(color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "@${course.location}",
                    style = TextStyle(color = subTextColor, fontSize = 11.sp),
                    maxLines = 1
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

    private fun isCourseActive(course: Course, sectionTimes: List<SectionTime>, now: LocalTime): Boolean {
        if (sectionTimes.isEmpty()) return false
        
        val startStr = getSectionStartTime(course.startSection, sectionTimes) ?: return false
        val endSectionNum = course.startSection + course.duration - 1
        val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
             sectionTimes[endSectionNum - 1].endTime
         } else return false
         
        try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val startTime = LocalTime.parse(startStr, formatter)
            val endTime = LocalTime.parse(endStr, formatter)
            
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
             val formatter = DateTimeFormatter.ofPattern("HH:mm")
             val endTime = LocalTime.parse(endStr, formatter)
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
