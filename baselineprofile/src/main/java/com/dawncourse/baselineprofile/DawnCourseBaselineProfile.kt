package com.dawncourse.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 的关键用户旅程。
 *
 * 启动路径单独纳入 Startup Profile；周次切换与课程网格滚动仅补充 Baseline Profile，
 * 避免把非启动热路径错误放进 Startup Profile。
 */
@RunWith(AndroidJUnit4::class)
class DawnCourseBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun seedStableDataset() {
        check(BaselineProfileSeedClient.seedCourses() >= MINIMUM_COURSE_COUNT) {
            "Baseline Profile seed did not create the required course dataset"
        }
    }

    @Test
    fun startup_toToday() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        strictStability = true,
        filterPredicate = ProfileRuleFilter::shouldKeep
    ) {
        startActivityAndWait()
        waitForTimetable()
    }

    @Test
    fun timetable_weekSwitchingAndCourseGridFling() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false,
        strictStability = true,
        filterPredicate = ProfileRuleFilter::shouldKeep
    ) {
        startActivityAndWait()
        waitForTimetable()
        WEEK_SEQUENCE.forEach { week -> selectWeek(week) }
        repeat(3) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                SWIPE_STEPS
            )
            device.waitForIdle()
        }
    }

    @Test
    fun roomToTimetableUiState() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false,
        strictStability = true,
        filterPredicate = ProfileRuleFilter::shouldKeep
    ) {
        startActivityAndWait()
        waitForTimetable()
    }

    @Test
    fun widgetDataBuild_throughProductionRepositoryChain() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false,
        strictStability = true,
        filterPredicate = ProfileRuleFilter::shouldKeep
    ) {
        check(BaselineProfileSeedClient.buildWidgetTimeline() >= MINIMUM_COURSE_COUNT) {
            "Widget timeline did not read the seeded course dataset"
        }
    }

    private fun MacrobenchmarkScope.waitForTimetable() {
        check(device.wait(Until.hasObject(By.res(TIMETABLE_READY_TEST_TAG)), UI_TIMEOUT_MS)) {
            "Timetable content was not rendered from TimetableUiState.Success"
        }
    }

    private fun MacrobenchmarkScope.selectWeek(week: Int) {
        val weekSwitch = checkNotNull(
            device.wait(Until.findObject(By.res(WEEK_SWITCH_TEST_TAG)), UI_TIMEOUT_MS)
        ) { "Week switch was not available after ${UI_TIMEOUT_MS}ms" }
        weekSwitch.clickFirstClickableAncestor("Week switch")

        val weekOption = checkNotNull(
            device.wait(Until.findObject(By.text(weekLabel(week))), UI_TIMEOUT_MS)
        ) { "Week $week was not available in the picker after ${UI_TIMEOUT_MS}ms" }
        weekOption.clickFirstClickableAncestor("Week $week option")
        device.waitForIdle()
    }

    /** Compose 图标/文字节点自身可能不可点击，实际点击语义通常位于其父节点。 */
    private fun UiObject2.clickFirstClickableAncestor(label: String) {
        val clickable = generateSequence(this) { node -> node.parent }
            .firstOrNull { node -> node.isClickable }
        checkNotNull(clickable) { "$label has no clickable semantics ancestor" }.click()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.dawncourse.app"
        const val TIMETABLE_READY_TEST_TAG = "timetable_content_ready"
        const val WEEK_SWITCH_TEST_TAG = "timetable_week_switch"
        const val UI_TIMEOUT_MS = 10_000L
        const val SWIPE_STEPS = 12
        const val MINIMUM_COURSE_COUNT = 120
        val WEEK_SEQUENCE = listOf(1, 2, 3, 4, 5)

        fun weekLabel(week: Int): String = "第 $week 周"
    }
}
