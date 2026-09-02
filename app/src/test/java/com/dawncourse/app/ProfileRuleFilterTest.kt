package com.dawncourse.app

import com.dawncourse.baselineprofile.ProfileRuleFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 以真实规则输入验证 Baseline Profile 生成器的纯 Kotlin 过滤策略。 */
class ProfileRuleFilterTest {

    @Test
    fun `Dawn 生产规则应保留`() {
        assertTrue(ProfileRuleFilter.shouldKeep(DAWN_RULE))
    }

    @Test
    fun `benchmark 规则应拒绝`() {
        assertFalse(ProfileRuleFilter.shouldKeep(BENCHMARK_RULE))
    }

    @Test
    fun `AndroidX Compose Room Hilt 与协程规则应保留`() {
        frameworkRules.forEach { rule -> assertTrue("应保留 $rule", ProfileRuleFilter.shouldKeep(rule)) }
    }

    @Test
    fun `空集合应拒绝作为 release Profile`() {
        assertFalse(ProfileRuleFilter.isValidForRelease(emptyList()))
    }

    @Test
    fun `仅 benchmark 集合应拒绝作为 release Profile`() {
        assertFalse(ProfileRuleFilter.isValidForRelease(listOf(BENCHMARK_RULE)))
    }

    @Test
    fun `Dawn 与框架规则集合应通过 release Profile 校验`() {
        assertTrue(ProfileRuleFilter.isValidForRelease(listOf(DAWN_RULE, frameworkRules.first())))
    }

    @Test
    fun `包含任意 benchmark 规则的集合应拒绝作为 release Profile`() {
        assertFalse(ProfileRuleFilter.isValidForRelease(listOf(DAWN_RULE, frameworkRules.first(), BENCHMARK_RULE)))
    }

    private companion object {
        const val DAWN_RULE = "HLcom/dawncourse/app/MainActivity;->onCreate()V"
        const val BENCHMARK_RULE = "HLcom/dawncourse/app/benchmark/BenchmarkSeedProvider;->call()V"
        val frameworkRules = listOf(
            "HLandroidx/compose/runtime/Recomposer;->runRecomposeAndApplyChanges()V",
            "HLandroidx/room/RoomDatabase;->beginTransaction()V",
            "HLdagger/hilt/android/internal/managers/ActivityComponentManager;->generatedComponent()Ljava/lang/Object;",
            "HLkotlinx/coroutines/CoroutineScopeKt;->coroutineScope()Ljava/lang/Object;"
        )
    }
}
