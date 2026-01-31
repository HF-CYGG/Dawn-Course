package com.dawncourse.feature.timetable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable

import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 课表功能入口路由 (Composable Route)
 */
@Composable
fun TimetableRoute(
    viewModel: TimetableViewModel = hiltViewModel(),
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onCourseClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    
    TimetableScreen(
        uiState = uiState,
        userMessage = userMessage,
        onUserMessageShown = { viewModel.userMessageShown() },
        onAddClick = onAddClick,
        onImportClick = onImportClick,
        onSettingsClick = onSettingsClick,
        onCourseClick = onCourseClick,
        onUndoReschedule = { viewModel.undoReschedule(it) },
        onConfirmDelete = { viewModel.deleteCoursesWithUndo(it) },
        onUndoDelete = { viewModel.undoDelete() }
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
    userMessage: String? = null,
    onUserMessageShown: () -> Unit = {},
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCourseClick: (Long) -> Unit,
    onUndoReschedule: (Course) -> Unit,
    onConfirmDelete: (List<Course>) -> Unit,
    onUndoDelete: () -> Unit
) {
    // 选中的课程，用于显示详情弹窗
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    // 选中的课程 ID，用于显示调课弹窗
    var rescheduleCourseId by remember { mutableStateOf<Long?>(null) }
    // 待删除的课程列表（用于显示确认弹窗）
    var coursesToDelete by remember { mutableStateOf<List<Course>?>(null) }
    // 待删除的目标课程（用于区分“仅删除本时段”）
    var targetCourseForDelete by remember { mutableStateOf<Course?>(null) }
    
    // 标记是否已自动滚动到当前周 (使用 rememberSaveable 在配置变更/导航返回时保持状态，冷启动时重置)
    var hasScrolledToCurrentWeek by rememberSaveable { mutableStateOf(false) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    val settings = LocalAppSettings.current
    val isDarkTheme = isSystemInDarkTheme()

    // 显示 Snackbar
    LaunchedEffect(userMessage) {
        if (userMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = userMessage,
                actionLabel = "撤销",
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndoDelete()
            }
            onUserMessageShown()
        }
    }
    
    // Determine current week (Real world week)
    val realCurrentWeek = (uiState as? TimetableUiState.Success)?.currentWeek ?: 1
    val maxWeeks = 30 // Fixed max weeks for semester
    
    // Pager State
    val pagerState = rememberPagerState(
        initialPage = (realCurrentWeek - 1).coerceIn(0, maxWeeks - 1),
        pageCount = { maxWeeks }
    )
    
    // Calculate displayed week from pager state
    val displayedWeek by remember { derivedStateOf { pagerState.currentPage + 1 } }
    
    val scope = rememberCoroutineScope()

    // Auto-scroll to current week on first load
    LaunchedEffect(uiState) {
        if (!hasScrolledToCurrentWeek && uiState is TimetableUiState.Success) {
            // 仅当学期数据已加载（semesterStartDate != null）时才执行自动滚动，
            // 避免在数据加载初期（Success 但无 semesterStartDate）错误消耗滚动标记。
            if (uiState.semesterStartDate != null) {
                val targetPage = (uiState.currentWeek - 1).coerceIn(0, maxWeeks - 1)
                if (targetPage != pagerState.currentPage) {
                    pagerState.scrollToPage(targetPage)
                }
                hasScrolledToCurrentWeek = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 背景图 (沉浸式) - 使用独立组件以优化性能
        TimetableBackground(
            wallpaperUri = settings.wallpaperUri,
            wallpaperMode = settings.wallpaperMode,
            backgroundBlur = settings.backgroundBlur,
            backgroundBrightness = settings.backgroundBrightness,
            transparency = settings.transparency,
            isDarkTheme = isDarkTheme
        )

        // 3. 内容层 (Scaffold)
        // 关键：Scaffold 背景设为透明，否则会挡住下面的壁纸
        Scaffold(
            containerColor = Color.Transparent, // 透明背景
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // 1. 顶部栏 (透明背景)
                TimetableTopBar(
                    currentWeek = displayedWeek,
                    isRealCurrentWeek = displayedWeek == realCurrentWeek,
                    onTitleClick = {
                        scope.launch {
                            val targetPage = (realCurrentWeek - 1).coerceIn(0, maxWeeks - 1)
                            pagerState.animateScrollToPage(targetPage)
                        }
                    },
                    onSettingsClick = onSettingsClick,
                    onAddClick = onAddClick,
                    onImportClick = onImportClick
                )
            },
            contentColor = MaterialTheme.colorScheme.onBackground
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 3. 可滚动的课表区域
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 1 // 预加载前后各1页，大幅提升滑动流畅度
                ) { page ->
                    val week = page + 1
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 2. 星期栏头部 (跟随页面滑动)
                        WeekHeader(
                            isCurrentWeek = week == realCurrentWeek,
                            displayedWeek = week,
                            semesterStartDate = (uiState as? TimetableUiState.Success)?.semesterStartDate
                        )

                        // Use derivedStateOf for scroll-dependent logic if needed (e.g., sticky headers)
                        val scrollState = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                        ) {
                            // 左侧时间轴 (固定宽度)
                            TimeColumnIndicator()

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
            onRescheduleClick = {
                rescheduleCourseId = selectedCourse!!.id
                selectedCourse = null
            },
            onUndoRescheduleClick = {
                onUndoReschedule(selectedCourse!!)
                selectedCourse = null
            },
            onDeleteClick = {
                val currentCourse = selectedCourse!!
                targetCourseForDelete = currentCourse
                
                // 查找同名且同地点的所有课程时段 (简单判断是否为同一门课)
                val sameCourses = (uiState as? TimetableUiState.Success)?.courses?.filter {
                    it.name == currentCourse.name && it.teacher == currentCourse.teacher
                } ?: listOf(currentCourse)
                
                coursesToDelete = sameCourses
                selectedCourse = null // 关闭详情弹窗
            }
        )
    }
    
    // 智能删除确认弹窗
    if (coursesToDelete != null && targetCourseForDelete != null) {
        val allCourses = coursesToDelete!!
        val target = targetCourseForDelete!!
        // 判断是否包含多个不同时间段的课程（忽略完全重复的脏数据）
        val distinctTimeSlots = allCourses.distinctBy { Triple(it.dayOfWeek, it.startSection, it.duration) }
        val isMultiple = distinctTimeSlots.size > 1
        
        AlertDialog(
            onDismissRequest = { 
                coursesToDelete = null 
                targetCourseForDelete = null
            },
            title = { Text("删除课程") },
            text = { 
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isMultiple) "该课程包含 ${distinctTimeSlots.size} 个时段，你想如何删除？" else "确定要删除《${target.name}》吗？")
                }
            },
            confirmButton = {
                if (isMultiple) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.End
                    ) {
                        TextButton(
                            onClick = {
                                onConfirmDelete(allCourses)
                                coursesToDelete = null
                                targetCourseForDelete = null
                            }
                        ) { 
                            Text("删除所有时段", color = MaterialTheme.colorScheme.error) 
                        }
                        
                        TextButton(
                            onClick = {
                                // 仅删除本时段（包括该时段下的所有重复记录）
                                val duplicates = allCourses.filter { 
                                    it.dayOfWeek == target.dayOfWeek && 
                                    it.startSection == target.startSection && 
                                    it.duration == target.duration
                                }
                                onConfirmDelete(duplicates)
                                coursesToDelete = null
                                targetCourseForDelete = null
                            }
                        ) { 
                            Text("仅删除本时段") 
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            // 单时段课程（可能包含重复数据），直接全部删除
                            onConfirmDelete(allCourses)
                            coursesToDelete = null
                            targetCourseForDelete = null
                        }
                    ) { 
                        Text("删除", color = MaterialTheme.colorScheme.error) 
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    coursesToDelete = null 
                    targetCourseForDelete = null
                }) { Text("取消") }
            }
        )
    }
    
    // 调课弹窗
    if (rescheduleCourseId != null) {
        CourseRescheduleSheet(
            courseId = rescheduleCourseId!!,
            onDismissRequest = { rescheduleCourseId = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableTopBar(
    currentWeek: Int,
    isRealCurrentWeek: Boolean,
    onTitleClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column(modifier = Modifier.clickable(onClick = onTitleClick)) {
                Text(
                    text = "第 $currentWeek 周",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                if (isRealCurrentWeek) {
                    Text(
                        text = "本周",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onImportClick) {
                Icon(Icons.Default.CloudDownload, contentDescription = "导入课程")
            }
            IconButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
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
