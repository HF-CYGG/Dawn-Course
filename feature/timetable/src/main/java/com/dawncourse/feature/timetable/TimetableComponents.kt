package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import com.dawncourse.core.domain.model.Course
import kotlin.math.roundToInt

// 常量定义
val NODE_HEIGHT = 56.dp // 单节课高度
val TIMETABLE_START_HOUR = 8 // 起始时间 8:00

/**
 * 课表网格布局
 *
 * 使用自定义 Layout 实现，根据课程的星期和节次进行绝对定位。
 *
 * @param courses 课程列表
 * @param modifier 修饰符
 * @param onCourseClick 课程点击回调
 */
@Composable
fun TimetableGrid(
    courses: List<Course>,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit
) {
    // 假设每天最多 12 节课
    val maxNodes = 12
    
    Layout(
        content = {
            courses.forEach { course ->
                CourseCard(
                    course = course,
                    onClick = { onCourseClick(course) }
                )
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        // 1. 计算基础尺寸
        val width = constraints.maxWidth
        // 将宽度平均分为 7 份（周一到周日）
        val cellWidth = width / 7
        val nodeHeightPx = NODE_HEIGHT.toPx()
        
        // 2. 测量所有子元素 (Course Cards)
        val placeables = measurables.mapIndexed { index, measurable ->
            val course = courses[index]
            // 计算该课程的高度：跨度 * 单节高度
            // span = endNode - startNode + 1
            // 注意：Course 模型中使用 startSection 和 duration
            val startNode = course.startSection
            val span = course.duration
            val height = (span * nodeHeightPx).roundToInt()
            
            // 强制卡片尺寸
            measurable.measure(
                constraints.copy(
                    minWidth = cellWidth,
                    maxWidth = cellWidth,
                    minHeight = height,
                    maxHeight = height
                )
            )
        }

        // 3. 计算布局总高度
        val totalHeight = (maxNodes * nodeHeightPx).roundToInt()

        // 4. 放置子元素
        layout(width, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                val course = courses[index]
                
                // 计算 X 坐标：(dayOfWeek - 1) * cellWidth
                // dayOfWeek 范围 1-7
                val dayOfWeekIndex = (course.dayOfWeek - 1).coerceIn(0, 6)
                val x = dayOfWeekIndex * cellWidth
                
                // 计算 Y 坐标：(startNode - 1) * nodeHeight
                val startNode = course.startSection
                val startNodeIndex = (startNode - 1).coerceAtLeast(0)
                val y = (startNodeIndex * nodeHeightPx).roundToInt()
                
                placeable.placeRelative(x = x, y = y)
            }
        }
    }
}

/**
 * 单个课程卡片组件
 *
 * 设计风格：
 * - 无边框
 * - 半透明背景 (Surface Variant)
 * - 圆角矩形
 */
@Composable
fun CourseCard(
    course: Course,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 动态生成卡片背景色（这里暂时使用单一颜色，后续可根据课程属性生成）
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = modifier
            .padding(1.dp) // 卡片间隙
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)) // 圆角
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(4.dp) // 内容内边距
    ) {
        Column {
            // 课程名称
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            
            // 教室地点 (如果高度允许显示)
            // span = duration
            if (course.duration >= 2) {
                Text(
                    text = "@${course.location}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp
                    ),
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * 左侧时间轴组件
 * 显示 1-12 的数字序列
 */
@Composable
fun TimeColumn(
    modifier: Modifier = Modifier
) {
    val maxNodes = 12
    
    Column {
        repeat(maxNodes) { index ->
            val nodeIndex = index + 1
            Box(
                modifier = Modifier
                    .padding(vertical = 1.dp)
                    .fillMaxSize() // 填充高度，这里需要父布局配合
                    // 或者固定高度
            ) {
               // 这里稍后在主布局中处理，为了对齐方便，建议 TimeColumn 也使用类似的 Layout 或者固定高度
               // 简单起见，这里只定义数据结构，UI 在主界面组装
            }
        }
    }
}

/**
 * 时间轴指示器 (左侧列)
 *
 * 显示节次数字和时间。
 * 每个 Item 高度必须与 CourseCard 的网格高度严格对齐 (NODE_HEIGHT)。
 */
@Composable
fun TimeColumnIndicator(
    modifier: Modifier = Modifier
) {
    val maxNodes = 12
    val startHour = TIMETABLE_START_HOUR
    
    Column(
        modifier = modifier
            .padding(top = 0.dp) // 与网格顶部对齐
    ) {
        repeat(maxNodes) { index ->
            val nodeIndex = index + 1
            // 简单的时间计算：每节课假设 1 小时 (演示用)
            // 实际项目应从配置读取
            val timeText = "${startHour + index}:00"
            
            Box(
                modifier = Modifier
                    .height(NODE_HEIGHT),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(
                        text = nodeIndex.toString(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

/**
 * 星期栏头部
 *
 * 显示周一到周日。
 */
@Composable
fun WeekHeader(
    modifier: Modifier = Modifier
) {
    val weeks = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val todayIndex = java.time.LocalDate.now().dayOfWeek.value - 1 // 0-6
    
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(vertical = 8.dp)
    ) {
        // 左侧留白给时间轴
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(32.dp))
        
        weeks.forEachIndexed { index, weekName ->
            val isToday = index == todayIndex
            val textColor = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = weekName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = textColor
                    )
                )
                // 这里可以添加日期显示
            }
        }
    }
}

/**
 * 课程详情底部弹窗 (Bottom Sheet)
 *
 * 显示课程的详细信息，并提供编辑和删除操作。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CourseDetailSheet(
    course: Course,
    onDismissRequest: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp) // 底部留白
        ) {
            // 1. 课程名称
            Text(
                text = course.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
            
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
            
            // 2. 信息列表
            val endNode = course.startSection + course.duration - 1
            DetailInfoRow(
                icon = androidx.compose.material.icons.Icons.Default.Schedule,
                label = "时间",
                text = "周${course.dayOfWeek} 第${course.startSection}-${endNode}节"
            )
            
            DetailInfoRow(
                icon = androidx.compose.material.icons.Icons.Default.Place,
                label = "地点",
                text = course.location
            )
            
            DetailInfoRow(
                icon = androidx.compose.material.icons.Icons.Default.Person,
                label = "老师",
                text = course.teacher
            )
            
            DetailInfoRow(
                icon = androidx.compose.material.icons.Icons.Default.DateRange,
                label = "周次",
                text = "${course.startWeek}-${course.endWeek}周"
            )
            
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(32.dp))
            
            // 3. 操作按钮
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize()
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onEditClick,
                    modifier = Modifier.weight(1f)
                ) {
                    androidx.compose.material.icons.Icons.Default.Edit
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text("编辑")
                }
                
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(16.dp))
                
                androidx.compose.material3.TextButton(
                    onClick = onDeleteClick,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    androidx.compose.material.icons.Icons.Default.Delete
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    text: String
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxSize(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
