package com.dawncourse.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证 benchmark 专用数据钩子可以构建稳定的大样本课程集。 */
@RunWith(AndroidJUnit4::class)
class BenchmarkSeedProviderContractTest {
    @Test
    fun seedCourses_exposesAtLeastOneHundredCoursesForMacrobenchmark() {
        assertTrue(BenchmarkSeedClient.seedCourses() >= 120)
        assertTrue(BenchmarkSeedClient.buildWidgetTimeline() >= 120)
    }
}
