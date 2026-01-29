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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme

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

/**
 * 课表界面 (Screen)
 *
 * 课表功能的主界面，包含顶部栏、周次头部、时间轴和课程网格。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val isDarkTheme = isSystemInDarkTheme()
    
    // Determine current week (Real world week)
    val realCurrentWeek = (uiState as? TimetableUiState.Success)?.currentWeek ?: 1
    val maxWeeks = 30 // Fixed max weeks for semester
    
    // Pager State
    val pagerState = rememberPagerState(
        initialPage = (realCurrentWeek - 1).coerceIn(0, maxWeeks - 1),
        pageCount = { maxWeeks }
    )
    
    // Calculate displayed week from pager state
    val displayedWeek = pagerState.currentPage + 1

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 背景图 (沉浸式)
        if (settings.wallpaperUri != null) {
            AsyncImage(
                model = settings.wallpaperUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp) // 高斯模糊 (20-30dp)
            )
            
            // 2. 遮罩层
            val defaultAlpha = if (isDarkTheme) 0.4f else 0.6f
            val overlayAlpha = if (settings.transparency == 0f) defaultAlpha else settings.transparency
            
            val overlayColor = if (isDarkTheme) Color.Black else Color.White
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor.copy(alpha = overlayAlpha))
            )
        } else {
            // 无壁纸时使用默认背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // 3. 内容层 (Scaffold)
        // 关键：Scaffold 背景设为透明，否则会挡住下面的壁纸
        Scaffold(
            containerColor = Color.Transparent, // 透明背景
            topBar = {
                // 1. 顶部栏 (透明背景)
                TimetableTopBar(
                    currentWeek = displayedWeek,
                    onSettingsClick = onSettingsClick,
                    onImportClick = onImportClick
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onAddClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加课程")
                }
            },
            contentColor = MaterialTheme.colorScheme.onBackground
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 2. 星期栏头部
                WeekHeader()

                // 3. 可滚动的课表区域
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    val week = page + 1
                    
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
                                currentWeek = week,
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
                                Text("加载中...", style = MaterialTheme.typography.bodyLarge)
                            }
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
                // TODO: 处理删除
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
                // 移除具体日期显示，仅保留周次，更加简洁
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
        modifier = Modifier
    )
}
