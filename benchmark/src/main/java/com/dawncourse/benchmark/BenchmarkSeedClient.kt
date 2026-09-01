package com.dawncourse.benchmark

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 仅供 Macrobenchmark 使用的 Debug provider 客户端。
 *
 * Provider 只注册在 app 的 benchmark 相关构建类型中，因此不会出现在 release manifest。
 */
internal object BenchmarkSeedClient {
    private const val AUTHORITY = "com.dawncourse.app.benchmark"
    private val uri = Uri.parse("content://$AUTHORITY")

    fun seedCourses(): Int = call("seed_courses").getInt("course_count")

    fun buildWidgetTimeline(): Int = call("widget_data_build").getInt("course_count")

    private fun call(method: String): Bundle {
        return requireNotNull(
            InstrumentationRegistry.getInstrumentation()
                .context
                .contentResolver
                .call(uri, method, null, null)
        ) { "Benchmark seed provider did not return a result for $method" }
    }
}
