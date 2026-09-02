package com.dawncourse.feature.timetable

/**
 * 课表首帧完成契约。
 *
 * `Success` 仅在当前学期与课程 Repository Flow 已组合完成后产生；实际供 Macrobenchmark
 * 等待的标记附着在 Success 分支中的 [TimetableGrid]，因此不会把加载期顶部工具栏当作内容就绪。
 */
object TimetableBenchmarkContract {
    const val READY_TEST_TAG = "timetable_content_ready"
    const val WEEK_SWITCH_TEST_TAG = "timetable_week_switch"

    fun isContentReady(uiState: TimetableUiState): Boolean =
        uiState is TimetableUiState.Success
}
