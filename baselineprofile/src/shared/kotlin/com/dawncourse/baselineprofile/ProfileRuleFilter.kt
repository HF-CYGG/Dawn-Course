package com.dawncourse.baselineprofile

/** Baseline Profile 生成器的可执行规则过滤与 release 合法性策略。 */
object ProfileRuleFilter {
    private const val DAWN_COURSE_RULE_PREFIX = "Lcom/dawncourse/"
    private const val BENCHMARK_RULE_PREFIX = "Lcom/dawncourse/app/benchmark/"

    /** 仅排除 benchmark-only 规则，保留应用及其实际热路径依赖规则。 */
    fun shouldKeep(rule: String): Boolean = !rule.contains(BENCHMARK_RULE_PREFIX)

    /** release Profile 必须有 Dawn Course 生产规则且不能含 benchmark-only 规则。 */
    fun isValidForRelease(rules: Iterable<String>): Boolean =
        rules.any(::isDawnCourseProductionRule) && rules.none { rule -> !shouldKeep(rule) }

    /** 判断规则是否属于 Dawn Course 的非 benchmark 生产代码。 */
    private fun isDawnCourseProductionRule(rule: String): Boolean =
        rule.contains(DAWN_COURSE_RULE_PREFIX) && shouldKeep(rule)
}
