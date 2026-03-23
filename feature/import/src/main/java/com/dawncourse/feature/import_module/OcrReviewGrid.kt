package com.dawncourse.feature.import_module

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawncourse.feature.import_module.model.ParsedCourse

/**
 * OCR 专用的可视化校验网格
 *
 * 仿 vivo 课表的交互体验，将解析结果渲染为真实的二维 Grid，
 * 并对低置信度的单元格进行颜色高亮预警，支持点击快速编辑。
 */
@Composable
fun OcrReviewGrid(
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedCourse by remember { mutableStateOf<Pair<Int, ParsedCourse>?>(null) }
    var showTimeSettings by remember { mutableStateOf(false) }

    // 弹窗处理
    selectedCourse?.let { (index, course) ->
        CourseQuickEditDialog(
            course = course,
            onDismiss = { selectedCourse = null },
            onSave = { updatedCourse ->
                viewModel.updateParsedCourse(index, updatedCourse)
                selectedCourse = null
            },
            onDelete = {
                viewModel.deleteParsedCourse(index)
                selectedCourse = null
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部提示条
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "发现部分异常课程 (标黄)，请点击卡片核对并修改",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // 网格主体 (简化的 7 列布局，不含左侧时间轴，侧重于校验)
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 表头
            val days = listOf("一", "二", "三", "四", "五", "六", "日")
            items(7) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = days[index],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 按照最大节次渲染网格
            val maxSection = uiState.detectedMaxSection
            items(maxSection * 7) { index ->
                val row = index / 7 + 1 // 1-based section
                val col = index % 7 + 1 // 1-based dayOfWeek

                // 查找属于当前格子的课程
                val courseWithIndex = uiState.parsedCourses
                    .withIndex()
                    .find { it.value.dayOfWeek == col && it.value.startSection == row }

                if (courseWithIndex != null) {
                    val (courseIndex, course) = courseWithIndex
                    OcrCourseCard(
                        course = course,
                        onClick = { selectedCourse = courseIndex to course }
                    )
                } else {
                    // 空白占位格，支持点击新增（暂未实现）
                    Box(
                        modifier = Modifier
                            .aspectRatio(0.6f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    )
                }
            }
        }

        // 底部操作栏
        Surface(
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showTimeSettings = true }) {
                    Text("设置学期与作息")
                }
                Button(
                    onClick = { viewModel.confirmImport() }
                ) {
                    Text("确认无误，开始导入")
                }
            }
        }
    }
}

@Composable
private fun OcrCourseCard(
    course: ParsedCourse,
    onClick: () -> Unit
) {
    // 根据置信度决定背景色
    val backgroundColor = if (course.confidence < 0.8f) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f) // 标黄/红警告
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) // 正常绿色/主题色
    }

    val textColor = if (course.confidence < 0.8f) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.6f) // 控制长宽比使其像课表格子
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = textColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (course.location.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = course.location,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            // 若置信度低，右上角显示警告图标
            if (course.confidence < 0.8f) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "需校验",
                    tint = textColor,
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.End)
                )
            }
        }
    }
}
