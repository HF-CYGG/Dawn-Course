package com.dawncourse.benchmark

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

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
        ensureTargetProcessRunning()
        return requireNotNull(
            InstrumentationRegistry.getInstrumentation()
                .context
                .contentResolver
                .call(uri, method, null, null)
        ) { "Benchmark seed provider did not return a result for $method" }
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
