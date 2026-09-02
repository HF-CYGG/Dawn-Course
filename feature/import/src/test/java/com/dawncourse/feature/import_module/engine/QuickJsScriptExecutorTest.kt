package com.dawncourse.feature.import_module.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsScriptExecutorTest {

    @Test
    fun `执行器按宿主 依赖 解析器 顺序调用运行时`() {
        val runtime = RecordingRuntime(
            evaluations = mapOf(
                "__dawnHost.begin" to QuickJsEvaluationValue.BooleanValue(false),
                "__dawnHost.isSettled" to QuickJsEvaluationValue.BooleanValue(true),
                "__dawnHost.resultJson" to QuickJsEvaluationValue.TextValue(successResultJson())
            )
        )

        QuickJsScriptExecutor(QuickJsRuntimeFactory { runtime }).execute(
            request(script = "parser-source", dependencies = listOf("dependency-source"))
        )

        assertEquals(
            listOf(
                SETUP_RUNTIME_PREFIX,
                "harness-source",
                "dependency-source",
                "parser-source",
                "__dawnHost.begin",
                "__dawnHost.isSettled",
                "__dawnHost.resultJson",
                DIAGNOSTICS_QUERY
            ),
            runtime.evaluated.map(::contractCall)
        )
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `Promise 宿主结果由运行时 eager drain 后直接读取`() {
        val runtime = RecordingRuntime(
            evaluations = mapOf(
                "__dawnHost.begin" to QuickJsEvaluationValue.BooleanValue(true),
                "__dawnHost.isSettled" to QuickJsEvaluationValue.BooleanValue(true),
                "__dawnHost.resultJson" to QuickJsEvaluationValue.TextValue(successResultJson())
            )
        )

        QuickJsScriptExecutor(QuickJsRuntimeFactory { runtime }).execute(request())

        assertEquals(1, runtime.evaluated.count { contractCall(it) == "__dawnHost.isSettled" })
        assertFalse(runtime.evaluated.any { it.contains("executePendingJobs") })
        assertFalse(runtime.evaluated.any { it.startsWith("__dawnHost.abortAsTimeout") })
    }

    @Test
    fun `运行时异常映射为稳定错误且不泄露 vendor 消息`() {
        val runtime = RecordingRuntime(throwOnSource = "parser-source")

        val result = QuickJsScriptExecutor(QuickJsRuntimeFactory { runtime }).execute(request())

        assertEquals(ScriptEngine.ERROR_SCRIPT_EXCEPTION, result.errorCode)
        assertEquals("script execution failed", result.errorMessage)
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `执行器只返回允许的丢弃诊断并保留重复计数`() {
        val runtime = RecordingRuntime(
            evaluations = mapOf(
                "__dawnHost.begin" to QuickJsEvaluationValue.BooleanValue(false),
                "__dawnHost.isSettled" to QuickJsEvaluationValue.BooleanValue(true),
                "__dawnHost.resultJson" to QuickJsEvaluationValue.TextValue(successResultJson()),
                DIAGNOSTICS_QUERY to QuickJsEvaluationValue.TextValue(
                    """["no_weeks","private-html","no_weeks","no_day"]"""
                )
            )
        )

        val result = QuickJsScriptExecutor(QuickJsRuntimeFactory { runtime }).execute(request())

        assertEquals(listOf("no_weeks", "no_weeks", "no_day"), result.diagnostics)
    }

    @Test
    fun `运行时 close 是幂等的`() {
        val runtime = RecordingRuntime()

        runtime.close()
        runtime.close()

        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `线程受限适配器拒绝跨线程执行`() {
        val delegate = RecordingRuntime()
        val runtime = ThreadConfinedQuickJsRuntimeAdapter(delegate)
        var thrown: Throwable? = null

        val thread = Thread {
            thrown = runCatching { runtime.evaluate("1 + 1") }.exceptionOrNull()
        }
        thread.start()
        thread.join()

        assertTrue(thrown is IllegalStateException)
        assertTrue(delegate.evaluated.isEmpty())
    }

    @Test
    fun `线程受限适配器在所有者线程仅关闭一次`() {
        val delegate = RecordingRuntime()
        val runtime = ThreadConfinedQuickJsRuntimeAdapter(delegate)

        runtime.evaluate("1 + 1")
        runtime.close()
        runtime.close()

        assertEquals(listOf("1 + 1"), delegate.evaluated)
        assertEquals(1, delegate.closeCalls)
    }

    private fun request(
        script: String = "parser-source",
        dependencies: List<String> = emptyList()
    ) = ScriptRuntimeRequest(
        script = script,
        html = "<html></html>",
        harnessSource = "harness-source",
        dependencies = dependencies,
        targetType = "parser",
        timeoutMillis = 1_000L
    )

    private fun successResultJson(): String = """
        {"raw":"[]","ok":true,"schemaValid":true,"resultCount":0,"errorCode":"","errorMessage":"","entryUsed":"parse","contractVersion":1}
    """.trimIndent()

    private fun contractCall(source: String): String = when {
        source.startsWith("(function()") -> SETUP_RUNTIME_PREFIX
        source.startsWith("__dawnHost.begin") -> "__dawnHost.begin"
        source == "__dawnHost.isSettled()" -> "__dawnHost.isSettled"
        source == "__dawnHost.resultJson()" -> "__dawnHost.resultJson"
        source == DIAGNOSTICS_QUERY -> DIAGNOSTICS_QUERY
        else -> source
    }

    private class RecordingRuntime(
        private val evaluations: Map<String, QuickJsEvaluationValue> = emptyMap(),
        private val throwOnSource: String? = null
    ) : QuickJsRuntimeAdapter {
        val evaluated = mutableListOf<String>()
        var closeCalls = 0
            private set

        override fun evaluate(source: String): QuickJsEvaluationValue {
            evaluated += source
            if (source == throwOnSource) {
                throw IllegalStateException("vendor stack and script source must never escape")
            }
            return evaluations.entries.firstOrNull { source.startsWith(it.key) }?.value
                ?: QuickJsEvaluationValue.Empty
        }

        override fun close() {
            if (closeCalls == 0) {
                closeCalls++
            }
        }
    }

    private companion object {
        const val SETUP_RUNTIME_PREFIX = "setup-runtime"
        const val DIAGNOSTICS_QUERY =
            "JSON.stringify((globalThis.__dc_diag || []).slice(0, 512))"
    }
}
