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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings

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
                            onNext = { currentStep = RescheduleStep.SET_TIME_LOCATION }
                        )
                    }
                    RescheduleStep.SET_TIME_LOCATION -> {
                        TimeLocationStep(
                            uiState = uiState,
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
        val totalWeeks = LocalAppSettings.current.totalWeeks.coerceAtLeast(20)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 300.dp)
        ) {
            items((1..totalWeeks).toList()) { week ->
                val isAvailable = uiState.availableWeeks.contains(week)
                val isSelected = uiState.selectedWeeks.contains(week)
                
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                isAvailable -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            }
                        )
                        .clickable(enabled = isAvailable) { onToggleWeek(week) }
                ) {
                    Text(
                        text = week.toString(),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            isAvailable -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected || isAvailable) FontWeight.Bold else FontWeight.Normal
                    )
                    
                    // Conflict Indicator (Red Dot)
                    if (uiState.conflictWeeks.contains(week)) {
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

        Button(
            onClick = onNext,
            enabled = uiState.selectedWeeks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("下一步")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun TimeLocationStep(
    uiState: RescheduleUiState,
    onTimeChange: (Int, Int) -> Unit,
    onLocationChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Time Selector (Reusing TimeGridSelector)
        Text("选择新时间", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        
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
                    text = "第 ${uiState.conflictWeeks.sorted().joinToString(", ")} 周存在时间冲突",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        // Note: Reusing TimeGridSelector from CourseEditorScreen.kt
        // Assuming it's available in the same package
        TimeGridSelector(
            selectedDay = uiState.newDay,
            startNode = uiState.newStartNode,
            duration = uiState.originalCourse?.duration ?: 2,
            onSelectionChange = { day, start, _ -> onTimeChange(day, start) }
        )

        // Location Input
        OutlinedTextField(
            value = uiState.newLocation,
            onValueChange = onLocationChange,
            label = { Text("地点") },
            leadingIcon = { Icon(Icons.Default.LocationOn, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Note Input
        OutlinedTextField(
            value = uiState.note,
            onValueChange = onNoteChange,
            label = { Text("备注 (选填)") },
            placeholder = { Text("例如：老师出差补课") },
            leadingIcon = { Icon(Icons.Default.DateRange, null) }, // Use appropriate icon
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("下一步")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
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
                
                Divider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                
                // Weeks
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("调整周次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(
                            text = uiState.selectedWeeks.sorted().joinToString(", ") { "${it}周" },
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
