package com.dawncourse.feature.timetable

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings
import com.dawncourse.core.ui.util.CourseColorUtils
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
    onSaveClick: (List<Course>) -> Unit
) {
    var name by remember(course) { mutableStateOf(course?.name ?: "") }
    var location by remember(course) { mutableStateOf(course?.location ?: "") }
    var teacher by remember(course) { mutableStateOf(course?.teacher ?: "") }

    val settings = LocalAppSettings.current
    val defaultDuration = settings.defaultCourseDuration
    val totalWeeks = settings.totalWeeks.coerceAtLeast(20)

    // 初始状态：如果是在编辑现有课程，则加载其时间段；如果是新建，则创建默认时间段
    val initialSlot = remember(course, defaultDuration, totalWeeks) {
        val startWeek = course?.startWeek ?: 1
        val endWeek = course?.endWeek ?: totalWeeks
        val weekType = course?.weekType ?: Course.WEEK_TYPE_ALL
        TimeSlotState(
            dayOfWeek = course?.dayOfWeek ?: 1,
            startSection = course?.startSection ?: 1,
            duration = course?.duration ?: defaultDuration,
            selectedWeeks = buildWeeksFromRange(startWeek, endWeek, weekType)
        )
    }
    // 使用列表支持多时间段编辑 (目前 UI 仅支持添加，逻辑层已预留)
    var timeSlots by remember(course, defaultDuration, totalWeeks) { mutableStateOf(listOf(initialSlot)) }

    val initialColor = remember(course) {
        course?.color?.takeIf { it.isNotBlank() } ?: CourseColorUtils.generateColor(course?.name ?: "", course?.teacher)
    }
    var selectedColor by remember(course) { mutableStateOf(initialColor) }
    var isColorLocked by remember(course) { mutableStateOf(course?.color?.isNotBlank() == true) }

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
                            val semesterId = course?.semesterId ?: currentSemesterId
                            val coursesToSave = timeSlots.flatMapIndexed { slotIndex, slot ->
                                val segments = convertWeeksToSegments(slot.selectedWeeks)
                                segments.mapIndexed { segmentIndex, segment ->
                                    Course(
                                        id = if (course != null && slotIndex == 0 && segmentIndex == 0) course.id else 0L,
                                        semesterId = semesterId,
                                        name = name.trim(),
                                        location = location.trim(),
                                        teacher = teacher.trim(),
                                        dayOfWeek = slot.dayOfWeek,
                                        startSection = slot.startSection,
                                        duration = slot.duration,
                                        startWeek = segment.startWeek,
                                        endWeek = segment.endWeek,
                                        weekType = segment.weekType,
                                        color = selectedColor
                                    )
                                }
                            }
                            onSaveClick(coursesToSave)
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
                onNameChange = { newName ->
                    name = newName
                    if (!isColorLocked) {
                        selectedColor = CourseColorUtils.generateColor(newName, teacher)
                    }
                },
                location = location,
                onLocationChange = { location = it },
                teacher = teacher,
                onTeacherChange = { teacher = it },
                onDone = { focusManager.clearFocus() }
            )
            
            // Color Selector
            ColorSection(
                selectedColor = selectedColor,
                onColorSelected = {
                    selectedColor = it
                    isColorLocked = true
                }
            )

            TimeSlotSection(
                timeSlots = timeSlots,
                totalWeeks = totalWeeks,
                defaultDuration = defaultDuration,
                allowMultiple = course == null,
                onSlotsChange = { timeSlots = it }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private data class TimeSlotState(
    val dayOfWeek: Int,
    val startSection: Int,
    val duration: Int,
    val selectedWeeks: Set<Int>
)

@Composable
private fun TimeSlotSection(
    timeSlots: List<TimeSlotState>,
    totalWeeks: Int,
    defaultDuration: Int,
    allowMultiple: Boolean,
    onSlotsChange: (List<TimeSlotState>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        timeSlots.forEachIndexed { index, slot ->
            EditorSection(
                title = if (allowMultiple) "时间段 ${index + 1}" else "时间段",
                icon = Icons.Default.Schedule
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WeekSection(
                        selectedWeeks = slot.selectedWeeks,
                        totalWeeks = totalWeeks,
                        onWeeksChange = { weeks ->
                            val updated = timeSlots.toMutableList()
                            updated[index] = slot.copy(selectedWeeks = weeks)
                            onSlotsChange(updated)
                        }
                    )
                    TimeSection(
                        selectedDay = slot.dayOfWeek,
                        startNode = slot.startSection,
                        duration = slot.duration,
                        onSelectionChange = { day, start, duration ->
                            val updated = timeSlots.toMutableList()
                            updated[index] = slot.copy(dayOfWeek = day, startSection = start, duration = duration)
                            onSlotsChange(updated)
                        }
                    )
                    if (allowMultiple && timeSlots.size > 1) {
                        TextButton(
                            onClick = {
                                val updated = timeSlots.toMutableList()
                                updated.removeAt(index)
                                onSlotsChange(updated)
                            },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("移除此时间段")
                        }
                    }
                }
            }
        }

        if (allowMultiple) {
            Button(
                onClick = {
                    val updated = timeSlots + TimeSlotState(
                        dayOfWeek = 1,
                        startSection = 1,
                        duration = defaultDuration,
                        selectedWeeks = (1..totalWeeks).toSet()
                    )
                    onSlotsChange(updated)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("添加上课时间")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    selectedWeeks: Set<Int>,
    totalWeeks: Int,
    onWeeksChange: (Set<Int>) -> Unit
) {
    val summary by remember(selectedWeeks) {
        derivedStateOf { formatWeekSummary(selectedWeeks) }
    }

    EditorSection(title = "上课周次", icon = Icons.Default.DateRange) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = {
                        val allWeeks = (1..totalWeeks).toSet()
                        if (selectedWeeks.containsAll(allWeeks)) {
                            onWeeksChange(emptySet())
                        } else {
                            onWeeksChange(allWeeks)
                        }
                    },
                    label = { Text("全选") }
                )
                SuggestionChip(
                    onClick = {
                        val oddWeeks = buildWeeksFromRange(1, totalWeeks, Course.WEEK_TYPE_ODD)
                        if (selectedWeeks.containsAll(oddWeeks)) {
                            onWeeksChange(selectedWeeks - oddWeeks)
                        } else {
                            onWeeksChange(selectedWeeks + oddWeeks)
                        }
                    },
                    label = { Text("单周") }
                )
                SuggestionChip(
                    onClick = {
                        val evenWeeks = buildWeeksFromRange(1, totalWeeks, Course.WEEK_TYPE_EVEN)
                        if (selectedWeeks.containsAll(evenWeeks)) {
                            onWeeksChange(selectedWeeks - evenWeeks)
                        } else {
                            onWeeksChange(selectedWeeks + evenWeeks)
                        }
                    },
                    label = { Text("双周") }
                )
            }

            DotMatrixWeekSelector(
                totalWeeks = totalWeeks,
                selectedWeeks = selectedWeeks,
                onWeekToggle = { week ->
                    val updated = selectedWeeks.toMutableSet()
                    if (updated.contains(week)) {
                        updated.remove(week)
                    } else {
                        updated.add(week)
                    }
                    onWeeksChange(updated)
                }
            )
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
    conflictSlots: Set<Pair<Int, Int>> = emptySet(),
    onSelectionChange: (day: Int, start: Int, duration: Int) -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rowHeightPx = with(density) { 32.dp.toPx() } // Increased touch target
    var dragAnchorDay by remember { mutableStateOf<Int?>(null) }
    var dragAnchorNode by remember { mutableStateOf<Int?>(null) }
    var dragAnchorOffsetY by remember { mutableStateOf(0f) }

    // Helper to perform haptic and update
    val updateSelection = { day: Int, start: Int, dur: Int ->
        if (day != selectedDay || start != startNode || dur != duration) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelectionChange(day, start, dur)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                for (day in 1..7) {
                    val isSelected = day == selectedDay && node >= startNode && node < startNode + duration
                    val isConflict = conflictSlots.contains(day to node)
                    
                    val isHead = day == selectedDay && node == startNode
                    val isTail = day == selectedDay && node == startNode + duration - 1
                    
                    // Shape logic for connected cells
                    val shape = when {
                        isHead && isTail -> RoundedCornerShape(8.dp)
                        isHead -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
                        isTail -> RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 2.dp, topEnd = 2.dp)
                        isSelected -> RoundedCornerShape(2.dp) // Connected middle parts
                        isConflict -> RoundedCornerShape(4.dp)
                        else -> CircleShape
                    }
                    
                    val color by animateColorAsState(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isConflict -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            else -> Color.Transparent
                        },
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
                                            updateSelection(day, node, 1)
                                        } else if (node >= startNode && node < startNode + duration) {
                                            updateSelection(day, node, 1)
                                        } else if (node > startNode + duration - 1) {
                                            updateSelection(day, startNode, node - startNode + 1)
                                        } else if (node < startNode) {
                                            updateSelection(day, node, 1)
                                        }
                                    } else {
                                        updateSelection(day, node, 1)
                                    }
                                }
                            }
                            .pointerInput(day, node) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragAnchorDay = day
                                        dragAnchorNode = node
                                        dragAnchorOffsetY = offset.y
                                        updateSelection(day, node, 1)
                                    },
                                    onDrag = { change, _ ->
                                        val anchorDay = dragAnchorDay ?: day
                                        val anchorNode = dragAnchorNode ?: node
                                        val deltaRows = ((change.position.y - dragAnchorOffsetY) / rowHeightPx).toInt()
                                        val currentNode = (anchorNode + deltaRows).coerceIn(1, 12)
                                        val start = min(anchorNode, currentNode)
                                        val end = max(anchorNode, currentNode)
                                        updateSelection(anchorDay, start, end - start + 1)
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
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isSelected && !isConflict) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                            )
                        }
                    }
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

