package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定生成规则的过滤边界、release 门禁与 Baseline Profile 对照基准。 */
class BaselineProfileContractTest {

    @Test
    fun `过滤器保留框架规则而只排除 benchmark 规则`() {
        val source = baselineProfileGeneratorSource()

        assertTrue(source.contains("return !rule.contains(BENCHMARK_RULE_PREFIX)"))
        assertFalse(source.contains("DAWN_COURSE_RULE_PREFIX"))
    }

    @Test
    fun `release 门禁要求生产 Dawn 规则但允许框架规则`() {
        val buildScript = appBuildScript()

        assertTrue(buildScript.contains("rules.any { it.contains(DAWN_COURSE_RULE_PREFIX)"))
        assertFalse(buildScript.contains("rules.all { it.contains(\"Lcom/dawncourse/\") }"))
        assertTrue(buildScript.contains("rules.none { it.contains(BENCHMARK_RULE_PREFIX) }"))
    }

    @Test
    fun `对照基准要求安装 Baseline Profile 并保留 None 基准`() {
        val source = macrobenchmarkSource()

        assertTrue(source.contains("fun coldStart_toToday_withBaselineProfile()"))
        assertTrue(source.contains("CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)"))
        assertTrue(source.contains("fun coldStart_toToday()"))
        assertTrue(source.contains("compilationMode = CompilationMode.None()"))
    }

    /** 返回 Baseline Profile 生成器源码。 */
    private fun baselineProfileGeneratorSource(): String = File(
        "../baselineprofile/src/main/java/com/dawncourse/baselineprofile/DawnCourseBaselineProfile.kt"
    ).readText()

    /** 返回 app 模块的 Profile 门禁构建脚本。 */
    private fun appBuildScript(): String = File("build.gradle.kts").readText()

    /** 返回 Macrobenchmark 源码。 */
    private fun macrobenchmarkSource(): String = File(
        "../benchmark/src/main/java/com/dawncourse/benchmark/DawnCourseMacrobenchmark.kt"
    ).readText()
}
