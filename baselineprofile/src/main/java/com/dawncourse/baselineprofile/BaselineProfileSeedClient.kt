package com.dawncourse.baselineprofile

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/** 仅供 Baseline Profile 生成器调用的 benchmark-only 数据种子 Provider 客户端。 */
internal object BaselineProfileSeedClient {
    private val uri = Uri.parse("content://com.dawncourse.app.benchmark")

    fun seedCourses(): Int = requireNotNull(
        InstrumentationRegistry.getInstrumentation()
            .context
            .contentResolver
            .call(uri, "seed_courses", null, null)
    ) { "Baseline Profile seed provider did not return a result" }.getInt("course_count")

    fun buildWidgetTimeline(): Int = requireNotNull(
        InstrumentationRegistry.getInstrumentation()
            .context
            .contentResolver
            .call(uri, "widget_data_build", null, null)
    ) { "Baseline Profile widget provider did not return a result" }.getInt("course_count")
}