private data class WeekSegment(
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int
)

private fun buildWeeksFromRange(startWeek: Int, endWeek: Int, weekType: Int): Set<Int> {
    val weeks = mutableSetOf<Int>()
    for (week in startWeek..endWeek) {
        if (weekType == Course.WEEK_TYPE_ODD && week % 2 == 0) continue
        if (weekType == Course.WEEK_TYPE_EVEN && week % 2 != 0) continue
        weeks.add(week)
    }
    return weeks
}

private fun convertWeeksToSegments(weeks: Set<Int>): List<WeekSegment> {
    if (weeks.isEmpty()) return emptyList()
    val pending = weeks.sorted().toMutableSet()
    val segments = mutableListOf<WeekSegment>()
    while (pending.isNotEmpty()) {
        val first = pending.minOrNull() ?: break
        var endAll = first
        while (pending.contains(endAll + 1)) {
            endAll++
        }
        val countAll = endAll - first + 1

        var endParity = first
        while (pending.contains(endParity + 2)) {
            endParity += 2
        }
        val countParity = (endParity - first) / 2 + 1

        if (countAll >= countParity) {
            segments.add(WeekSegment(first, endAll, Course.WEEK_TYPE_ALL))
            for (week in first..endAll) {
                pending.remove(week)
            }
        } else {
            val type = if (first % 2 != 0) Course.WEEK_TYPE_ODD else Course.WEEK_TYPE_EVEN
            segments.add(WeekSegment(first, endParity, type))
            var current = first
            while (current <= endParity) {
                pending.remove(current)
                current += 2
            }
        }
    }
    return segments.sortedBy { it.startWeek }
}

