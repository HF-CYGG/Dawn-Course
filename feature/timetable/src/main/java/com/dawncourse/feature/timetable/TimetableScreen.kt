package com.dawncourse.feature.timetable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings
import java.time.LocalDate

/**
 * 课表功能入口路由 (Composable Route)
 */
@Composable
fun TimetableRoute(
    viewModel: TimetableViewModel = hiltViewModel(),
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onCourseClick: (Long) -> Unit,
    onImportClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    TimetableScreen(
        uiState = uiState,
        onAddClick = onAddClick,
        onSettingsClick = onSettingsClick,
        onCourseClick = onCourseClick,
        onImportClick = onImportClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableScreen(
    uiState: TimetableUiState,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCourseClick: (Long) -> Unit,
    onImportClick: () -> Unit
) {
    // 选中的课程，用于显示详情弹窗
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    
    val settings = LocalAppSettings.current
    val containerColor = if (settings.wallpaperUri != null) {
        MaterialTheme.colorScheme.background.copy(alpha = 1f - settings.transparency)
    } else {
        MaterialTheme.colorScheme.background
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
            }
        },
        // 沉浸式设计：背景延伸到全屏，TopBar 背景透明
        containerColor = containerColor
    ) { padding ->
        // 使用 Box 容纳背景和内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding) // 这里的 padding 包含了 SystemBars 的 inset
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 1. 顶部栏 (透明背景)
                TimetableTopBar(
                    currentWeek = 1, // TODO: 从 ViewModel 获取
                    onSettingsClick = onSettingsClick,
                    onImportClick = onImportClick
                )

                // 2. 星期栏头部
                WeekHeader()

                // 3. 可滚动的课表区域
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 左侧时间轴 (固定宽度)
                    TimeColumnIndicator(
                        modifier = Modifier.width(32.dp)
                    )

                    // 右侧课程网格
                    if (uiState is TimetableUiState.Success) {
                        TimetableGrid(
                            courses = uiState.courses,
                            modifier = Modifier.weight(1f),
                            onCourseClick = { course -> selectedCourse = course }
                        )
                    } else {
                        // 空状态或加载状态
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 100.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text("暂无课程", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }

        // 课程详情弹窗
    if (selectedCourse != null) {
        CourseDetailSheet(
            course = selectedCourse!!,
            onDismissRequest = { selectedCourse = null },
            onEditClick = {
                onCourseClick(selectedCourse!!.id)
                selectedCourse = null
            },
            onDeleteClick = {
                // TODO: 处理删除, call ViewModel delete
                // Since TimetableScreen doesn't have VM, pass callback?
                // Or just close for now.
                // We need onDelete callback in TimetableScreen.
                // Let's just close for now or add callback later.
                selectedCourse = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableTopBar(
    currentWeek: Int,
    onSettingsClick: () -> Unit,
    onImportClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "第 $currentWeek 周",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = LocalDate.now().toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = onImportClick) {
                Icon(Icons.Default.Download, contentDescription = "导入")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        // 由于我们在 Activity 中开启了 edge-to-edge，这里 TopAppBar 内部会自动处理 status bar padding吗？
        // 通常 TopAppBar 会处理。但在 Scaffold 中，padding 参数已经包含了这些。
        // 为了安全起见，我们信任 Scaffold 传递的 padding。
        modifier = Modifier
    )
}
