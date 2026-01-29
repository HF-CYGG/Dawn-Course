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
import java.time.LocalDate
import kotlin.math.roundToInt

// 常量定义
val NODE_HEIGHT = 56.dp // 单节课高度
val TIMETABLE_START_HOUR = 8 // 起始时间 8:00

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
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit
) {
    // 假设每天最多 12 节课
    val maxNodes = 12
    val totalHeight = NODE_HEIGHT * maxNodes
    
    Layout(
        content = {
            courses.forEach { course ->
                CourseCard(
                    course = course,
                    onClick = { onCourseClick(course) }
                )
            }
        },
        modifier = modifier.height(totalHeight)
    ) { measurables, constraints ->
        // 1. 计算基础尺寸
        val width = constraints.maxWidth
        val cellWidth = width / 7
        val nodeHeightPx = NODE_HEIGHT.toPx()
        
        // 2. 预先计算布局信息 (处理冲突)
        // 简单的冲突处理：如果同一天同一节有多个课程，则重叠显示或暂不处理（高级冲突处理需要复杂算法）
        // 这里我们简单实现：计算位置，不处理重叠时的宽度缩减（后续可优化）
        
        // 3. 测量所有子元素
        val placeables = measurables.mapIndexed { index, measurable ->
            val course = courses[index]
            val span = course.duration
            val height = (span * nodeHeightPx).roundToInt()
            
            // 宽度略微减小以留出间隙
            val placeableWidth = (cellWidth * 0.95f).roundToInt()
            
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
                val course = courses[index]
                
                // 计算位置
                // X: (dayOfWeek - 1) * cellWidth
                val x = ((course.dayOfWeek - 1) * cellWidth) + (cellWidth * 0.025f).roundToInt()
                
                // Y: (startSection - 1) * nodeHeight
                val y = ((course.startSection - 1) * nodeHeightPx).roundToInt()
                
                placeable.place(x, y)
            }
        }
    }
}

@Composable
fun CourseCard(
    course: Course,
    onClick: () -> Unit
) {
    val backgroundColor = if (course.color.isNotEmpty()) {
        try {
            Color(android.graphics.Color.parseColor(course.color))
        } catch (e: Exception) {
            MaterialTheme.colorScheme.secondaryContainer
        }
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (course.location.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "@${course.location}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

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
