package com.dawncourse.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings

/**
 * 课程详情底部弹窗
 *
 * 显示课程的详细信息，提供编辑和删除操作入口。
 * 使用 Material 3 ModalBottomSheet 实现。
 *
 * @param course 课程数据
 * @param onDismissRequest 关闭弹窗回调
 * @param onEditClick 编辑按钮点击回调
 * @param onRescheduleClick 调课按钮点击回调
 * @param onUndoRescheduleClick 撤销调课按钮点击回调
 * @param onDeleteClick 删除按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    onDismissRequest: () -> Unit,
    onEditClick: () -> Unit,
    onRescheduleClick: () -> Unit,
    onUndoRescheduleClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // 获取当前主题色，用于图标和按钮着色
    val themePrimary = MaterialTheme.colorScheme.primary
    val settings = LocalAppSettings.current
    // 底部弹窗状态，skipPartiallyExpanded = true 确保直接展开
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding() // 适配导航栏高度
                .padding(horizontal = 24.dp)
                .padding(bottom = 56.dp) // 底部留白增加，防止误触
        ) {
            // 1. 头部区域：显示课程颜色条和课程名称
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // 颜色指示条 (圆角矩形)
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 32.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(themePrimary)
                )
                Spacer(modifier = Modifier.width(16.dp))
                // 课程名称
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // 2. 信息网格：显示时间、地点、教师等详细信息
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 时间信息处理
                val startSectionIndex = course.startSection - 1
                val endSectionIndex = course.startSection + course.duration - 2
                // 如果配置了具体的作息时间，则显示具体的时间段 (e.g. 08:00-09:40)
                val timeRangeText = if (startSectionIndex >= 0 && endSectionIndex < settings.sectionTimes.size && startSectionIndex <= endSectionIndex) {
                     val start = settings.sectionTimes[startSectionIndex].startTime
                     val end = settings.sectionTimes[endSectionIndex].endTime
                     " ($start-$end)"
                } else {
                    ""
                }

                // 时间项
                CourseDetailItem(
                    icon = Icons.Default.Schedule,
                    label = "时间",
                    value = "周${getDayText(course.dayOfWeek)} 第${course.startSection}-${course.startSection + course.duration - 1}节$timeRangeText",
                    iconTint = themePrimary
                )
                
                // 地点信息 (非空才显示)
                if (course.location.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Place,
                        label = "地点",
                        value = course.location,
                        iconTint = themePrimary
                    )
                }
                
                // 教师信息 (非空才显示)
                val teacherText = cleanTeacherText(course.teacher)
                if (teacherText.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Person,
                        label = "教师",
                        value = teacherText,
                        iconTint = themePrimary
                    )
                }
                
                // 周次信息
                CourseDetailItem(
                    icon = Icons.Default.DateRange,
                    label = "周次",
                    value = "${course.startWeek}-${course.endWeek}周 ${getWeekType(course.weekType)}",
                    iconTint = themePrimary
                )
                
                // 备注信息 (非空才显示)
                if (course.note.isNotEmpty()) {
                    CourseDetailItem(
                        icon = Icons.Default.Info,
                        label = "备注",
                        value = course.note,
                        iconTint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 3. 操作区域：调课、删除、编辑
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 调课/撤销调课 按钮 (通栏按钮)
                androidx.compose.material3.Button(
                    onClick = if (course.isModified) onUndoRescheduleClick else onRescheduleClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (course.isModified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (course.isModified) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Icon(
                        imageVector = if (course.isModified) Icons.Default.Restore else Icons.Default.EditCalendar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (course.isModified) "撤销调课 (还原)" else "调课 (部分周次变动)")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 删除按钮 (次要操作，OutlinedButton)
                    OutlinedButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, 
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除")
                    }
                    
                    // 编辑按钮 (主要操作，Filled Button)
                    androidx.compose.material3.Button(
                        onClick = onEditClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = themePrimary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("编辑")
                    }
                }
            }
        }
    }
}

/**
 * 删除确认对话框
 *
 * 处理单课程删除和多时段重复课程的删除逻辑。
 *
 * @param coursesToDelete 待删除的课程列表
 * @param targetCourse 触发删除的目标课程 (用户点击的那个)
 * @param onConfirmDelete 确认删除回调
 * @param onDismiss 取消回调
 */
@Composable
fun DeleteConfirmationDialog(
    coursesToDelete: List<Course>,
    targetCourse: Course,
    onConfirmDelete: (List<Course>) -> Unit,
    onDismiss: () -> Unit
) {
    // 判断是否包含多个不同时间段的课程（忽略完全重复的脏数据）
    val distinctTimeSlots = remember(coursesToDelete) {
        coursesToDelete.distinctBy { Triple(it.dayOfWeek, it.startSection, it.duration) }
    }
    val isMultiple = distinctTimeSlots.size > 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isMultiple) "该课程包含 ${distinctTimeSlots.size} 个时段，你想如何删除？" else "确定要删除《${targetCourse.name}》吗？")
            }
        },
        confirmButton = {
            if (isMultiple) {
                // 多时段课程，提供两种删除选项
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    TextButton(
                        onClick = {
                            onConfirmDelete(coursesToDelete)
                        }
                    ) {
                        Text("删除所有时段", color = MaterialTheme.colorScheme.error)
                    }

                    TextButton(
                        onClick = {
                            // 仅删除本时段（包括该时段下的所有重复记录）
                            val duplicates = coursesToDelete.filter {
                                it.dayOfWeek == targetCourse.dayOfWeek &&
                                        it.startSection == targetCourse.startSection &&
                                        it.duration == targetCourse.duration
                            }
                            onConfirmDelete(duplicates)
                        }
                    ) {
                        Text("仅删除本时段")
                    }
                }
            } else {
                // 单时段课程（可能包含重复数据），直接全部删除
                TextButton(
                    onClick = {
                        onConfirmDelete(coursesToDelete)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 课程详情项组件
 *
 * 显示带图标的标签和值。
 *
 * @param icon 图标资源
 * @param label 标签文本
 * @param value 内容文本
 * @param iconTint 图标颜色
 */
@Composable
private fun CourseDetailItem(
    icon: ImageVector,
    label: String,
    value: String,
    iconTint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Icon Container：圆形半透明背景
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 文本信息列
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 获取星期文本
 * @param day 1-7
 * @return 中文星期
 */
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

private fun cleanTeacherText(raw: String): String {
    if (raw.isBlank()) return ""
    var cleaned = raw.replace(Regex("\\s+"), " ").trim()
    cleaned = cleaned.replace(Regex("^(教师|任课教师)\\s*[:：]?\\s*"), "")
    val stopRegex = Regex("(教学班组成|教学班|考核方式|课程学时组成|课程学时|课程性质|课程属性|课程类别|课程类型|选课备注|备注|人数|班级组成|班级|课序号|课程号|课程代码|开课单位|上课对象|授课对象|授课形式)")
    val match = stopRegex.find(cleaned)
    if (match != null && match.range.first > 0) {
        cleaned = cleaned.substring(0, match.range.first).trim()
    }
    cleaned = cleaned.trimEnd { it == '，' || it == ',' || it == ';' || it == '；' || it == '/' }
    return cleaned
}

/**
 * 获取周次类型文本
 * @param type 0=全周, 1=单周, 2=双周
 * @return 简写文本
 */
private fun getWeekType(type: Int): String {
    return when (type) {
        Course.WEEK_TYPE_ALL -> "(全)"
        Course.WEEK_TYPE_ODD -> "(单)"
        Course.WEEK_TYPE_EVEN -> "(双)"
        else -> ""
    }
}
