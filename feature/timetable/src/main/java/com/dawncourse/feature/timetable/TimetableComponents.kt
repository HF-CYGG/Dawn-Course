package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawncourse.core.domain.model.Course
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.ui.theme.LocalAppSettings
import com.dawncourse.feature.timetable.util.CourseColorUtils
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

// 常量定义
val NODE_HEIGHT = 56.dp // 单节课高度
val TIMETABLE_START_HOUR = 8 // 起始时间 8:00
val TIME_COLUMN_WIDTH = 30.dp // 左侧时间轴宽度

/**
 * 周次头部栏组件
 *
 * 显示周一到周日，并高亮当前日期。
 *
 * @param modifier 修饰符
 */
@Composable
fun WeekHeader(modifier: Modifier = Modifier) {
    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val today = LocalDate.now().dayOfWeek.value // 1 (Mon) - 7 (Sun)
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TIME_COLUMN_WIDTH) // Time column offset
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        days.forEachIndexed { index, day ->
            val dayValue = index + 1
            val isToday = dayValue == today
            
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Capsule Background for Today
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // 变淡
                    )
                }
                
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (isToday) 14.sp else 12.sp // 选中放大
                    ),
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) // 选中色
                )
            }
        }
    }
}

/**
 * 左侧时间轴指示器
 *
 * 显示节次数字 (1-12) 和对应的时间。
 *
 * @param modifier 修饰符
 */
@Composable
fun TimeColumnIndicator(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val maxNodes = settings.maxDailySections

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(TIME_COLUMN_WIDTH) // 限制宽度
            .padding(top = 4.dp), // Align with grid
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (i in 1..maxNodes) {
            Column(
                modifier = Modifier.height(NODE_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = i.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp), // Smaller
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif, // Google Sans-like (System Sans)
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) // Darker grey
                )
                // Optional: Show time in very small font if needed, or remove it as requested
                // User said: "remove specific time or make it tiny (8sp)"
                // Let's keep it tiny for now as it's useful
                Text(
                    text = "${TIMETABLE_START_HOUR + i - 1}:00",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp, // Tiny
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray
                )
            }
        }
    }
}


/**
 * 课表网格布局
 *
 * 使用自定义 Layout 实现，根据课程的星期和节次进行绝对定位。
 */
