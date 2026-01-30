package com.dawncourse.feature.timetable

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 课程编辑/添加界面
 *
 * 提供课程信息的输入表单，包括基本信息、上课时间、周次和颜色。
 *
 * @param course 要编辑的课程对象，若为 null 则表示添加新课程
 * @param onBackClick 返回按钮点击回调
 * @param onSaveClick 保存按钮点击回调，传入编辑后的 Course 对象
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorScreen(
    course: Course? = null,
    currentSemesterId: Long = 1L,
    onBackClick: () -> Unit,
    onSaveClick: (Course) -> Unit
) {
    var name by remember(course) { mutableStateOf(course?.name ?: "") }
    var location by remember(course) { mutableStateOf(course?.location ?: "") }
    var teacher by remember(course) { mutableStateOf(course?.teacher ?: "") }
    
    // Time selection state
    val defaultDuration = LocalAppSettings.current.defaultCourseDuration
    var selectedDay by remember(course) { mutableStateOf(course?.dayOfWeek ?: 1) }
    var startNode by remember(course) { mutableStateOf(course?.startSection ?: 1) }
    var duration by remember(course) { mutableStateOf(course?.duration ?: defaultDuration) }
    
    // Week selection state
    var startWeek by remember(course) { mutableStateOf(course?.startWeek ?: 1) }
    var endWeek by remember(course) { mutableStateOf(course?.endWeek ?: 16) }
    var weekType by remember(course) { mutableStateOf(course?.weekType ?: Course.WEEK_TYPE_ALL) }
    
    // Color selection
    var selectedColor by remember(course) { mutableStateOf(course?.color ?: "#2196F3") }

    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (course == null) "添加课程" else "编辑课程", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val newCourse = Course(
                                id = course?.id ?: 0,
                                semesterId = course?.semesterId ?: currentSemesterId,
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
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Basic Info
            BasicInfoSection(
                name = name,
                onNameChange = { name = it },
                location = location,
                onLocationChange = { location = it },
                teacher = teacher,
                onTeacherChange = { teacher = it },
                onDone = { focusManager.clearFocus() }
            )
            
            // Time Selector
            TimeSection(
                selectedDay = selectedDay,
                startNode = startNode,
                duration = duration,
                onSelectionChange = { day, start, dur ->
                    selectedDay = day
                    startNode = start
                    duration = dur
                }
            )

            // Week Selector
            WeekSection(
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType,
                onRangeChange = { start, end ->
                    startWeek = start
                    endWeek = end
                },
                onTypeChange = { weekType = it }
            )
            
            // Color Selector
            ColorSection(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BasicInfoSection(
    name: String,
    onNameChange: (String) -> Unit,
    location: String,
    onLocationChange: (String) -> Unit,
    teacher: String,
    onTeacherChange: (String) -> Unit,
    onDone: () -> Unit
) {
    EditorSection(title = "基本信息", icon = Icons.Default.Edit) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("课程名称") },
                placeholder = { Text("例如：高等数学") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
                trailingIcon = {
                    if (name.isNotEmpty()) {
                        IconButton(onClick = { onNameChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                }
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = onLocationChange,
                    label = { Text("上课地点") },
                    placeholder = { Text("例如：教三 101") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
                
                OutlinedTextField(
                    value = teacher,
                    onValueChange = onTeacherChange,
                    label = { Text("授课教师") },
                    placeholder = { Text("选填") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
        }
    }
}

@Composable
private fun TimeSection(
    selectedDay: Int,
    startNode: Int,
    duration: Int,
    onSelectionChange: (day: Int, start: Int, duration: Int) -> Unit
) {
    EditorSection(title = "上课时间", icon = Icons.Default.Schedule) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "周${getDayText(selectedDay)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "第 $startNode - ${startNode + duration - 1} 节",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "共 ${duration} 节",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            TimeGridSelector(
                selectedDay = selectedDay,
                startNode = startNode,
                duration = duration,
                onSelectionChange = onSelectionChange
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekSection(
    startWeek: Int,
    endWeek: Int,
    weekType: Int,
    onRangeChange: (start: Int, end: Int) -> Unit,
    onTypeChange: (Int) -> Unit
) {
    val totalWeeks = LocalAppSettings.current.totalWeeks.coerceAtLeast(20)

    EditorSection(title = "上课周次", icon = Icons.Default.DateRange) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 1. 常用预设 (Quick Presets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    "1-16周" to (1 to 16),
                    "前8周" to (1 to 8),
                    "后8周" to (9 to 16),
                    "全学期" to (1 to totalWeeks)
                )
                
                presets.forEach { (label, range) ->
                    SuggestionChip(
                        onClick = { onRangeChange(range.first, range.second) },
                        label = { Text(label) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (startWeek == range.first && endWeek == range.second) 
                                MaterialTheme.colorScheme.secondaryContainer 
                            else Color.Transparent
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = if (startWeek == range.first && endWeek == range.second)
                                Color.Transparent
                            else MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            // 2. 范围滑块 (Range Slider)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "第 $startWeek - $endWeek 周",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // 周次类型选择 (Week Type)
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val types = listOf(
                            Course.WEEK_TYPE_ALL to "全周",
                            Course.WEEK_TYPE_ODD to "单周",
                            Course.WEEK_TYPE_EVEN to "双周"
                        )
                        
                        types.forEach { (type, label) ->
                            val isSelected = weekType == type
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { onTypeChange(type) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                RangeSlider(
                    value = startWeek.toFloat()..endWeek.toFloat(),
                    onValueChange = { range ->
                        val start = range.start.roundToInt().coerceIn(1, totalWeeks)
                        val end = range.endInclusive.roundToInt().coerceIn(1, totalWeeks)
                        if (start <= end) {
                            onRangeChange(start, end)
                        }
                    },
                    valueRange = 1f..totalWeeks.toFloat(),
                    steps = totalWeeks - 2, // steps = (max - min) / step - 1
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("第1周", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("第${totalWeeks}周", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSection(
    selectedColor: String,
    onColorSelected: (String) -> Unit
) {
    EditorSection(title = "课程颜色", icon = null) {
        val colors = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7",
            "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
            "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
            "#FFEB3B", "#FFC107", "#FF9800", "#FF5722",
            "#795548", "#9E9E9E", "#607D8B", "#333333"
        )
        
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            colors.forEach { colorHex ->
                val isSelected = selectedColor.equals(colorHex, ignoreCase = true)
                val color = Color(android.graphics.Color.parseColor(colorHex))
                
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onColorSelected(colorHex) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f))
                        )
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

@Composable
private fun EditorSection(
    title: String,
    icon: ImageVector?,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            content()
        }
    }
}

@Composable
fun TimeGridSelector(
    selectedDay: Int,
    startNode: Int,
    duration: Int,
    onSelectionChange: (day: Int, start: Int, duration: Int) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 32.dp.toPx() } // Increased touch target
    var dragAnchorDay by remember { mutableStateOf<Int?>(null) }
    var dragAnchorNode by remember { mutableStateOf<Int?>(null) }
    var dragAnchorOffsetY by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        // Header
        Row {
            Spacer(modifier = Modifier.width(32.dp))
            for (d in 1..7) {
                val isSelectedDay = d == selectedDay
                Text(
                    text = getDayText(d),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelectedDay) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelectedDay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // Grid
        for (node in 1..12) {
            Row(
                modifier = Modifier.height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$node",
                    modifier = Modifier.width(32.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                for (day in 1..7) {
                    val isSelected = day == selectedDay && node >= startNode && node < startNode + duration
                    val isHead = day == selectedDay && node == startNode
                    val isTail = day == selectedDay && node == startNode + duration - 1
                    
                    // Shape logic for connected cells
                    val shape = when {
                        isHead && isTail -> RoundedCornerShape(6.dp)
                        isHead -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                        isTail -> RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                        isSelected -> RoundedCornerShape(0.dp)
                        else -> RoundedCornerShape(4.dp)
                    }
                    
                    val color by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        label = "CellColor"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(1.dp)
                            .fillMaxSize()
                            .clip(shape)
                            .background(color)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    if (day == selectedDay) {
                                        if (node == startNode && duration > 1) {
                                            onSelectionChange(day, node, 1)
                                        } else if (node >= startNode && node < startNode + duration) {
                                            onSelectionChange(day, node, 1)
                                        } else if (node > startNode + duration - 1) {
                                            onSelectionChange(day, startNode, node - startNode + 1)
                                        } else if (node < startNode) {
                                            onSelectionChange(day, node, 1)
                                        }
                                    } else {
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
