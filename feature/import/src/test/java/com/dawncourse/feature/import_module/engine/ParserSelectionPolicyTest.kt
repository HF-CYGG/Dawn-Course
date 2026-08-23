package com.dawncourse.feature.import_module.engine

import com.dawncourse.core.domain.model.RemoteScriptDescriptor
import com.dawncourse.core.domain.model.ScriptDependency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器候选筛选策略测试
 *
 * 覆盖「manifest 驱动」引入的关键规则：优先级排序、契约版本门控、
 * 依赖解析与离线降级。这些规则直接决定学校专属脚本能否被执行。
 */
class ParserSelectionPolicyTest {

    private fun descriptor(
        name: String,
        priority: Int = 0,
        version: Int = 1,
        releaseId: String = "rel-$name-$version",
        scriptKey: String = "parser/parsers/$name/system/ZF",
        parserApiVersion: Int = 1,
        runnerContractVersion: Int = 1,
        category: String = "parsers",
        dependencies: List<ScriptDependency> = emptyList()
    ) = RemoteScriptDescriptor(
        scriptId = "parsers.$name",
        targetType = "parser",
        category = category,
        name = name,
        version = version,
        releaseId = releaseId,
        scriptKey = scriptKey,
        bundleUrl = "https://example.com/api/v1/scripts/releases/$releaseId/bundle",
        scopeKind = "system",
        scopeId = "ZF",
        schoolSystemType = "ZF",
        releaseStage = "active",
        channel = "stable",
        url = "https://example.com/scripts/parsers/$name",
        metaUrl = "https://example.com/scripts/parsers/$name.meta.json",
        sha256 = "",
        signature = "",
        alg = "rsa-sha256",
        priority = priority,
        schoolSystemTypes = emptyList(),
        schoolIds = emptyList(),
        rolloutPercent = 100,
        killSwitch = false,
        minAppVersionCode = 0L,
        maxAppVersionCode = null,
        parserApiVersion = parserApiVersion,
        runnerContractVersion = runnerContractVersion,
        schoolBindingId = null,
        selectionPolicy = "auto",
        dependencies = dependencies,
        changelog = ""
    )

    private fun buildPlan(candidates: List<RemoteScriptDescriptor>) =
        ParserSelectionPolicy.buildPlan(
            candidates = candidates,
            supportedParserApiVersion = 1,
            supportedContractVersion = 1
        )

    @Test
    fun `候选按优先级与版本降序排列`() {
        val plan = buildPlan(
            listOf(
                descriptor("zhengfang.js", priority = 50),
                descriptor("school_special.js", priority = 100),
                descriptor("kingosoft.js", priority = 10)
            )
        )

        assertEquals(
            listOf("school_special.js", "zhengfang.js", "kingosoft.js"),
            plan.map { it.scriptName }
        )
    }

    @Test
    fun `同名脚本只保留版本最高的一个`() {
        val plan = buildPlan(
            listOf(
                descriptor("zhengfang.js", priority = 50, version = 3),
                descriptor("zhengfang.js", priority = 50, version = 7)
            )
        )

        assertEquals(1, plan.size)
        assertEquals("zhengfang.js", plan[0].scriptName)
        assertEquals("rel-zhengfang.js-7", plan[0].releaseId)
    }

    @Test
    fun `同名学校轨道与系统轨道都保留`() {
        val plan = buildPlan(
            listOf(
                descriptor(
                    "zhengfang.js",
                    priority = 100,
                    releaseId = "rel-school",
                    scriptKey = "parser/parsers/zhengfang.js/school/school-a"
                ),
                descriptor(
                    "zhengfang.js",
                    priority = 50,
                    releaseId = "rel-system",
                    scriptKey = "parser/parsers/zhengfang.js/system/ZF"
                )
            )
        )

        assertEquals(listOf("rel-school", "rel-system"), plan.map { it.releaseId })
    }

    @Test
    fun `工具库不会作为独立解析器入口被执行`() {
        // common_parser_utils.js 是依赖而非入口，若被当成解析器执行必然产出空结果
        val plan = buildPlan(
            listOf(
                descriptor(ParserSelectionPolicy.COMMON_PARSER_UTILS, priority = 90),
                descriptor("zhengfang.js", priority = 50)
            )
        )

        assertEquals(listOf("zhengfang.js"), plan.map { it.scriptName })
    }

    @Test
    fun `超出客户端支持范围的契约版本被跳过`() {
        val plan = buildPlan(
            listOf(
                descriptor("future_parser.js", priority = 100, runnerContractVersion = 2),
                descriptor("future_api.js", priority = 99, parserApiVersion = 5),
                descriptor("zhengfang.js", priority = 50)
            )
        )

        assertEquals(listOf("zhengfang.js"), plan.map { it.scriptName })
    }

    @Test
    fun `全部候选被门控时回落到内置列表`() {
        val plan = buildPlan(
            listOf(descriptor("future_parser.js", runnerContractVersion = 9))
        )

        assertEquals(ParserSelectionPolicy.FALLBACK_PARSERS, plan.map { it.scriptName })
    }

    @Test
    fun `候选为空时回落到内置列表以保证离线可用`() {
        val plan = buildPlan(emptyList())

        assertEquals(ParserSelectionPolicy.FALLBACK_PARSERS, plan.map { it.scriptName })
        assertTrue(
            "内置计划也必须带上工具库依赖",
            plan.all { entry ->
                entry.dependencies.any { it.name == ParserSelectionPolicy.COMMON_PARSER_UTILS }
            }
        )
    }

    @Test
    fun `未声明依赖时补齐默认工具库依赖`() {
        val plan = buildPlan(listOf(descriptor("zhengfang.js")))

        assertEquals(
            listOf(ParserSelectionPolicy.COMMON_PARSER_UTILS),
            plan[0].dependencies.map { it.name }
        )
    }

    @Test
    fun `manifest 显式声明的依赖优先于默认值`() {
        val declared = listOf(ScriptDependency(category = "parsers", name = "custom_utils.js", version = 2))
        val plan = buildPlan(listOf(descriptor("school_special.js", dependencies = declared)))

        assertEquals(listOf("custom_utils.js"), plan[0].dependencies.map { it.name })
    }

    @Test
    fun `非解析器分类的脚本不会进入执行计划`() {
        val plan = buildPlan(
            listOf(
                descriptor("zf_nav.js", priority = 100, category = "js"),
                descriptor("script_host.js", priority = 99, category = "runtime"),
                descriptor("zhengfang.js", priority = 50)
            )
        )

        assertEquals(listOf("zhengfang.js"), plan.map { it.scriptName })
    }
}
