package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.DividerType
import com.dawncourse.core.ui.theme.LocalAppSettings
import com.dawncourse.core.ui.util.CourseColorUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 常量定义
const val TIMETABLE_START_HOUR = 8 // 起始时间 8:00
val TIME_COLUMN_WIDTH = 32.dp // 左侧时间轴宽度

/**
 * 课表网格中单个课程的布局信息
 *
 * 注意：课表网格采用绝对定位，如果同一天存在节次区间重叠的课程，
 * 需要通过“横向分栏（lane）”避免卡片覆盖显示。
 */
private data class TimetableLayoutItem(
    val course: Course,
    val isCurrentWeek: Boolean,
    val safeDayOfWeek: Int,
    val safeStartSection: Int,
    val safeEndSection: Int,
    val laneIndex: Int,
    val laneCount: Int
)

/**
 * 课表顶部操作栏
 *
 * 显示当前周次、周次切换下拉菜单以及常用功能入口（导入、添加、设置）。
 *
 * @param currentWeek 当前展示的周次
 * @param isRealCurrentWeek 是否为真实时间的本周
 * @param totalWeeks 学期总周数
 * @param onWeekSelected 周次选择回调
 * @param onSettingsClick 设置按钮点击回调
 * @param onAddClick 添加按钮点击回调
 * @param onImportClick 导入按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableTopBar(
    currentWeek: Int,
    isRealCurrentWeek: Boolean,
    totalWeeks: Int,
    onWeekSelected: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit
) {
    var showWeekMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showWeekMenu = true }
                ) {
                    Text(
                        text = "第 $currentWeek 周",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "切换周次",
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                if (isRealCurrentWeek) {
                    Text(
                        text = "本周",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                
                DropdownMenu(
                    expanded = showWeekMenu,
                    onDismissRequest = { showWeekMenu = false },
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    for (i in 1..totalWeeks) {
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    text = "第 $i 周" + if (i == currentWeek) " (当前展示)" else "",
                                    fontWeight = if (i == currentWeek) FontWeight.Bold else FontWeight.Normal
                                ) 
                            },
                            onClick = {
                                onWeekSelected(i)
                                showWeekMenu = false
                            }
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onImportClick) {
                Icon(Icons.Default.CloudDownload, contentDescription = "导入课程")
            }
            IconButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        modifier = Modifier
    )
}

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
    // 性能优化：将静态列表放入 remember 中，避免每次重组重复创建
    val days = remember { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日") }
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
 *
 * @param courses 课程列表
 * @param currentWeek 当前周次
 * @param modifier 修饰符
 * @param onCourseClick 课程点击回调
 */
