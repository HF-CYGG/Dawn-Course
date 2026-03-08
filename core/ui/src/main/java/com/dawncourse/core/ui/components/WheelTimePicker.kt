package com.dawncourse.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

@Composable
fun WheelTimePicker(
    initialTime: LocalTime,
    onTimeChanged: (LocalTime) -> Unit,
    modifier: Modifier = Modifier
) {
    var hour by remember { mutableIntStateOf(initialTime.hour) }
    var minute by remember { mutableIntStateOf(initialTime.minute) }

    Surface(
        modifier = modifier,
        color = Color.Transparent
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Highlight bar for selection
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium
            ) {}

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hour
                WheelPicker(
                    count = 24,
                    initialIndex = initialTime.hour,
                    visibleCount = 5,
                    itemHeight = 48.dp,
                    modifier = Modifier.weight(1f),
                    onIndexChanged = { 
                        hour = it 
                        onTimeChanged(LocalTime.of(hour, minute))
                    }
                ) { index, isSelected ->
                    Text(
                        text = String.format("%02d", index),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = if (isSelected) 34.sp else 28.sp,
                        color = if (isSelected) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = ":",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp).offset(y = (-4).dp)
                )

                // Minute
                WheelPicker(
                    count = 60,
                    initialIndex = initialTime.minute,
                    visibleCount = 5,
                    itemHeight = 48.dp,
                    modifier = Modifier.weight(1f),
                    onIndexChanged = { 
                        minute = it
                        onTimeChanged(LocalTime.of(hour, minute))
                    }
                ) { index, isSelected ->
                    Text(
                        text = String.format("%02d", index),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = if (isSelected) 34.sp else 28.sp,
                        color = if (isSelected) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    count: Int,
    initialIndex: Int,
    visibleCount: Int,
    itemHeight: Dp,
    modifier: Modifier = Modifier,
    onIndexChanged: (Int) -> Unit,
    content: @Composable (index: Int, isSelected: Boolean) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % count) + initialIndex,
        initialFirstVisibleItemScrollOffset = 0
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> index % count }
            .distinctUntilChanged()
            .collect { 
                onIndexChanged(it)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
    }

    val verticalPadding = itemHeight * (visibleCount / 2)

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        modifier = modifier.height(itemHeight * visibleCount),
        contentPadding = PaddingValues(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(
            count = Int.MAX_VALUE,
            key = { it }
        ) { index ->
            val actualIndex = index % count
            
            // Calculate scale/alpha in graphicsLayer to avoid recomposition
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val layoutInfo = listState.layoutInfo
                        val viewportCenter = layoutInfo.viewportSize.height / 2f
                        
                        val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }
                        
                        if (itemInfo != null) {
                            val itemCenter = itemInfo.offset + itemInfo.size / 2f
                            val distance = abs(viewportCenter - itemCenter)
                            val maxDistance = itemHeightPx * visibleCount / 2f
                            
                            val scale = 1f - (distance / maxDistance * 0.5f)
                            val alpha = 1f - (distance / maxDistance * 0.7f)
                            
                            scaleX = scale.coerceIn(0.5f, 1f)
                            scaleY = scale.coerceIn(0.5f, 1f)
                            this.alpha = alpha.coerceIn(0.1f, 1f)
                            
                            // 3D rotation effect simulation
                            rotationX = (distance / maxDistance) * 20f * (if (itemCenter > viewportCenter) 1 else -1)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Use derivedState for isSelected to minimize recomposition scope
                // We rely on the fact that `firstVisibleItemIndex` snaps to the center item
                val isSelected by remember { 
                    derivedStateOf { listState.firstVisibleItemIndex == index } 
                }
                
                content(actualIndex, isSelected)
            }
        }
    }
}