/**
 * 格式化周次摘要
 *
 * 将离散的周次集合格式化为易读的字符串。
 * 例如：{1, 2, 3, 5, 7} -> "1-3周(全)、5周(单)、7周(单)"
 */
private fun formatWeekSummary(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "未选择周次"
    return convertWeeksToSegments(weeks).joinToString("、") { segment ->
        val typeText = when (segment.weekType) {
            Course.WEEK_TYPE_ODD -> "单"
            Course.WEEK_TYPE_EVEN -> "双"
            else -> "全"
        }
        if (segment.startWeek == segment.endWeek) {
            "第${segment.startWeek}周($typeText)"
        } else {
            "${segment.startWeek}-${segment.endWeek}周($typeText)"
        }
    }
}

@Composable
private fun DotMatrixWeekSelector(
    totalWeeks: Int,
    selectedWeeks: Set<Int>,
    onWeekToggle: (Int) -> Unit
) {
    val columns = 5
    val rows = (totalWeeks + columns - 1) / columns

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        for (row in 0 until rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (col in 0 until columns) {
                    val week = row * columns + col + 1
                    if (week <= totalWeeks) {
                        val isSelected = selectedWeeks.contains(week)
                        val background by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            label = "WeekCellColor"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(background)
                                .clickable { onWeekToggle(week) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = week.toString(),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}
