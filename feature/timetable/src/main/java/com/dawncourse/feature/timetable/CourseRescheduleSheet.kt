package com.dawncourse.feature.timetable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
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

            AnimatedContent(targetState = currentStep, label = "step_transition") { step ->
                when (step) {
                    RescheduleStep.SELECT_WEEKS -> {
                        WeekSelectionStep(
                            uiState = uiState,
                            onToggleWeek = viewModel::toggleWeekSelection,
                            onSelectAll = viewModel::selectAllWeeks,
                            onSelectOdd = viewModel::selectOddWeeks,
                            onSelectEven = viewModel::selectEvenWeeks,
                            onNext = { 
                                viewModel.initTargetWeeks()
                                currentStep = RescheduleStep.SET_TIME_LOCATION 
                            }
                        )
                    }
                    RescheduleStep.SET_TIME_LOCATION -> {
                        TimeLocationStep(
                            uiState = uiState,
                            onTimeChange = viewModel::updateNewTime,
                            onLocationChange = viewModel::updateNewLocation,
                            onNoteChange = viewModel::updateNote,
                            onTargetWeekToggle = viewModel::toggleTargetWeek,
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
private fun WeekSelectionStep(
    uiState: RescheduleUiState,
    onToggleWeek: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOdd: () -> Unit,
    onSelectEven: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Quick Actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = false, onClick = onSelectAll, label = { Text("全选") })
            FilterChip(selected = false, onClick = onSelectOdd, label = { Text("单周") })
            FilterChip(selected = false, onClick = onSelectEven, label = { Text("双周") })
        }

        // Week Grid
        WeekGridSelector(
            availableWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(),
            enabledWeeks = uiState.availableWeeks, // Only original weeks are enabled for selection
            selectedWeeks = uiState.selectedWeeks,
            conflictWeeks = uiState.conflictWeeks,
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
    onTimeChange: (Int, Int) -> Unit,
    onLocationChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onTargetWeekToggle: (Int) -> Unit,
    onNext: () -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.verticalScroll(scrollState)
    ) {
        // Target Week Selector
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新周次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    val countDiff = uiState.targetWeeks.size - uiState.selectedWeeks.size
                    val countText = if (countDiff == 0) {
                        "已选 ${uiState.targetWeeks.size} 周"
                    } else {
                        "需选 ${uiState.selectedWeeks.size} 周 (当前 ${uiState.targetWeeks.size})"
                    }
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (countDiff == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                WeekGridSelector(
                    availableWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(),
                    enabledWeeks = (1..LocalAppSettings.current.totalWeeks.coerceAtLeast(20)).toSet(), // All weeks selectable
                    selectedWeeks = uiState.targetWeeks,
                    conflictWeeks = uiState.conflictWeeks,
                    onToggleWeek = onTargetWeekToggle,
                    modifier = Modifier.heightIn(max = 200.dp)
                )
            }
        }

        // Time Selector
        Card(
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
             modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("新时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                if (uiState.conflictWeeks.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "第 ${uiState.conflictWeeks.sorted().joinToString(", ")} 周存在冲突",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                TimeGridSelector(
                    selectedDay = uiState.newDay,
                    startNode = uiState.newStartNode,
                    duration = uiState.originalCourse?.duration ?: 2,
                    onSelectionChange = { day, start, _ -> onTimeChange(day, start) }
                )
            }
        }

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
            enabled = uiState.targetWeeks.size == uiState.selectedWeeks.size,
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

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("确认调整")
        }
    }
}

@Composable
private fun WeekGridSelector(
    availableWeeks: Set<Int>,
    enabledWeeks: Set<Int>,
    selectedWeeks: Set<Int>,
    conflictWeeks: Set<Int>,
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
            val isConflict = conflictWeeks.contains(week)
            
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
                
                // Conflict Indicator (Red Dot)
                if (isConflict) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }

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
