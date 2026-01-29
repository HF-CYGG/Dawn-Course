package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawncourse.core.domain.model.Course
import kotlin.math.max
import kotlin.math.min

/**
 * 课程编辑/添加界面
 *
 * 提供课程信息的输入表单，包括基本信息、上课时间、周次和颜色。
 *
 * @param course 要编辑的课程对象，若为 null 则表示添加新课程
 * @param onBackClick 返回按钮点击回调
 * @param onSaveClick 保存按钮点击回调，传入编辑后的 Course 对象
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditorScreen(
    course: Course? = null,
    onBackClick: () -> Unit,
    onSaveClick: (Course) -> Unit
) {
    var name by remember(course) { mutableStateOf(course?.name ?: "") }
    var location by remember(course) { mutableStateOf(course?.location ?: "") }
    var teacher by remember(course) { mutableStateOf(course?.teacher ?: "") }
    
    // Time selection state
    var selectedDay by remember(course) { mutableStateOf(course?.dayOfWeek ?: 1) }
    var startNode by remember(course) { mutableStateOf(course?.startSection ?: 1) }
    var duration by remember(course) { mutableStateOf(course?.duration ?: 2) }
    
    // Week selection state
    var startWeek by remember(course) { mutableStateOf(course?.startWeek ?: 1) }
    var endWeek by remember(course) { mutableStateOf(course?.endWeek ?: 16) }
    var weekType by remember(course) { mutableStateOf(course?.weekType ?: Course.WEEK_TYPE_ALL) }
    
    // Color selection
    var selectedColor by remember(course) { mutableStateOf(course?.color ?: "#2196F3") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (course == null) "添加课程" else "编辑课程") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val newCourse = Course(
                            id = course?.id ?: 0,
                            name = name,
                            location = location,
                            teacher = teacher,
                            dayOfWeek = selectedDay,
                            startSection = startNode,
                            duration = duration,
                            startWeek = startWeek,
                            endWeek = endWeek,
                            weekType = weekType,
                            color = selectedColor
                        )
                        onSaveClick(newCourse)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Basic Info Card
            EditorSectionCard(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    trailingIcon = {
                        if (name.isNotEmpty()) {
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("上课地点") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("授课教师") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true
                )
            }
            
            // Time Selector Card
            EditorSectionCard(title = "上课时间") {
                Text(
                    text = "周${getDayText(selectedDay)} 第$startNode-${startNode + duration - 1}节",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TimeGridSelector(
                    selectedDay = selectedDay,
                    startNode = startNode,
                    duration = duration,
                    onSelectionChange = { day, start, dur ->
                        selectedDay = day
                        startNode = start
                        duration = dur
                    }
                )
            }

            // Week Selector Card
            EditorSectionCard(title = "上课周次") {
                // Shortcuts
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    FilterChip(
                        selected = weekType == Course.WEEK_TYPE_ALL,
                        onClick = { weekType = Course.WEEK_TYPE_ALL },
                        label = { Text("全周") }
                    )
                    FilterChip(
                        selected = weekType == Course.WEEK_TYPE_ODD,
                        onClick = { weekType = Course.WEEK_TYPE_ODD },
                        label = { Text("单周") }
                    )
                    FilterChip(
                        selected = weekType == Course.WEEK_TYPE_EVEN,
                        onClick = { weekType = Course.WEEK_TYPE_EVEN },
                        label = { Text("双周") }
                    )
                }
                
                // Week Toggles (1-20)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 1..20) {
                        val isSelected = i in startWeek..endWeek
                        // Determine if this week is active based on weekType
                        val isActive = isSelected && when (weekType) {
                            Course.WEEK_TYPE_ODD -> i % 2 != 0
                            Course.WEEK_TYPE_EVEN -> i % 2 == 0
                            else -> true
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        // Simple range logic
                                        if (i < startWeek) startWeek = i
                                        else if (i > endWeek) endWeek = i
                                        else {
                                            // inside range
                                            if (i == startWeek && i == endWeek) {
                                                // do nothing or unselect? Course requires valid range.
                                            } else {
                                                 if (i - startWeek < endWeek - i) startWeek = i else endWeek = i
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$i",
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Text(
                    text = "点击首尾周次可快速选择范围",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            // Color Selector Card
            EditorSectionCard(title = "课程颜色") {
                val colors = listOf(
                    "#F44336", "#E91E63", "#9C27B0", "#673AB7",
                    "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
                    "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
                    "#FFEB3B", "#FFC107", "#FF9800", "#FF5722"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    colors.forEach { colorHex ->
                        val isSelected = selectedColor.equals(colorHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(colorHex)))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures { selectedColor = colorHex }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

/**
 * 节次选择网格组件
 *
 * 显示 7x12 的微缩网格，用于选择上课时间（星期和节次）。
 */
