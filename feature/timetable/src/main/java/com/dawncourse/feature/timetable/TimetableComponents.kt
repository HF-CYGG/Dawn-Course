package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.dawncourse.core.ui.components.AnimatedDropdownMenu
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
 * 课表顶部操作栏
 *
 * 显示当前周次、周次切换下拉菜单以及常用功能入口（导入、添加、设置）。
 *
 * @param displayedWeek 当前展示的周次（用户正在查看的周次）
 * @param realCurrentWeek 当前日期所属的真实周次（用于“当前”标记与本周提示）
 * @param isHolidayMode 是否处于假期模式（当前日期已超过学期总周数）
 * @param totalWeeks 学期总周数
 * @param onWeekSelected 周次选择回调
 * @param onSettingsClick 设置按钮点击回调
 * @param onAddClick 添加按钮点击回调
 * @param onImportClick 导入按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableTopBar(
    displayedWeek: Int,
    realCurrentWeek: Int,
    isHolidayMode: Boolean,
    totalWeeks: Int,
    onWeekSelected: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    var showWeekMenu by remember { mutableStateOf(false) }
    val weekMenuScrollState = rememberScrollState()

    TopAppBar(
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showWeekMenu = true }
                ) {
                    Text(
                        text = if (isHolidayMode) "假期中" else "第 $displayedWeek 周",
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
                if (isHolidayMode) {
                    Text(
                        text = "当前展示：第 $displayedWeek 周",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                } else if (displayedWeek == realCurrentWeek) {
                    Text(
                        text = "本周",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                
                AnimatedDropdownMenu(
                    expanded = showWeekMenu,
                    onDismissRequest = { showWeekMenu = false },
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .width(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "选择周次",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { },
                        enabled = false
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(weekMenuScrollState)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        for (i in 1..totalWeeks) {
                            val isDisplayedWeek = i == displayedWeek
                            val isRealCurrent = i == realCurrentWeek
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "第 $i 周",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isDisplayedWeek) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        if (isRealCurrent) {
                                            Text(
                                                text = "当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                },
                                leadingIcon = if (isDisplayedWeek) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    onWeekSelected(i)
                                    showWeekMenu = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isDisplayedWeek) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        } else {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onSyncClick) {
                Icon(Icons.Default.Refresh, contentDescription = "一键更新")
            }
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

    // 1. 准备显示列表并生成布局项
    val layoutItems = remember(courses, currentWeek, settings.hideNonThisWeek, maxNodes) {
        TimetableLayoutEngine.calculateLayoutItems(
            courses = courses,
            currentWeek = currentWeek,
            maxNodes = maxNodes,
            hideNonThisWeek = settings.hideNonThisWeek
        )
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
                val cellWidth = width / 7f
                val nodeHeightPx = nodeHeight.toPx()
                
                // 2. 测量所有子元素
                val placeables = measurables.mapIndexed { index, measurable ->
                    val item = layoutItems[index]
                    val span = (item.safeEndSection - item.safeStartSection + 1).coerceAtLeast(1)
                    val height = (span * nodeHeightPx).roundToInt()

                    // 同一天列内，如果存在重叠课程，则按 laneCount 把列宽等分，避免覆盖
                    val placeableWidth = (cellWidth / item.laneCount).roundToInt()
                    
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
                        val dayX = ((item.safeDayOfWeek - 1) * cellWidth).roundToInt()
                        val laneWidth = cellWidth / item.laneCount
                        val x = dayX + (item.laneIndex * laneWidth).roundToInt()
                        
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
        // 非本周：使用课程色的浅色填充
        CourseColorUtils.parseColor(CourseColorUtils.getCourseColor(course)).copy(alpha = 0.18f)
    }

    // 2. 动态计算内容透明度
    val contentAlpha = if (isCurrentWeek) 1f else 0.75f

    // 3. 非本周不再使用描边，保持卡片为纯色块
    val borderModifier = Modifier

    // 性能优化：使用 BoxWithConstraints 替代 Box 以支持响应式布局
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp) // 卡片之间的空隙
            .clip(RoundedCornerShape(12.dp)) // 大圆角
            .background(baseColor)
            .then(borderModifier) // 应用边框
            .clickable(onClick = onClick)
    ) {
        // 宽度检测：如果宽度小于 30dp，则隐藏详细信息以避免拥挤
        val showDetails = maxWidth >= 30.dp && isCurrentWeek

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp), // 内部间距
            verticalArrangement = if (showDetails) Arrangement.SpaceBetween else Arrangement.Center // 根据内容量决定对齐方式
        ) {
            // 1. 课程名
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrentWeek) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (isCurrentWeek) 12.sp else 11.sp,
                    lineHeight = 14.sp, // 稍微紧凑一点
                    color = (if (isCurrentWeek) Color(0xFF333333) else Color(0xFF333333)).copy(alpha = contentAlpha)
                ),
                maxLines = if (showDetails) 2 else 3, // 减少行数预留空间给详情
                overflow = TextOverflow.Ellipsis
            )
            
            // 2. 底部信息块 (仅本周且宽度足够时显示)
            if (showDetails) {
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