@Composable
fun TimetableGrid(
    courses: List<Course>,
    currentWeek: Int,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit
) {
    val settings = LocalAppSettings.current
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

    // 1. 准备显示列表（仅负责“本周/非本周”决策，不在此阶段做布局运算）
    val rawDisplayList = remember(courses, currentWeek, settings.hideNonThisWeek) {
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
                // 移除周次范围限制，确保只要未开启"隐藏非本周"，所有时段的课程（包括已结课/未开始）都能显示
                group.maxByOrNull { it.id }?.let { list.add(it to false) }
            }
        }
        list
    }

    // 2. 生成最终布局列表：补齐边界、防止异常数据导致堆叠，并对节次区间重叠的课程做横向分栏
    val layoutItems = remember(rawDisplayList, maxNodes) {
        data class Tmp(
            val course: Course,
            val isCurrentWeek: Boolean,
            val safeDayOfWeek: Int,
            val safeStartSection: Int,
            val safeEndSection: Int
        )

        val normalized = rawDisplayList
            .map { (course, isCurrentWeek) ->
                val safeDay = course.dayOfWeek.coerceIn(1, 7)
                val safeStart = course.startSection.coerceIn(1, maxNodes)
                val safeDuration = course.duration.coerceAtLeast(1)
                val safeEnd = (safeStart + safeDuration - 1).coerceIn(1, maxNodes)
                Tmp(
                    course = course,
                    isCurrentWeek = isCurrentWeek,
                    safeDayOfWeek = safeDay,
                    safeStartSection = safeStart,
                    safeEndSection = safeEnd
                )
            }
            // 稳定排序：避免输入列表顺序变化导致测量/放置“看起来错位”
            .sortedWith(
                compareBy<Tmp>({ it.safeDayOfWeek }, { it.safeStartSection }, { it.safeEndSection }, { it.course.id })
            )

        val result = mutableListOf<TimetableLayoutItem>()

        // 按星期分组，分别处理每一列（避免跨天影响 lane 计算）
        val byDay = normalized.groupBy { it.safeDayOfWeek }.toSortedMap()
        byDay.forEach { (day, dayCourses) ->
            var i = 0
            while (i < dayCourses.size) {
                // 1) 构造“重叠簇”：同一星期内，只要节次区间存在重叠，就划入同一簇
                var clusterEnd = dayCourses[i].safeEndSection
                var j = i + 1
                while (j < dayCourses.size && dayCourses[j].safeStartSection <= clusterEnd) {
                    clusterEnd = max(clusterEnd, dayCourses[j].safeEndSection)
                    j++
                }

                val cluster = dayCourses.subList(i, j)

                // 2) 对重叠簇做横向分栏：同一簇内的课程会被分配到不同 lane，避免覆盖显示
                val laneEnds = mutableListOf<Int>()
                val assigned = mutableListOf<Pair<Tmp, Int>>()

                cluster.forEach { item ->
                    // 注意：节次区间是闭区间 [start, end]，所以 lane 可复用条件为 laneEnd < start
                    val laneIndex = laneEnds.indexOfFirst { laneEnd -> laneEnd < item.safeStartSection }
                    val finalLaneIndex = if (laneIndex >= 0) {
                        laneEnds[laneIndex] = item.safeEndSection
                        laneIndex
                    } else {
                        laneEnds.add(item.safeEndSection)
                        laneEnds.size - 1
                    }
                    assigned.add(item to finalLaneIndex)
                }

                val laneCount = laneEnds.size.coerceAtLeast(1)
                assigned.forEach { (item, laneIndex) ->
                    result.add(
                        TimetableLayoutItem(
                            course = item.course,
                            isCurrentWeek = item.isCurrentWeek,
                            safeDayOfWeek = day,
                            safeStartSection = item.safeStartSection,
                            safeEndSection = item.safeEndSection,
                            laneIndex = laneIndex,
                            laneCount = laneCount
                        )
                    )
                }

                i = j
            }
        }

        result
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
        if (layoutItems.isEmpty()) {
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
                    layoutItems.forEach { item ->
                        val course = item.course
                        androidx.compose.runtime.key(course.id) {
                            CourseCard(
                                course = course,
                                isCurrentWeek = item.isCurrentWeek,
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
                    val item = layoutItems[index]
                    val span = (item.safeEndSection - item.safeStartSection + 1).coerceAtLeast(1)
                    val height = (span * nodeHeightPx).roundToInt()

                    // 同一天列内，如果存在重叠课程，则按 laneCount 把列宽等分，避免覆盖
                    val baseWidth = cellWidth / item.laneCount
                    val remainder = cellWidth % item.laneCount
                    val placeableWidth = baseWidth + if (item.laneIndex < remainder) 1 else 0
                    
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
                        val item = layoutItems[index]
                        
                        // 计算位置
                        // X: (dayOfWeek - 1) * cellWidth + laneOffset
                        val dayX = (item.safeDayOfWeek - 1) * cellWidth
                        val baseWidth = cellWidth / item.laneCount
                        val remainder = cellWidth % item.laneCount
                        val laneExtraOffset = min(item.laneIndex, remainder)
                        val x = dayX + (item.laneIndex * baseWidth) + laneExtraOffset
                        
                        // Y: (startSection - 1) * nodeHeight
                        val y = ((item.safeStartSection - 1) * nodeHeightPx).roundToInt()
                        
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
                    lineHeight = 14.sp, // 稍微紧凑一点
                    color = (if (isCurrentWeek) Color(0xFF333333) else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                ),
                maxLines = if (isCurrentWeek) 2 else 3, // 减少行数预留空间给详情
                overflow = TextOverflow.Ellipsis
            )
            
            // 2. 底部信息块 (仅本周显示)
            if (isCurrentWeek) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp) // 极小的间距
                ) {
                    // 教室
                    if (course.location.isNotEmpty()) {
                        Text(
                            text = course.location,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = Color(0xFF49454F)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    // 老师
                    if (course.teacher.isNotEmpty()) {
                        Text(
                            text = course.teacher,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = Color(0xFF49454F)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
 * 假期模式视图
 *
 * 当当前日期超过学期总周数时显示。
 */
@Composable
fun HolidayView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BeachAccess,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 24.dp)
                    .alpha(0.8f),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = "好好享受假期吧！",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "本学期课程已全部结束，下学期也要加油哦。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}
