package com.dawncourse.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P0 的可重复 Macrobenchmark 旅程；所有数据均由 benchmark-only Provider 写入。 */
@RunWith(AndroidJUnit4::class)
class DawnCourseMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart_toToday() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            pressHome()
        }
    ) {
        startActivityAndWait()
        waitForTimetable()
    }

    @Test
    fun coldStart_toWeekTimetable() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            pressHome()
        }
    ) {
        startActivityAndWait()
        waitForTimetable()
        selectWeek(WEEK_FOR_COLD_START)
    }

    @Test
    fun switchWeek_fiveTimes() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            startActivityAndWait()
            waitForTimetable()
        }
    ) {
        WEEK_SEQUENCE.forEach { week -> selectWeek(week) }
    }

    @Test
    fun flingCourseGrid_withMoreThanOneHundredCourses() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            startActivityAndWait()
            waitForTimetable()
        }
    ) {
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
    fun roomColdQuery_toTimetableUiState_withSeededLargeDataset() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        // StartupTimingMetric 的 TTFD 由 TimetableScreen 的 ReportDrawnWhen 在真实 UiState.Success 后上报。
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            // StartupMode.COLD 会在 setup 完成后统一停止目标进程；这里手动
            // killProcess() 会破坏 Macrobenchmark 的冷启动前置状态检查。
            pressHome()
        }
    ) {
        startActivityAndWait()
        waitForTimetable()
    }

    @Test
    @OptIn(ExperimentalMetricApi::class)
    fun widgetDataBuild_throughProductionRepositoryChain() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(TraceSectionMetric(TRACE_WIDGET_DATA_BUILD)),
        compilationMode = CompilationMode.None(),
        iterations = ITERATIONS,
        setupBlock = {
            BenchmarkSeedClient.seedCourses()
            killProcess()
        }
    ) {
        check(BenchmarkSeedClient.buildWidgetTimeline() >= MINIMUM_COURSE_COUNT) {
            "Widget timeline did not read the seeded course dataset"
        }
    }

    private fun MacrobenchmarkScope.waitForTimetable() {
        check(device.wait(Until.hasObject(By.res(TIMETABLE_READY_TEST_TAG)), UI_TIMEOUT_MS)) {
            "Timetable content was not rendered from TimetableUiState.Success"
        }
    }

    private fun MacrobenchmarkScope.selectWeek(week: Int) {
        device.findObject(By.desc(WEEK_SWITCH_DESCRIPTION)).click()
        check(device.wait(Until.hasObject(By.text(weekLabel(week))), UI_TIMEOUT_MS)) {
            "Week $week was not available in the picker"
        }
        device.findObject(By.text(weekLabel(week))).click()
        device.waitForIdle()
    }

    /**
     * P1 再接入 Profile 安装/切换矩阵；P0 不伪造已启用或已禁用的 Profile 场景。
     */
    @Ignore("P1：待接入真实 Baseline Profile 切换与安装态验证后执行")
    @Test
    fun profileSwitching_isDeferredToP1() = Unit

    private companion object {
        const val TARGET_PACKAGE = "com.dawncourse.app"
        const val WEEK_SWITCH_DESCRIPTION = "切换周次"
        const val TIMETABLE_READY_TEST_TAG = "timetable_content_ready"
        const val TRACE_WIDGET_DATA_BUILD = "DawnCourseBenchmark#widgetDataBuild"
        const val UI_TIMEOUT_MS = 10_000L
        // P95/P99 至少应基于 100 次重复；dry-run instrumentation 参数会自动缩减为快速单次。
        const val ITERATIONS = 100
        const val MINIMUM_COURSE_COUNT = 120
        const val SWIPE_STEPS = 12
        const val WEEK_FOR_COLD_START = 3
        val WEEK_SEQUENCE = listOf(1, 2, 3, 4, 5)

        fun weekLabel(week: Int): String = "第 $week 周"
    }
}