@Composable
fun TimetableGrid(
    courses: List<Course>,
    currentWeek: Int,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit
) {
    val settings = LocalAppSettings.current
    // 假设每天最多 12 节课
    val maxNodes = settings.maxDailySections
    val totalHeight = NODE_HEIGHT * maxNodes
    
    val dividerColor = try {
        Color(android.graphics.Color.parseColor(settings.dividerColor))
    } catch (e: Exception) { Color(0xFFE5E7EB) }.copy(alpha = settings.dividerAlpha)
    
    val pathEffect = when (settings.dividerType) {
        DividerType.SOLID -> null
        DividerType.DASHED -> PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        DividerType.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
    }

    // 1. Prepare display list
    // Filter and process courses
    val courseGroups = courses.groupBy { "${it.dayOfWeek}-${it.startSection}" }
    val displayList = remember(courses, currentWeek) {
        val list = mutableListOf<Pair<Course, Boolean>>() // Course, isCurrentWeek
        courseGroups.forEach { (_, group) ->
            // Conflict resolution logic
            val currentWeekCourses = group.filter { course ->
                val isWeekActive = course.startWeek <= currentWeek && course.endWeek >= currentWeek
                val isWeekParityMatch = when (course.weekType) {
                    Course.WEEK_TYPE_ALL -> true
                    Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
                    Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
                    else -> true
                }
                isWeekActive && isWeekParityMatch
            }
            
            // Priority: Current Week > Non-Current Week (First one)
            // Fixes overlap by ensuring only ONE course is displayed per slot
            val targetCourse = if (currentWeekCourses.isNotEmpty()) {
                currentWeekCourses.first() // If multiple current (conflict), pick first
            } else {
                group.first() // If no current, pick first available (non-current)
            }
            
            val isCurrent = currentWeekCourses.contains(targetCourse)
            list.add(targetCourse to isCurrent)
        }
        list
    }

    Layout(
        content = {
            displayList.forEach { (course, isCurrentWeek) ->
                CourseCard(
                    course = course,
                    isCurrentWeek = isCurrentWeek,
                    onClick = { onCourseClick(course) }
                )
            }
        },
        modifier = modifier
            .height(totalHeight)
            .drawBehind {
                val width = size.width
                val nodeHeightPx = NODE_HEIGHT.toPx()

                // Draw horizontal lines only (Very faint)
                for (i in 0..maxNodes) {
                    // 隐藏竖线，只画极细的横线，或者完全去掉（这里保留极细横线以维持网格感，但非常淡）
                    // 用户说"去掉所有分割线"指的是 Header，但对于 Grid，"隐形网格"是指去掉竖线，横线极细。
                    // 用户在Step 3中说 "去掉所有分割线" 是针对Header。
                    // 对于Grid，Step 3说 "网格与时间轴：做减法...横向分割线...设为极细...或者干脆也去掉"
                    // 既然用户提到"或者干脆也去掉"，且希望"通透感"，我们可以尝试去掉横线，或者留一个极淡的。
                    // 这里我们保留极淡的横线 (alpha 0.05) 供参考，或者如果用户觉得太乱可以设为 0。
                    // 暂时保留极淡横线。
                    drawLine(
                        color = dividerColor.copy(alpha = 0.05f), // Very faint
                        start = Offset(0f, i * nodeHeightPx),
                        end = Offset(width, i * nodeHeightPx),
                        strokeWidth = 0.5.dp.toPx(), // Thin
                        pathEffect = pathEffect
                    )
                }
            }
    ) { measurables, constraints ->
        // 1. 计算基础尺寸
        val width = constraints.maxWidth
        val cellWidth = width / 7
        val nodeHeightPx = NODE_HEIGHT.toPx()
        
        // 2. 测量所有子元素
        val placeables = measurables.mapIndexed { index, measurable ->
            val (course, _) = displayList[index]
            val span = course.duration
            val height = (span * nodeHeightPx).roundToInt()
            
            // 使用完整宽度，间距由 Card 内部 padding 控制
            val placeableWidth = cellWidth
            
            measurable.measure(
                constraints.copy(
                    minWidth = placeableWidth,
                    maxWidth = placeableWidth,
                    minHeight = height,
                    maxHeight = height
                )
            )
        }
        
        layout(width, (maxNodes * nodeHeightPx).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val (course, _) = displayList[index]
                
                // 计算位置
                // X: (dayOfWeek - 1) * cellWidth
                val x = (course.dayOfWeek - 1) * cellWidth
                
                // Y: (startSection - 1) * nodeHeight
                val y = ((course.startSection - 1) * nodeHeightPx).roundToInt()
                
                placeable.place(x, y)
            }
        }
    }
}

/**
 * 课程卡片组件
 *
 * 在网格中显示的单个课程块。
 *
 * @param course 课程数据
 * @param isCurrentWeek 是否为本周课程 (非本周课程显示半透明)
 * @param onClick 点击回调
 */
@Composable
fun CourseCard(
    course: Course,
    isCurrentWeek: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = CourseColorUtils.parseColor(CourseColorUtils.getCourseColor(course))
    val containerColor = backgroundColor.copy(alpha = if (isCurrentWeek) 0.9f else 0.4f) // Use alpha 0.9 as requested
    
    // 扁平化，无阴影
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp), // 卡片之间的空隙
        shape = RoundedCornerShape(12.dp), // 大圆角
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp), // 内部间距
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. 课程名
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFF333333) // 深灰
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            // 2. 底部信息块
            Column {
                // 教室：带小图标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF49454F) // 次级文本色
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = course.location,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            color = Color(0xFF49454F)
                        ),
                        maxLines = 1
                    )
                }
                
                // 老师
                if (course.teacher.isNotEmpty()) {
                    Text(
                        text = course.teacher,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            color = Color(0xFF49454F)
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * 课程详情底部弹窗
 *
 * 显示课程的详细信息，提供编辑和删除操作入口。
 *
 * @param course 课程数据
 * @param onDismissRequest 关闭弹窗回调
 * @param onEditClick 编辑按钮点击回调
 * @param onDeleteClick 删除按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    onDismissRequest: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "周${getDayText(course.dayOfWeek)} 第${course.startSection}-${course.startSection + course.duration - 1}节",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            if (course.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = course.location,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (course.teacher.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = course.teacher,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${course.startWeek}-${course.endWeek}周 ${getWeekType(course.weekType)}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
            }
        }
    }
}

private fun getDayText(day: Int): String {
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

private fun getWeekType(type: Int): String {
    return when (type) {
        Course.WEEK_TYPE_ALL -> "(全)"
        Course.WEEK_TYPE_ODD -> "(单)"
        Course.WEEK_TYPE_EVEN -> "(双)"
        else -> ""
    }
}
