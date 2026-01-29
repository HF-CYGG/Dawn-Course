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
import kotlin.math.roundToInt

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

// 常量定义
val NODE_HEIGHT = 56.dp // 单节课高度
val TIMETABLE_START_HOUR = 8 // 起始时间 8:00

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
            .padding(start = 32.dp) // Time column offset
            .padding(vertical = 8.dp)
    ) {
        days.forEachIndexed { index, day ->
            val dayValue = index + 1
            val isToday = dayValue == today
            
            Column(
                modifier = Modifier
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
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
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = 4.dp), // Align with grid
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (i in 1..12) {
            Column(
                modifier = Modifier.height(NODE_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = i.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${TIMETABLE_START_HOUR + i - 1}:00",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    // 假设每天最多 12 节课
    val maxNodes = 12
    val totalHeight = NODE_HEIGHT * maxNodes
    
    val settings = LocalAppSettings.current
    val dividerColor = try {
        Color(android.graphics.Color.parseColor(settings.dividerColor))
    } catch (e: Exception) { Color(0xFFE5E7EB) }.copy(alpha = settings.dividerAlpha)
    
    val strokeWidth = with(androidx.compose.ui.platform.LocalDensity.current) { settings.dividerWidth.dp.toPx() }
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
                val height = size.height
                val cellWidth = width / 7
                val nodeHeightPx = NODE_HEIGHT.toPx()

                // Draw vertical lines
                for (i in 1..7) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(i * cellWidth, 0f),
                        end = Offset(i * cellWidth, height),
                        strokeWidth = strokeWidth,
                        pathEffect = pathEffect
                    )
                }
                
                // Draw horizontal lines
                for (i in 0..maxNodes) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, i * nodeHeightPx),
                        end = Offset(width, i * nodeHeightPx),
                        strokeWidth = strokeWidth,
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
            
            // 宽度略微减小以留出间隙
            val placeableWidth = (cellWidth * 0.96f).roundToInt()
            
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
                val x = ((course.dayOfWeek - 1) * cellWidth) + (cellWidth * 0.02f).roundToInt()
                
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
    val containerColor = backgroundColor.copy(alpha = if (isCurrentWeek) 1f else 0.4f)
    
    // 根据背景色（不透明）计算最佳文本颜色，确保对比度
    val contentColor = CourseColorUtils.getBestContentColor(backgroundColor)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentWeek) 2.dp else 0.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(6.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp
                ),
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            Column(verticalArrangement = Arrangement.Bottom) {
                // Location (Priority 2: Display above teacher, allow 2 lines)
                if (course.location.isNotEmpty()) {
                    Text(
                        text = "@${course.location}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, // Bold for visibility
                        color = contentColor, // Full opacity
                        maxLines = 2, // Allow wrapping
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 13.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // Teacher (Priority 3: Smaller, below location)
                if (course.teacher.isNotEmpty()) {
                    Text(
                        text = course.teacher,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
