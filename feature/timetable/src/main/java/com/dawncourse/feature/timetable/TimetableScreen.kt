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
            
            // 2. 遮罩层 (固定半透明遮罩，不再受settings.transparency完全控制，而是叠加效果)
            // 用户建议：Color.White.copy(alpha = 0.6f) or Color.Black.copy(alpha = 0.4f)
            // settings.transparency 依然可以作为微调，但基础遮罩必须存在以保证可读性
            val baseOverlayAlpha = if (isDarkTheme) 0.4f else 0.6f
            val userTransparency = settings.transparency // 0.0(不透) - 1.0(全透) -> 实际上是控制背景图的可见度
            // 这里我们保持原逻辑：settings.transparency 越高，背景图越清晰（遮罩越淡）？
            // 或者 settings.transparency 越高，遮罩越透明？
            // 通常 transparency = 1.0f 意味着背景完全可见（遮罩 alpha = 0）。
            // 用户现在给出了明确建议值，我们优先使用建议值作为基准。
            // 我们可以让 settings.transparency 微调这个 alpha 值。
            // 简化起见，直接使用用户建议的固定值，或者允许微调。
            // 让我们使用固定建议值 + settings.transparency 作为混合因子。
            // 假设 settings.transparency (0-1) 控制遮罩的不透明度：1.0=完全透明(不遮)，0.0=完全不透明(全遮)。
            // 用户建议 0.6f (白) / 0.4f (黑)。
            
            // 实际上，为了效果好，我们直接用用户建议的值作为默认效果。
            // 如果用户之前设置了 transparency，我们暂时忽略或重新映射。
            // 但为了兼容，我们假设 transparency 控制的是“背景图的清晰度”。
            // 这里直接采用用户建议的固定值效果最好。
            val overlayColor = if (isDarkTheme) Color.Black else Color.White
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor.copy(alpha = baseOverlayAlpha))
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
