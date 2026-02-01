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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.draw.alpha
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
import com.dawncourse.core.ui.util.CourseColorUtils
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

// 常量定义
// val NODE_HEIGHT = 56.dp // 单节课高度 (Moved to AppSettings)
const val TIMETABLE_START_HOUR = 8 // 起始时间 8:00
val TIME_COLUMN_WIDTH = 32.dp // 左侧时间轴宽度

/**
 * 周次头部栏组件
 *
 * 显示周一到周日，并高亮当前日期。
 * 如果设置中开启了日期显示，会根据学期开始日期计算并显示具体的月.日。
 *
 * @param modifier 修饰符
 * @param isCurrentWeek 是否为本周 (只有本周才高亮今天)
 * @param displayedWeek 当前显示的周次 (1-based)
 * @param semesterStartDate 学期开始日期
 */
@Composable
fun WeekHeader(
    modifier: Modifier = Modifier,
    isCurrentWeek: Boolean,
    displayedWeek: Int = 1,
    semesterStartDate: LocalDate? = null
) {
    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val today = LocalDate.now().dayOfWeek.value // 1 (Mon) - 7 (Sun)
    val settings = LocalAppSettings.current
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TIME_COLUMN_WIDTH) // 左侧时间轴偏移
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        days.forEachIndexed { index, day ->
            val dayValue = index + 1
            val isToday = isCurrentWeek && (dayValue == today)
            
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // 今天的高亮胶囊背景
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(42.dp) // 增加高度以容纳日期
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // 变淡
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (isToday) 14.sp else 12.sp // 选中放大
                        ),
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) // 选中色
                    )

                    // 日期显示 (例如 09.01)
                    if (settings.showDateInHeader && semesterStartDate != null) {
                        // 修复：确保基准日期是该学期第一周的周一
                        // 即使学期开始日期设置的是周三，第一周的周一也应该是该周的周一，而不是周三
                        val firstMonday = semesterStartDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        val date = firstMonday.plusWeeks((displayedWeek - 1).toLong())
                            .plusDays(index.toLong())
                        val dateText = "${date.monthValue}.${date.dayOfMonth}"
                        
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) 
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
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
    val settings = LocalAppSettings.current
    val maxNodes = settings.maxDailySections
    val nodeHeight = settings.courseItemHeightDp.dp

    Column(
        modifier = modifier
            .width(TIME_COLUMN_WIDTH), // 限制宽度
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (i in 1..maxNodes) {
            Column(
                modifier = Modifier.height(nodeHeight),
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
                // Show configured time or default
                val sectionTime = settings.sectionTimes.getOrNull(i - 1)
                val startTime = sectionTime?.startTime ?: "${TIMETABLE_START_HOUR + i - 1}:00"
                val endTime = sectionTime?.endTime
                
                Text(
                    text = startTime,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp, // Tiny
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray
                )
                
                if (endTime != null) {
                    Text(
                        text = endTime,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp, // Tiny
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray.copy(alpha = 0.8f)
                    )
                }
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
    val nodeHeight = settings.courseItemHeightDp.dp
    val totalHeight = nodeHeight * maxNodes
    
    val dividerColor = remember(settings.dividerColor, settings.dividerAlpha) {
        runCatching { Color(android.graphics.Color.parseColor(settings.dividerColor)) }
            .getOrElse { Color(0xFFE5E7EB) }
            .copy(alpha = settings.dividerAlpha)
    }

    val pathEffect = remember(settings.dividerType) {
        when (settings.dividerType) {
            DividerType.SOLID -> null
            DividerType.DASHED -> PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            DividerType.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
        }
    }

    // 1. 准备显示列表
    // 筛选并处理课程
    val displayList = remember(courses, currentWeek, settings.hideNonThisWeek) {
        val courseGroups = courses.groupBy { it.dayOfWeek to it.startSection }
        val list = mutableListOf<Pair<Course, Boolean>>() // 课程, 是否为本周
        courseGroups.forEach { (_, group) ->
            // 冲突解决逻辑：
            // 1. 优先显示本周课程
            // 2. 如果没有本周课程，且未开启"隐藏非本周"，则显示非本周课程
            // 3. 如果有多个非本周课程，显示 ID 最大的（通常是最新添加的）
            val currentWeekCourse = group.find { course ->
                // 判断逻辑：当前周在课程的起始周和结束周之间，且符合单双周规则
                currentWeek in course.startWeek..course.endWeek && when (course.weekType) {
                    Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
                    Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
                    else -> true
                }
            }

            if (currentWeekCourse != null) {
                list.add(currentWeekCourse to true)
            } else if (!settings.hideNonThisWeek) {
                // 显示非本周课程（取 ID 最大的一个作为代表）
                group.maxByOrNull { it.id }?.let {
                    list.add(it to false)
                }
            }
        }
        list
    }

    Box(
        modifier = modifier
            .height(totalHeight)
            .drawBehind {
                val width = size.width
                val nodeHeightPx = nodeHeight.toPx()

                // Draw horizontal lines
                for (i in 0..maxNodes) {
                    drawLine(
                        color = dividerColor, // Use color from settings (includes alpha)
                        start = Offset(0f, i * nodeHeightPx),
                        end = Offset(width, i * nodeHeightPx),
                        strokeWidth = settings.dividerWidthDp.dp.toPx(), // Use width from settings (dp)
                        pathEffect = pathEffect
                    )
                }
            }
    ) {
        if (displayList.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 60.dp), // 视觉中心修正
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "☕",
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "好好享受假期吧",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Layout(
                content = {
                    displayList.forEach { (course, isCurrentWeek) ->
                        androidx.compose.runtime.key(course.id) {
                            CourseCard(
                                course = course,
                                isCurrentWeek = isCurrentWeek,
                                onClick = { onCourseClick(course) }
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, constraints ->
                // 1. 计算基础尺寸
                val width = constraints.maxWidth
                val cellWidth = width / 7
                val nodeHeightPx = nodeHeight.toPx()
                
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
    // 1. 动态计算背景颜色
    val baseColor = if (isCurrentWeek) {
        CourseColorUtils.parseColor(CourseColorUtils.getCourseColor(course)).copy(alpha = 0.9f)
    } else {
        // 非本周：统一灰色且半透明 (极淡的灰色，避免抢眼)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    // 2. 动态计算内容透明度
    val contentAlpha = if (isCurrentWeek) 1f else 0.6f

    // 性能优化：使用 Box 替代 Card，移除阴影和不必要的 Surface 嵌套
    // 仅使用 clip + background + clickable 实现相同视觉效果，大幅减少渲染开销
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp) // 卡片之间的空隙
            .clip(RoundedCornerShape(12.dp)) // 大圆角
            .background(baseColor)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp), // 内部间距
            verticalArrangement = if (isCurrentWeek) Arrangement.SpaceBetween else Arrangement.Center // 非本周居中显示
        ) {
            // 1. 课程名
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrentWeek) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (isCurrentWeek) 12.sp else 11.sp,
                    lineHeight = 16.sp,
                    color = (if (isCurrentWeek) Color(0xFF333333) else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                ),
                maxLines = if (isCurrentWeek) 3 else 4,
                overflow = TextOverflow.Ellipsis
            )
            
            // 2. 底部信息块 (仅本周显示)
            if (isCurrentWeek) {
                Column {
                    // 教室
                    if (course.location.isNotEmpty()) {
                        Text(
                            text = course.location,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                color = Color(0xFF49454F)
                            )
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

        // 调课标记 (左上角)
        if (course.isModified) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(bottomEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "调",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
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
    onRescheduleClick: () -> Unit,
    onUndoRescheduleClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // 获取课程颜色
    val themePrimary = MaterialTheme.colorScheme.primary
    val settings = LocalAppSettings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 56.dp) // 底部留白增加，防止误触
        ) {
            // 1. 头部区域：显示课程颜色条和课程名称
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // 颜色指示条
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 32.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(themePrimary)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // 2. 信息网格：显示时间、地点、教师等详细信息
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 时间信息
                val startSectionIndex = course.startSection - 1
                val endSectionIndex = course.startSection + course.duration - 2
                // 如果配置了具体的作息时间，则显示具体的时间段 (e.g. 08:00-09:40)
                val timeRangeText = if (startSectionIndex >= 0 && endSectionIndex < settings.sectionTimes.size && startSectionIndex <= endSectionIndex) {
                     val start = settings.sectionTimes[startSectionIndex].startTime
                     val end = settings.sectionTimes[endSectionIndex].endTime
                     " ($start-$end)"
                } else {
                    ""
                }

                CourseDetailItem(
                    icon = Icons.Default.Schedule,
                    label = "时间",
                    value = "周${getDayText(course.dayOfWeek)} 第${course.startSection}-${course.startSection + course.duration - 1}节$timeRangeText",
                    iconTint = themePrimary
                )
                
                // 地点信息
                if (course.location.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Place,
                        label = "地点",
                        value = course.location,
                        iconTint = themePrimary
                    )
                }
                
                // 教师信息
                if (course.teacher.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Person,
                        label = "教师",
                        value = course.teacher,
                        iconTint = themePrimary
                    )
                }
                
                // 周次信息
                CourseDetailItem(
                    icon = Icons.Default.DateRange,
                    label = "周次",
                    value = "${course.startWeek}-${course.endWeek}周 ${getWeekType(course.weekType)}",
                    iconTint = themePrimary
                )
                
                // 备注信息
                if (course.note.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Info,
                        label = "备注",
                        value = course.note,
                        iconTint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
                // 3. 操作区域：调课、删除、编辑
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 调课/撤销调课 按钮
                    androidx.compose.material3.Button(
                        onClick = if (course.isModified) onUndoRescheduleClick else onRescheduleClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (course.isModified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                            contentColor = if (course.isModified) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (course.isModified) Icons.Default.Restore else Icons.Default.EditCalendar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    Text(if (course.isModified) "撤销调课 (还原)" else "调课 (部分周次变动)")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 删除按钮 (次要操作)
                    OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, 
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除")
                }
                
                // 编辑按钮 (主要操作)
                androidx.compose.material3.Button(
                    onClick = onEditClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themePrimary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("编辑")
                }
            }
            }
        }
    }
}

@Composable
private fun CourseDetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    iconTint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Icon Container
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
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
