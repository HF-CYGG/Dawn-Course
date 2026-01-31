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
import androidx.glance.appwidget.lazy.items
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
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SemesterRepository
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
import com.dawncourse.core.domain.repository.SettingsRepository
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
 * 2. 根据 Widget 尺寸自动切换布局 (NextClassView, HorizontalDailyListView, DailyListView)
 * 3. 过滤非当前周次或非当日的课程
 */
class DawnWidget : GlanceAppWidget() {

    companion object {
        private val SMALL_SQUARE = DpSize(100.dp, 100.dp)
        private val HORIZONTAL_RECTANGLE = DpSize(250.dp, 100.dp)
        private val BIG_SQUARE = DpSize(250.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL_SQUARE, HORIZONTAL_RECTANGLE, BIG_SQUARE)
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
                val size = LocalSize.current
                // 根据宽度判断使用哪种视图
                if (size.width < 200.dp) {
                    NextClassView(courses, sectionTimes)
                } else if (size.height < 120.dp) {
                    // 1x4 横向极窄布局
                    HorizontalNextClassView(courses, sectionTimes)
                } else if (size.height < 180.dp) {
                    // 2x4 横向长条布局
                    HorizontalDailyListView(courses, today, currentWeek, sectionTimes)
                } else {
                    DailyListView(courses, today, currentWeek, sectionTimes)
                }
            }
        }
    }

    /**
     * Hilt EntryPoint 用于在 GlanceAppWidget 中注入依赖
     * 因为 GlanceAppWidget 不是 Android 组件，无法直接使用 @AndroidEntryPoint
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun courseRepository(): CourseRepository
        fun semesterRepository(): SemesterRepository
        fun settingsRepository(): SettingsRepository
    }

    @Composable
    fun HorizontalNextClassView(courses: List<Course>, sectionTimes: List<SectionTime>) {
        val now = LocalTime.now()
        val nextCourse = courses.firstOrNull { course ->
             isCourseCurrentOrFuture(course, sectionTimes, now)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Background)
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .padding(8.dp)
                .clickable(actionStartActivity(getMainActivityClassName())),
            contentAlignment = Alignment.CenterStart
        ) {
            if (nextCourse != null) {
                val isCurrent = isCourseActive(nextCourse, sectionTimes, now)
                // 使用课程颜色作为左侧条的颜色，或者整体背景淡色
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
                    Column(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                         Text(
                            text = startTime,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = WidgetColors.TextPrimary
                            )
                        )
                        if (isCurrent) {
                            Text(
                                text = "上课中",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    color = WidgetColors.Primary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    Spacer(GlanceModifier.width(12.dp))
                    
                    // 竖线分隔
                    Box(
                        modifier = GlanceModifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(WidgetColors.Divider)
                    ) {}
                    
                    Spacer(GlanceModifier.width(12.dp))

                    // 课程信息
                    Column(
                        modifier = GlanceModifier.defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = nextCourse.name,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = WidgetColors.TextPrimary
                            ),
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
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = WidgetColors.TextSecondary
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                // 无课状态
                Row(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "☕",
                        style = TextStyle(fontSize = 20.sp)
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = "今日课程已结束",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = WidgetColors.TextSecondary
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun NextClassView(courses: List<Course>, sectionTimes: List<SectionTime>) {
        val now = LocalTime.now()
        // 寻找当前正在上或即将开始的课
        val nextCourse = courses.firstOrNull { course ->
             isCourseCurrentOrFuture(course, sectionTimes, now)
        }
        
        // 如果所有课都结束了，显示"今日课程已结束"
        // 这里简单判定：如果找不到 currentOrFuture，说明都结束了
        
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.PrimaryContainer)
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .padding(16.dp)
                .clickable(actionStartActivity(getMainActivityClassName())),
            contentAlignment = Alignment.Center
        ) {
            if (nextCourse != null) {
                val isCurrent = isCourseActive(nextCourse, sectionTimes, now)
                Column {
                    // 状态提示
                    if (isCurrent) {
                         Text(
                            text = "正在上课",
                            style = TextStyle(
                                color = WidgetColors.Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = GlanceModifier.padding(bottom = 4.dp)
                        )
                    } else {
                         Text(
                            text = "接下来",
                            style = TextStyle(
                                color = WidgetColors.OnPrimaryContainer,
                                fontSize = 12.sp
                            ),
                            modifier = GlanceModifier.padding(bottom = 4.dp)
                        )
                    }

                    Text(
                        text = nextCourse.name,
                        style = TextStyle(
                            color = WidgetColors.OnPrimaryContainer,
                            fontSize = 20.sp, 
                            fontWeight = FontWeight.Bold
                        ),
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
                            style = TextStyle(
                                color = WidgetColors.OnPrimaryContainer,
                                fontSize = 14.sp
                            )
                        )
                    }
                    
                    val timeStr = getCourseTimeString(nextCourse, sectionTimes)
                    Text(
                        text = timeStr,
                        style = TextStyle(color = WidgetColors.Primary, fontSize = 14.sp),
                        modifier = GlanceModifier.padding(top = 4.dp)
                    )
                }
            } else {
                 Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "今日课程已结束",
                        style = TextStyle(
                            color = WidgetColors.OnPrimaryContainer,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                     Text(
                        text = "好好休息",
                        style = TextStyle(
                            color = WidgetColors.OnPrimaryContainer,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun HorizontalDailyListView(courses: List<Course>, today: LocalDate, currentWeek: Int, sectionTimes: List<SectionTime>) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Background)
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(getMainActivityClassName())),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：垂直排列的 Header
            Column(
                modifier = GlanceModifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "第${currentWeek}周",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = WidgetColors.TextPrimary
                    )
                )
                
                Text(
                    text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = WidgetColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier.padding(vertical = 4.dp)
                )
                
                Box(
                    modifier = GlanceModifier
                        .background(WidgetColors.DateBadgeBackground)
                        .cornerRadius(8.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${today.monthValue}月${today.dayOfMonth}日",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = WidgetColors.TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // 分割线
            Box(
                modifier = GlanceModifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(WidgetColors.Divider)
            ) {}
            
            Spacer(GlanceModifier.width(12.dp))

            // 右侧：课程列表
            if (courses.isEmpty()) {
                Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                    EmptyCourseView()
                }
            } else {
                // 使用 Box 包裹 ScheduleList 以填充剩余空间
                Box(modifier = GlanceModifier.defaultWeight()) {
                    ScheduleList(courses, sectionTimes)
                }
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
                style = TextStyle(
                    color = WidgetColors.TextSecondary,
                    fontSize = 16.sp
                )
            )
        }
    }

    @Composable
    fun DailyListView(courses: List<Course>, today: LocalDate, currentWeek: Int, sectionTimes: List<SectionTime>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Background)
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(getMainActivityClassName()))
        ) {
            WidgetHeader("第${currentWeek}周", "${today.monthValue}月${today.dayOfMonth}日")

            if (courses.isEmpty()) {
                EmptyCourseView()
            } else {
                ScheduleList(courses, sectionTimes)
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
            // 左边：大大的周次
            Text(
                text = weekInfo, // "第8周"
                style = TextStyle(
                    fontSize = 22.sp, // 加大字号
                    fontWeight = FontWeight.Bold,
                    color = WidgetColors.TextPrimary
                )
            )
            
            Spacer(GlanceModifier.width(8.dp))
            
            // 周几
            Text(
                text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = WidgetColors.TextSecondary,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.padding(bottom = 2.dp)
            )
            
            Spacer(GlanceModifier.defaultWeight())
            
            // 右边：日期胶囊
            Box(
                modifier = GlanceModifier
                    .background(WidgetColors.DateBadgeBackground) // 浅灰背景
                    .cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = dateInfo, // "1月30日"
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = WidgetColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }

    @Composable
    fun ScheduleList(courses: List<Course>, sectionTimes: List<SectionTime>) {
        val now = LocalTime.now()
        // 1. 找到当前正在进行，或者即将开始的第一节课的索引
        // 如果找不到(返回-1，说明都结束了)，则默认为0(显示第一节)，或者可以改为全部紧凑显示
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
                    // 已结束的课程：隐藏，为了节省空间
                } else if (isFocus) {
                    // 当前/下一节课：使用大卡片
                    val isCurrent = isCourseActive(course, sectionTimes, now)
                    ExpandedCourseItem(course, isCurrent, sectionTimes)
                } else {
                    // 未来的课程：使用紧凑模式
                    CompactCourseItem(course, sectionTimes)
                }
                
                // 列表项之间的间距 (只给没隐藏的加间距)
                if (index >= focusIndex) {
                    Spacer(GlanceModifier.height(8.dp))
                }
            }
            // 底部留白，防止被圆角截断
            item {
                Spacer(GlanceModifier.height(16.dp))
            }
        }
    }

    @Composable
    fun ExpandedCourseItem(course: Course, isCurrent: Boolean, sectionTimes: List<SectionTime>) {
        // 1. 根据课程名生成固定颜色
        val colorIndex = kotlin.math.abs(course.name.hashCode()) % WidgetCourseColors.size
        val baseColor = WidgetCourseColors[colorIndex]
        
        // 如果是当前课程，颜色加深一点；否则用极淡的背景
        // 由于 WidgetCourseColors 已经是 ColorProvider，无法直接 copy(alpha)，所以统一使用 baseColor
        // 在暗色模式下，这些颜色会自动适配
        val backgroundColor = baseColor
        val textColor = WidgetColors.TextPrimary
        val subTextColor = WidgetColors.TextSecondary
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"
        val endTimeStr = getSectionEndTime(course, sectionTimes)

        Row(
            modifier = GlanceModifier
                .fillMaxWidth(),
                //.padding(vertical = 4.dp), // 外部已经有 Spacer 了
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 左侧：时间列 ---
            Column(
                modifier = GlanceModifier.width(48.dp),
                horizontalAlignment = Alignment.End
            ) {
                val timeColor = if (isCurrent) WidgetColors.Primary else subTextColor
                
                Text(
                    text = startTime,
                    style = TextStyle(
                        color = timeColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    maxLines = 1
                )
                
                if (endTimeStr != null) {
                    Text(
                        text = endTimeStr,
                        style = TextStyle(
                            color = subTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(GlanceModifier.width(8.dp))

            // --- 右侧：课程卡片 ---
            // 这是一个带圆角的彩色块
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(backgroundColor) // 淡色背景
                    .cornerRadius(16.dp) // 大圆角
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 课程名
                Text(
                    text = course.name,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                
                Spacer(GlanceModifier.height(4.dp))
                
                // 地点与老师 (一行显示，中间加点)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 使用 ImageProvider 加载图标，并着色
                    Image(
                        provider = ImageProvider(R.drawable.ic_location_on),
                        contentDescription = null,
                        modifier = GlanceModifier.size(12.dp),
                        colorFilter = ColorFilter.tint(WidgetColors.IconTint)
                    )
                    
                    Spacer(GlanceModifier.width(4.dp))
                    
                    Text(
                        text = "${course.location} · ${course.teacher}",
                        style = TextStyle(
                            color = subTextColor,
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )
                }
            }
            
            // --- 选中指示器 (可选) ---
            // 如果是当前课程，右边显示一个小圆点
            if (isCurrent) {
                Spacer(GlanceModifier.width(8.dp))
                Box(
                    modifier = GlanceModifier
                        .size(6.dp)
                        .background(WidgetColors.Primary)
                        .cornerRadius(3.dp)
                ) {}
            }
        }
    }

    @Composable
    fun CompactCourseItem(course: Course, sectionTimes: List<SectionTime>) {
        val textColor = WidgetColors.TextPrimary
        val subTextColor = WidgetColors.TextSecondary
        
        val startTime = getSectionStartTime(course.startSection, sectionTimes) ?: "${course.startSection}"

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(WidgetColors.Surface) // 纯白背景
                // .border(1.dp, Color(0xFFE0E0E0)) // Glance 暂不支持 border modifier，用 background 模拟或忽略
                .cornerRadius(12.dp) // 稍小的圆角
                .padding(horizontal = 12.dp, vertical = 8.dp), // 紧凑的 Padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：仅显示开始时间
            Text(
                text = startTime,
                style = TextStyle(
                    color = subTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.width(40.dp)
            )
            
            // 竖线分隔
            Box(
                modifier = GlanceModifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(WidgetColors.Divider)
            ) {}
            
            Spacer(GlanceModifier.width(8.dp))

            // 右侧：课程名 + 教室 (一行显示)
            Row(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = course.name,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "@${course.location}", // Using location instead of room
                    style = TextStyle(
                        color = subTextColor,
                        fontSize = 11.sp
                    ),
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
          // duration 1 -> index same as start
          // duration 2 -> start 1, end 2. index 1.
          // Wait, startSection is 1-based.
          // startSection 1, duration 2 -> endSection 2. index 1.
          // formula: startSection + duration - 1 is the end section number.
          // index is endSection - 1.
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
         if (sectionTimes.isEmpty()) return true // 无法判断，默认都显示
         
         val endSectionNum = course.startSection + course.duration - 1
         val endStr = if (endSectionNum - 1 in sectionTimes.indices) {
             sectionTimes[endSectionNum - 1].endTime
         } else return true // 无法判断
         
         try {
             val formatter = DateTimeFormatter.ofPattern("HH:mm")
             val endTime = LocalTime.parse(endStr, formatter)
             return now.isBefore(endTime) // 只要没结束，都算 CurrentOrFuture
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
    
    // 这里的 ComponentName 需要根据你的 App 实际情况填写，或者直接用 Intent
    private fun getMainActivityClassName(): android.content.ComponentName {
        // 这里只是为了演示，实际可以直接传 Intent
         return android.content.ComponentName("com.dawncourse.app", "com.dawncourse.app.MainActivity")
    }
}
