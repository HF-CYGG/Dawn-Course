package com.dawncourse.feature.timetable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.ui.theme.LocalAppSettings
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 课表功能入口路由 (Composable Route)
 *
 * 负责连接 ViewModel 和 UI，处理导航事件。
 *
 * @param viewModel [TimetableViewModel] 实例
 * @param onSettingsClick 跳转设置点击回调
 * @param onAddClick 添加课程点击回调
 * @param onImportClick 导入课程点击回调
 * @param onCourseClick 课程点击回调 (用于编辑)
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
 * 课表功能的主界面，包含以下核心部分：
 * 1. [TimetableBackground]: 沉浸式背景
 * 2. [TimetableTopBar]: 顶部操作栏 (周次切换、功能入口)
 * 3. [HorizontalPager]: 周次切换容器
 * 4. [TimetableGrid]: 课程网格展示
 * 5. [CourseDetailSheet]: 课程详情弹窗
 *
 * @param uiState UI 状态
 * @param userMessage 用户提示消息
 * @param onUserMessageShown 消息已显示回调
 * @param onAddClick 添加课程回调
 * @param onImportClick 导入课程回调
 * @param onSettingsClick 设置回调
 * @param onCourseClick 课程点击回调
 * @param onUndoReschedule 撤销调课回调
 * @param onConfirmDelete 确认删除回调
 * @param onUndoDelete 撤销删除回调
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
    
    // 计算当前真实周次
    val realCurrentWeek = (uiState as? TimetableUiState.Success)?.currentWeek ?: 1
    // 绑定总周数到设置
    val maxWeeks = settings.totalWeeks
    // 假期模式判断：当前周 > 总周数
    val isHoliday = realCurrentWeek > maxWeeks

    // Pager 状态管理
    val pagerState = rememberPagerState(
        initialPage = (realCurrentWeek - 1).coerceIn(0, maxWeeks - 1),
        pageCount = { maxWeeks }
    )
    
    // 根据 Pager 计算当前展示的周次
    val displayedWeek by remember { derivedStateOf { pagerState.currentPage + 1 } }
    
    val scope = rememberCoroutineScope()

    // 首次加载时自动滚动到当前周
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

        // 2. 内容层 (Scaffold)
        // 关键：Scaffold 背景设为透明，否则会挡住下面的壁纸
        Scaffold(
            containerColor = Color.Transparent, // 透明背景
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // 顶部栏 (透明背景)
                TimetableTopBar(
                    currentWeek = displayedWeek,
                    isRealCurrentWeek = displayedWeek == realCurrentWeek,
                    totalWeeks = maxWeeks,
                    onWeekSelected = { week ->
                        scope.launch {
                            val targetPage = (week - 1).coerceIn(0, maxWeeks - 1)
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
            if (isHoliday) {
                HolidayView(modifier = Modifier.padding(padding))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // 3. 可滚动的课表区域 (Pager)
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        beyondBoundsPageCount = 1 // 预加载前后各1页，大幅提升滑动流畅度
                    ) { page ->
                        val week = page + 1
                        
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 3.1 星期栏头部 (跟随页面滑动)
                            WeekHeader(
                                isCurrentWeek = week == realCurrentWeek,
                                displayedWeek = week,
                                semesterStartDate = (uiState as? TimetableUiState.Success)?.semesterStartDate
                            )

                            // 3.2 垂直滚动区域 (时间轴 + 课程网格)
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
        DeleteConfirmationDialog(
            coursesToDelete = coursesToDelete!!,
            targetCourse = targetCourseForDelete!!,
            onConfirmDelete = { courses ->
                onConfirmDelete(courses)
                coursesToDelete = null
                targetCourseForDelete = null
            },
            onDismiss = {
                coursesToDelete = null
                targetCourseForDelete = null
            }
        )
    }
    
    // 调课弹窗
    if (rescheduleCourseId != null) {
        CourseRescheduleSheet(
            courseId = rescheduleCourseId!!,
            initialWeek = displayedWeek,
            onDismissRequest = { rescheduleCourseId = null }
        )
    }
}
