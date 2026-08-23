package com.dawncourse.feature.import_module.engine

import com.dawncourse.core.domain.model.RemoteScriptDescriptor
import com.dawncourse.core.domain.model.ScriptDependency

/**
 * 单个解析器的执行计划
 *
 * @property scriptName 解析器脚本名
 * @property dependencies 需要在解析器之前装载的依赖脚本
 */
data class ParserPlanEntry(
    val scriptName: String,
    val dependencies: List<ScriptDependency>
)

/**
 * 解析器候选筛选策略
 *
 * 背景：此前解析器列表与依赖都写死在 ImportViewModel 中，服务端按 schoolId
 * 发布的学校专属脚本永远不会被客户端执行，manifest 中的 dependencies、
 * parserApiVersion、runnerContractVersion 字段也全部被忽略。
 *
 * 本类为纯函数实现，不依赖 Android 框架，便于单元测试覆盖筛选与降级规则。
 */
object ParserSelectionPolicy {

    /** 所有解析器共用的工具库，作为依赖装载而非独立入口 */
    const val COMMON_PARSER_UTILS: String = "common_parser_utils.js"

    /** manifest 不可用时的内置兜底解析器列表（保证离线可用） */
    val FALLBACK_PARSERS: List<String> = listOf("qiangzhi.js", "zhengfang.js", "kingosoft.js")

    /**
     * 依据云端候选构建执行计划
     *
     * 筛选规则：
     * 1. 只保留 parsers 分类，且排除工具库本身（它是依赖，不是入口）
     * 2. 契约版本门控：脚本要求的契约版本高于客户端支持范围时跳过，
     *    避免新契约脚本下发到旧客户端后以难以诊断的方式失败
     * 3. 同名脚本只保留优先级最高的一个
     * 4. 结果为空时回落到内置列表
     *
     * @param candidates 云端候选（调用方已完成签名校验与灰度过滤）
     * @param supportedParserApiVersion 客户端支持的解析器 API 版本上限
     * @param supportedContractVersion 客户端支持的执行契约版本上限
     */
    fun buildPlan(
        candidates: List<RemoteScriptDescriptor>,
        supportedParserApiVersion: Int,
        supportedContractVersion: Int
    ): List<ParserPlanEntry> {
        val eligible = candidates
            .asSequence()
            .filter { it.category == "parsers" }
            .filter { it.name.isNotBlank() && it.name != COMMON_PARSER_UTILS }
            .filter { it.parserApiVersion <= supportedParserApiVersion }
            .filter { it.runnerContractVersion <= supportedContractVersion }
            .sortedWith(
                compareByDescending<RemoteScriptDescriptor> { it.priority }
                    .thenByDescending { it.version }
            )
            .distinctBy { it.name }
            .toList()

        if (eligible.isEmpty()) return fallbackPlan()

        return eligible.map { descriptor ->
            ParserPlanEntry(
                scriptName = descriptor.name,
                dependencies = descriptor.dependencies.ifEmpty { defaultDependencies(descriptor.name) }
            )
        }
    }

    /** 内置兜底计划：manifest 不可用或全部候选被门控时使用 */
    fun fallbackPlan(): List<ParserPlanEntry> {
        return FALLBACK_PARSERS.map { name ->
            ParserPlanEntry(scriptName = name, dependencies = defaultDependencies(name))
        }
    }

    /** 未声明依赖时的默认依赖：解析器统一前置工具库 */
    private fun defaultDependencies(scriptName: String): List<ScriptDependency> {
        if (scriptName == COMMON_PARSER_UTILS) return emptyList()
        return listOf(ScriptDependency(category = "parsers", name = COMMON_PARSER_UTILS, version = 1))
    }
}
