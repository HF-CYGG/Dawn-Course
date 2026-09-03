package com.dawncourse.baselineprofile

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/** 仅供 Baseline Profile 生成器调用的 benchmark-only 数据种子 Provider 客户端。 */
internal object BaselineProfileSeedClient {
    private val uri = Uri.parse("content://com.dawncourse.app.benchmark")

    fun seedCourses(): Int = callProvider("seed_courses").getInt("course_count")

    fun buildWidgetTimeline(): Int = callProvider("widget_data_build").getInt("course_count")

    private fun callProvider(method: String): Bundle {
        ensureTargetProcessRunning()
        return requireNotNull(
            InstrumentationRegistry.getInstrumentation()
                .context
                .contentResolver
                .call(uri, method, null, null)
        ) { "Baseline Profile seed provider did not return a result for $method" }
    }

    /** HyperOS 会阻止后台测试包直接唤醒目标 Provider，Shell 显式启动不受该限制。 */
    private fun ensureTargetProcessRunning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = instrumentation.uiAutomation
            .executeShellCommand("am start -W -n $TARGET_ACTIVITY")
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        check(output.lineSequence().any { line -> line.trim() == "Status: ok" }) {
            "Unable to start benchmark target before calling its provider: $output"
        }
    }

    private const val TARGET_ACTIVITY = "com.dawncourse.app/.MainActivity"
}