@Composable
fun TimeGridSelector(
    selectedDay: Int,
    startNode: Int,
    duration: Int,
    onSelectionChange: (day: Int, start: Int, duration: Int) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 28.dp.toPx() }
    var dragAnchorDay by remember { mutableStateOf<Int?>(null) }
    var dragAnchorNode by remember { mutableStateOf<Int?>(null) }
    var dragAnchorOffsetY by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp)
    ) {
        // Header
        Row {
            Spacer(modifier = Modifier.width(32.dp))
            for (d in 1..7) {
                Text(
                    text = getDayText(d),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))

        // Grid
        for (node in 1..12) {
            Row(
                modifier = Modifier.height(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$node",
                    modifier = Modifier.width(32.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (day in 1..7) {
                    val isSelected = day == selectedDay && node >= startNode && node < startNode + duration
                    val isHead = day == selectedDay && node == startNode
                    val isTail = day == selectedDay && node == startNode + duration - 1
                    
                    // Shape logic for connected cells
                    val shape = when {
                        isHead && isTail -> RoundedCornerShape(4.dp)
                        isHead -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        isTail -> RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                        isSelected -> RoundedCornerShape(0.dp)
                        else -> RoundedCornerShape(2.dp)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(1.dp)
                            .fillMaxSize()
                            .clip(shape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    // Intelligent selection logic
                                    if (day == selectedDay) {
                                        if (node == startNode && duration > 1) {
                                            // Clicking head: do nothing or maybe reset?
                                            // Let's treat it as resetting to single cell
                                            onSelectionChange(day, node, 1)
                                        } else if (node >= startNode && node < startNode + duration) {
                                            // Clicking inside: shrink or cut?
                                            // Let's set start to clicked node
                                            onSelectionChange(day, node, 1)
                                        } else if (node > startNode + duration - 1) {
                                            // Clicking below: extend
                                            onSelectionChange(day, startNode, node - startNode + 1)
                                        } else if (node < startNode) {
                                            // Clicking above: new start
                                            onSelectionChange(day, node, 1)
                                        }
                                    } else {
                                        // Different day: new start
                                        onSelectionChange(day, node, 1)
                                    }
                                }
                            }
                            .pointerInput(day, node) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        dragAnchorDay = day
                                        dragAnchorNode = node
                                        dragAnchorOffsetY = offset.y
                                        onSelectionChange(day, node, 1)
                                    },
                                    onDrag = { change, _ ->
                                        val anchorDay = dragAnchorDay ?: day
                                        val anchorNode = dragAnchorNode ?: node
                                        val deltaRows = ((change.position.y - dragAnchorOffsetY) / rowHeightPx).toInt()
                                        val currentNode = (anchorNode + deltaRows).coerceIn(1, 12)
                                        val start = min(anchorNode, currentNode)
                                        val end = max(anchorNode, currentNode)
                                        onSelectionChange(anchorDay, start, end - start + 1)
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        dragAnchorDay = null
                                        dragAnchorNode = null
                                    },
                                    onDragCancel = {
                                        dragAnchorDay = null
                                        dragAnchorNode = null
                                    }
                                )
                            }
                    )
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
