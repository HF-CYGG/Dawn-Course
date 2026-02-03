package com.dawncourse.feature.timetable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings

/**
 * 调课底部弹窗
 *
 * 引导用户完成调课流程的向导式界面。
 *
 * 流程：
 * 1. [WeekSelectionStep]: 选择需要调整的周次（例如：第 8 周老师请假）。
 * 2. [TimeLocationStep]: 设置新的上课时间、地点，并可调整目标周次（例如：补课到第 9 周）。
 * 3. [ConfirmStep]: 预览调整结果并确认。
 *
 * @param courseId 待调整的课程 ID
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CourseRescheduleSheet(
    courseId: Long,
    initialWeek: Int = 1,
    onDismissRequest: () -> Unit,
    viewModel: CourseRescheduleViewModel = hiltViewModel()
) {
    LaunchedEffect(courseId) {
        viewModel.loadCourse(courseId)
    }

    val uiState by viewModel.uiState.collectAsState()
    var currentStep by remember { mutableStateOf(RescheduleStep.SELECT_WEEKS) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding() // Handle system bars
        ) {
            // Title & Navigation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                if (currentStep != RescheduleStep.SELECT_WEEKS) {
                    IconButton(onClick = {
                        currentStep = when (currentStep) {
                            RescheduleStep.SET_TIME_LOCATION -> RescheduleStep.SELECT_WEEKS
                            RescheduleStep.CONFIRM -> RescheduleStep.SET_TIME_LOCATION
                            else -> RescheduleStep.SELECT_WEEKS
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                
                Text(
                    text = when (currentStep) {
                        RescheduleStep.SELECT_WEEKS -> "选择调整周次"
                        RescheduleStep.SET_TIME_LOCATION -> "设置新时间地点"
                        RescheduleStep.CONFIRM -> "确认调整"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = if (currentStep == RescheduleStep.SELECT_WEEKS) TextAlign.Start else TextAlign.Center
                )

                if (currentStep != RescheduleStep.SELECT_WEEKS) {
                     Spacer(modifier = Modifier.size(48.dp)) // Balance the back button
                }
            }
            
            // Stepper
            RescheduleStepper(currentStep = currentStep)
            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(targetState = currentStep, label = "step_transition") { step ->
                when (step) {
                    RescheduleStep.SELECT_WEEKS -> {
                        WeekSelectionStep(
                            uiState = uiState,
                            initialWeek = initialWeek,
                            onToggleWeek = viewModel::toggleWeekSelection,
                            onToggleSelectAll = viewModel::toggleSelectAllWeeks,
                            onToggleSelectOdd = viewModel::toggleSelectOddWeeks,
                            onToggleSelectEven = viewModel::toggleSelectEvenWeeks,
                            onNext = { 
                                viewModel.initTargetWeeks()
                                currentStep = RescheduleStep.SET_TIME_LOCATION 
                            }
                        )
                    }
                    RescheduleStep.SET_TIME_LOCATION -> {
                        TimeLocationStep(
                            uiState = uiState,
                            onTargetWeeksChange = viewModel::updateTargetWeeks,
                            onTimeChange = viewModel::updateNewTime,
                            onLocationChange = viewModel::updateNewLocation,
                            onNoteChange = viewModel::updateNote,
                            onNext = { currentStep = RescheduleStep.CONFIRM }
                        )
                    }
                    RescheduleStep.CONFIRM -> {
                        ConfirmStep(
                            uiState = uiState,
                            onConfirm = {
                                viewModel.confirmReschedule {
                                    onDismissRequest()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSelectionEntryCard(
    day: Int,
    startNode: Int,
    endNode: Int,
    conflictInfo: ConflictInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (conflictInfo.hasConflict) 
                MaterialTheme.colorScheme.errorContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Schedule, 
                contentDescription = null,
                tint = if (conflictInfo.hasConflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "新时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (conflictInfo.hasConflict) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "周${getDayText(day)} 第${startNode}-${endNode}节",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (conflictInfo.hasConflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                )
                if (conflictInfo.hasConflict) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ ${conflictInfo.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = if (conflictInfo.hasConflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimeSelectionDialog(
    uiState: RescheduleUiState,
    onTimeChange: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "选择新时间",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Show conflict message inside dialog too if needed, or just rely on grid visuals
                // The user wants "UI match", so maybe a small hint if conflict
                if (uiState.conflictInfo.hasConflict) {
                     Text(
                        text = uiState.conflictInfo.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(modifier = Modifier.weight(1f, fill = false)) {
                     TimeGridSelector(
                        selectedDay = uiState.newDay,
                        startNode = uiState.newStartNode,
                        duration = uiState.originalCourse?.duration ?: 2,
                        conflictSlots = uiState.conflictInfo.conflictSlots,
                        onSelectionChange = { day, start, _ -> onTimeChange(day, start) },
                        originalDay = uiState.originalCourse?.dayOfWeek ?: -1,
                        originalStartNode = uiState.originalCourse?.startSection ?: -1
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun RescheduleStepper(currentStep: RescheduleStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepItem(
            step = RescheduleStep.SELECT_WEEKS,
            currentStep = currentStep,
            label = "选择周次",
            modifier = Modifier.weight(1f)
        )
        StepDivider(active = currentStep.ordinal >= 1)
        StepItem(
            step = RescheduleStep.SET_TIME_LOCATION,
            currentStep = currentStep,
            label = "新时间",
            modifier = Modifier.weight(1f)
        )
        StepDivider(active = currentStep.ordinal >= 2)
        StepItem(
            step = RescheduleStep.CONFIRM,
            currentStep = currentStep,
            label = "确认",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepItem(
    step: RescheduleStep,
    currentStep: RescheduleStep,
    label: String,
    modifier: Modifier = Modifier
) {
    val isActive = step.ordinal <= currentStep.ordinal
    val isCurrent = step == currentStep
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary 
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (step.ordinal < currentStep.ordinal) {
                Icon(
                    Icons.Default.Check, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = "${step.ordinal + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun StepDivider(active: Boolean) {
    HorizontalDivider(
        modifier = Modifier.width(32.dp),
        thickness = 2.dp,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
private fun WeekSelectionStep(
    uiState: RescheduleUiState,
    initialWeek: Int,
    onToggleWeek: (Int) -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleSelectOdd: () -> Unit,
    onToggleSelectEven: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Quick Actions
        val available = uiState.availableWeeks
        val selected = uiState.selectedWeeks
        
        val isAllSelected = available.isNotEmpty() && selected.containsAll(available)
        val oddWeeks = available.filter { it % 2 != 0 }.toSet()
        val isOddSelected = oddWeeks.isNotEmpty() && selected == oddWeeks
        val evenWeeks = available.filter { it % 2 == 0 }.toSet()
        val isEvenSelected = evenWeeks.isNotEmpty() && selected == evenWeeks

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isAllSelected,
                onClick = onToggleSelectAll,
                label = { Text("全选") },
                leadingIcon = if (isAllSelected) { { Icon(Icons.Default.Check, null) } } else null
            )
            FilterChip(
                selected = isOddSelected,
                onClick = onToggleSelectOdd,
                label = { Text("单周") },
                leadingIcon = if (isOddSelected) { { Icon(Icons.Default.Check, null) } } else null
            )
            FilterChip(
                selected = isEvenSelected,
                onClick = onToggleSelectEven,
                label = { Text("双周") },
                leadingIcon = if (isEvenSelected) { { Icon(Icons.Default.Check, null) } } else null
            )
        }

        // Hint for current week
        if (uiState.availableWeeks.contains(initialWeek)) {
             Text(
                text = "💡 提示：您是从第 $initialWeek 周点击进入的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // Week Grid
        WeekGridSelector(
            availableWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(),
            enabledWeeks = uiState.availableWeeks, // Only original weeks are enabled for selection
            selectedWeeks = uiState.selectedWeeks,
            hintWeek = initialWeek,
            onToggleWeek = onToggleWeek,
            modifier = Modifier.heightIn(max = 300.dp)
        )

        Button(
            onClick = onNext,
            enabled = uiState.selectedWeeks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("下一步")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun TimeLocationStep(
    uiState: RescheduleUiState,
    onTargetWeeksChange: (Set<Int>) -> Unit,
    onTimeChange: (Int, Int) -> Unit,
    onLocationChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onNext: () -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    var showWeekPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (showWeekPicker) {
        TargetWeekPickerDialog(
            requiredCount = uiState.selectedWeeks.size,
            initialSelection = uiState.targetWeeks,
            onDismiss = { showWeekPicker = false },
            onConfirm = { 
                onTargetWeeksChange(it)
                showWeekPicker = false 
            }
        )
    }

    if (showTimePicker) {
        TimeSelectionDialog(
            uiState = uiState,
            onTimeChange = onTimeChange,
            onDismiss = { showTimePicker = false }
        )
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.verticalScroll(scrollState)
    ) {
        // Target Weeks Selection
        RescheduleInfoCard(
            sourceWeeks = uiState.selectedWeeks,
            targetWeeks = uiState.targetWeeks,
            onEditTarget = { showWeekPicker = true }
        )

        // Time Selector
        TimeSelectionEntryCard(
            day = uiState.newDay,
            startNode = uiState.newStartNode,
            endNode = uiState.newStartNode + (uiState.originalCourse?.duration ?: 0) - 1,
            conflictInfo = uiState.conflictInfo,
            onClick = { showTimePicker = true }
        )

        // Location & Note
        Card(
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
             modifier = Modifier.fillMaxWidth()
        ) {
             Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("其它信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = uiState.newLocation,
                    onValueChange = onLocationChange,
                    label = { Text("地点") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = onNoteChange,
                    label = { Text("备注 (选填)") },
                    placeholder = { Text("例如：老师出差补课") },
                    leadingIcon = { Icon(Icons.Default.DateRange, null) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
             }
        }

        Button(
            onClick = onNext,
            enabled = uiState.targetWeeks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("下一步")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun ConfirmStep(
    uiState: RescheduleUiState,
    onConfirm: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "即将进行以下调整",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                
                // Weeks
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("调整周次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(
                            text = "${uiState.selectedWeeks.sorted().joinToString(", ")} 周 \n→ ${uiState.targetWeeks.sorted().joinToString(", ")} 周",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // New Time
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("新时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(
                            text = "周${getDayText(uiState.newDay)} 第${uiState.newStartNode}-${uiState.newStartNode + (uiState.originalCourse?.duration ?: 0) - 1}节",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (uiState.conflictInfo.hasConflict) {
                            Text(
                                text = "⚠️ ${uiState.conflictInfo.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // New Location
                if (uiState.newLocation.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("新地点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text(
                                text = uiState.newLocation,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Note
                if (uiState.note.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("备注", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text(
                                text = uiState.note,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        val hasConflict = uiState.conflictInfo.hasConflict
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (hasConflict) Icons.Default.Info else Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (hasConflict) "仍要调整 (冲突)" else "确认调整")
        }
    }
}

@Composable
private fun WeekGridSelector(
    availableWeeks: Set<Int>,
    enabledWeeks: Set<Int>,
    selectedWeeks: Set<Int>,
    hintWeek: Int = -1,
    onToggleWeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(availableWeeks.toList()) { week ->
            val isEnabled = enabledWeeks.contains(week)
            val isSelected = selectedWeeks.contains(week)
            val isHint = week == hintWeek
            
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isEnabled -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                    // Add border for hint week if it's not selected
                    .then(
                         if (isHint && !isSelected) {
                             Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                         } else Modifier
                    )
                    .clickable(enabled = isEnabled) { onToggleWeek(week) }
            ) {
                Text(
                    text = week.toString(),
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        isEnabled -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected || isEnabled) FontWeight.Bold else FontWeight.Normal
                )

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            }
        }
    }
}

enum class RescheduleStep {
    SELECT_WEEKS,
    SET_TIME_LOCATION,
    CONFIRM
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

@Composable
private fun RescheduleInfoCard(
    sourceWeeks: Set<Int>,
    targetWeeks: Set<Int>,
    onEditTarget: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Source (Read-only)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "调整周次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "需变动的原周次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = sourceWeeks.sorted().joinToString(", ") { "$it" } + " 周",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Target (Interactive)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditTarget() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "目标周次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "调整到的新周次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (targetWeeks.isEmpty()) {
                        Text(
                            text = "点击设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = targetWeeks.sorted().joinToString(", ") { "$it" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " 周",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "修改",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetWeekPickerDialog(
    requiredCount: Int,
    initialSelection: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit
) {
    var currentSelection by remember { mutableStateOf(initialSelection) }
    val isValid = currentSelection.size == requiredCount
    
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "变更周次",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "原课程共 $requiredCount 周，请在下方选择 $requiredCount 个新的上课周。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                WeekGridSelector(
                    availableWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(),
                    enabledWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(),
                    selectedWeeks = currentSelection,
                    onToggleWeek = { week ->
                        currentSelection = if (currentSelection.contains(week)) {
                            currentSelection - week
                        } else {
                            currentSelection + week
                        }
                    },
                    modifier = Modifier.height(300.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val diff = currentSelection.size - requiredCount
                    val hintText = when {
                        diff < 0 -> "还差 ${-diff} 个"
                        diff > 0 -> "多了 $diff 个"
                        else -> "数量符合"
                    }
                    val hintColor = if (isValid) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    
                    Text(text = hintText, color = hintColor, style = MaterialTheme.typography.bodyMedium)
                    
                    Button(
                        onClick = { onConfirm(currentSelection) },
                        enabled = isValid
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
