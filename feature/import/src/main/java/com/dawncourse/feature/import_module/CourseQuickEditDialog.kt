package com.dawncourse.feature.import_module

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawncourse.feature.import_module.model.ParsedCourse

/**
 * OCR 课程快速编辑弹窗
 *
 * 在 OcrReviewGrid 中点击卡片后从底部弹出的面板。
 * 提供对课程名称、教师、地点、周次等核心字段的快速修正功能。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseQuickEditDialog(
    course: ParsedCourse,
    onDismiss: () -> Unit,
    onSave: (ParsedCourse) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(course.name) }
    var teacher by remember { mutableStateOf(course.teacher) }
    var location by remember { mutableStateOf(course.location) }
    var startWeek by remember { mutableStateOf(course.startWeek.toString()) }
    var endWeek by remember { mutableStateOf(course.endWeek.toString()) }
    // weekType: 0=全周, 1=单周, 2=双周
    var weekType by remember { mutableStateOf(course.weekType) }

    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val dayText = days.getOrNull(course.dayOfWeek - 1) ?: "未知"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部标题与位置提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "编辑课程信息",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "$dayText 第${course.startSection}-${course.endSection}节",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider()

            // 核心字段输入
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名称 (必填)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = name.isBlank()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("上课地点") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("授课教师") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            // 周次区间设置
            Text(text = "周次设置", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = startWeek,
                    onValueChange = { startWeek = it.filter { char -> char.isDigit() } },
                    label = { Text("起始周") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Text("至")
                OutlinedTextField(
                    value = endWeek,
                    onValueChange = { endWeek = it.filter { char -> char.isDigit() } },
                    label = { Text("结束周") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            // 单双周快捷 Tag
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = weekType == 0,
                    onClick = { weekType = 0 },
                    label = { Text("全周") }
                )
                FilterChip(
                    selected = weekType == 1,
                    onClick = { weekType = 1 },
                    label = { Text("单周") }
                )
                FilterChip(
                    selected = weekType == 2,
                    onClick = { weekType = 2 },
                    label = { Text("双周") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除该课")
                }

                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            val updated = course.copy(
                                name = name,
                                teacher = teacher,
                                location = location,
                                startWeek = startWeek.toIntOrNull() ?: 1,
                                endWeek = endWeek.toIntOrNull() ?: 16,
                                weekType = weekType
                            )
                            // 保存时手动将置信度置满，消除警告状态
                            updated.confidence = 1.0f
                            onSave(updated)
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text("保存修改")
                }
            }
        }
    }
}
