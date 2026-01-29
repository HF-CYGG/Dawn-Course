package com.dawncourse.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
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

        val today = LocalDate.now()
        // 临时逻辑：假设开学第一周是 2025-02-17 (周一)
        // TODO: 应该从 SettingsRepository 获取开学日期
        val termStartDate = LocalDate.of(2025, 2, 17)
        val daysDiff = ChronoUnit.DAYS.between(termStartDate, today)
        val currentWeek = (daysDiff / 7).toInt() + 1
        val currentDayOfWeek = today.dayOfWeek.value // 1 (Mon) - 7 (Sun)

        val courses = withContext(Dispatchers.IO) {
            repository.getAllCourses().first()
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
            val size = LocalSize.current
            DawnWidgetContent(courses, today, currentWeek, size)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun courseRepository(): CourseRepository
    }

    @Composable
    private fun DawnWidgetContent(
        courses: List<Course>,
        today: LocalDate,
        currentWeek: Int,
        size: DpSize
    ) {
        val isSmall = size.width < 150.dp
        
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(android.graphics.Color.WHITE))
                .padding(12.dp)
        ) {
            // Header
            if (isSmall) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = "${today.monthValue}/${today.dayOfMonth}",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(android.graphics.Color.BLACK)
                        )
                    )
                    Text(
                        text = "第${currentWeek}周",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY))
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "${today.monthValue}月${today.dayOfMonth}日",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(android.graphics.Color.BLACK)
                        )
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "第${currentWeek}周",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY))
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = "周${getDayOfWeekText(today.dayOfWeek.value)}",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY))
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // List
            if (courses.isEmpty()) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "今日无课",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY))
                    )
                }
            } else {
                if (isSmall) {
                    // Small mode: Show only first course or "Next" logic (simplified to first for now)
                    val firstCourse = courses.first()
                    CourseItem(firstCourse, isCompact = true)
                    if (courses.size > 1) {
                        Text(
                            text = "+${courses.size - 1} more",
                            style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY)),
                            modifier = GlanceModifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    // Normal mode: List
                    LazyColumn(
                        modifier = GlanceModifier.fillMaxSize()
                    ) {
                        items(courses) { course ->
                            CourseItem(course, isCompact = false)
                            Spacer(modifier = GlanceModifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CourseItem(course: Course, isCompact: Boolean) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(android.graphics.Color.parseColor("#F0F0F0")))
                .padding(if (isCompact) 4.dp else 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.name,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(android.graphics.Color.BLACK)
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (!isCompact) {
                    Text(
                        text = "${course.startSection}-${course.startSection + course.duration - 1}节",
                        style = TextStyle(color = ColorProvider(android.graphics.Color.DKGRAY))
                    )
                }
            }
            if (course.location.isNotEmpty()) {
                Text(
                    text = "@${course.location}",
                    style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY))
                )
            }
            if (isCompact) {
                 Text(
                    text = "${course.startSection}-${course.startSection + course.duration - 1}节",
                    style = TextStyle(color = ColorProvider(android.graphics.Color.DKGRAY))
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
}
