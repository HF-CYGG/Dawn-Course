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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
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

        val today = LocalDate.now()
        
        val semester = withContext(Dispatchers.IO) {
            semesterRepository.getCurrentSemester().first()
        }

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
                    NextClassView(courses)
                } else {
                    DailyListView(courses, today, currentWeek)
                }
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun courseRepository(): CourseRepository
        fun semesterRepository(): SemesterRepository
    }

    @Composable
    fun NextClassView(courses: List<Course>) {
        // 寻找当前正在上或即将开始的课
        // 这里只是简单的取第一节课，实际应该根据 SectionTime 判断
        // TODO: 引入 SectionTime 逻辑判断当前课程
        val nextCourse = courses.firstOrNull { 
            // 简单假设：只要是今天的课，且还没结束（这里暂时无法精确判断，取第一个）
            true 
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .appWidgetBackground()
                .padding(16.dp)
                .clickable(actionStartActivity(getMainActivityClassName())),
            contentAlignment = Alignment.Center
        ) {
            if (nextCourse != null) {
                Column {
                    Text(
                        text = nextCourse.name,
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 20.sp, // 稍微调小一点以防溢出
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 注意：Glance 不支持所有 VectorIcon，这里暂时用文本代替图标，或使用 drawable 资源
                        Text(
                            text = "📍", 
                            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp)
                        )
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            text = nextCourse.location.ifEmpty { "未知地点" },
                            style = TextStyle(
                                color = GlanceTheme.colors.onPrimaryContainer,
                                fontSize = 14.sp
                            )
                        )
                    }
                    Text(
                        text = "${nextCourse.startSection}-${nextCourse.startSection + nextCourse.duration - 1}节",
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp),
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
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                     Text(
                        text = "好好休息",
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun DailyListView(courses: List<Course>, today: LocalDate, currentWeek: Int) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .appWidgetBackground()
                .padding(12.dp)
                .clickable(actionStartActivity(getMainActivityClassName()))
        ) {
            // 标题栏
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第${currentWeek}周 · 周${getDayOfWeekText(today.dayOfWeek.value)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = "${today.monthValue}月${today.dayOfMonth}日",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                )
            }

            if (courses.isEmpty()) {
                 Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "今日无课",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            } else {
                LazyColumn {
                    items(courses) { course ->
                        // 模拟判断当前课程 (需要真实时间逻辑)
                        val isCurrent = false 
                        CourseItemRow(course, isCurrent)
                        Spacer(GlanceModifier.height(8.dp))
                    }
                }
            }
        }
    }
    
    @Composable
    fun CourseItemRow(course: Course, isCurrent: Boolean) {
        // 动态计算背景色
        val bgColor = if (isCurrent) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant
        val contentColor = if (isCurrent) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
        
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(bgColor)
                .cornerRadius(12.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间 (这里暂时只显示节次，因为 Course 模型里可能没有具体时间)
            // 理想情况是根据 SectionTime 算出 08:00
            Text(
                text = "${course.startSection}节", 
                style = TextStyle(color = contentColor, fontSize = 12.sp)
            )
            Spacer(GlanceModifier.width(12.dp))
            Column {
                Text(
                    text = course.name, 
                    style = TextStyle(
                        color = contentColor, 
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = "${course.location} · ${course.teacher}", 
                    style = TextStyle(
                        color = contentColor, 
                        fontSize = 12.sp
                    )
                )
            }
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
