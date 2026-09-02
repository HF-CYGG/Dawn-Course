package com.dawncourse.feature.import_module.engine

import org.json.JSONArray

/**
 * 仅在 :script_runtime 进程内创建和运行 QuickJS。
 *
 * `script_host.js` 仍是入口探测、依赖加载和结果规范化的唯一契约。运行时 Adapter 在每次
 * `evaluate` 返回前已完成 native Promise job drain，因此这里不得再以反射调用旧 wrapper
 * 的 pending-job API，也不得轮询等待 Promise。
 */
internal class QuickJsScriptExecutor(
    private val runtimeFactory: QuickJsRuntimeFactory = HarlonQuickJsRuntimeFactory
) {
    fun execute(request: ScriptRuntimeRequest): ScriptEngine.ScriptExecutionResult {
        val validation = ScriptRuntimeLimits.validateInput(
            harnessBytes = ScriptRuntimeLimits.utf8Size(request.harnessSource),
            scriptAndDependencyBytes = ScriptRuntimeLimits.utf8Size(request.script) +
                request.dependencies.sumOf(ScriptRuntimeLimits::utf8Size),
            htmlBytes = ScriptRuntimeLimits.utf8Size(request.html),
            timeoutMillis = request.timeoutMillis
        )
        if (!validation.isValid) {
            return failure(validation.errorCode, "script runtime input exceeds limit")
        }
        if (request.harnessSource.isBlank()) {
            return failure(ScriptEngine.ERROR_HARNESS_MISSING, "script harness is missing")
        }

        var runtime: QuickJsRuntimeAdapter? = null
        return try {
            runtime = runtimeFactory.create()
            setupRuntime(runtime)
            runtime.evaluate(request.harnessSource)
            request.dependencies.forEach { dependency ->
                if (dependency.isNotBlank()) runtime.evaluate(dependency)
            }
            runtime.evaluate(request.script)
            invokeHarness(runtime, request.html, request.targetType, request.timeoutMillis)
        } catch (error: Throwable) {
            failure(
                errorCode = (error as? ScriptEngine.ScriptExecutionException)?.errorCode
                    ?: ScriptEngine.ERROR_SCRIPT_EXCEPTION,
                message = "script execution failed"
            )
        } finally {
            // close 失败不能覆盖已经得到的宿主结果，也不得把 vendor 异常回传到主进程。
            runCatching { runtime?.close() }
        }
    }

    /** 注入最小浏览器兼容对象，保持既有 parser script 的离线运行语义。 */
    private fun setupRuntime(runtime: QuickJsRuntimeAdapter) {
        runtime.evaluate(
            """
            (function() {
              if (!globalThis.console) {
                globalThis.console = { log: function(){}, error: function(){}, warn: function(){} };
              }
              if (!globalThis.setTimeout) {
                globalThis.setTimeout = function(fn, ms) { if (typeof fn === 'function') { fn(); } return 0; };
              }
              if (!globalThis.clearTimeout) {
                globalThis.clearTimeout = function() {};
              }
              if (!globalThis.window) {
                globalThis.window = globalThis;
              }
              if (!globalThis.document) {
                globalThis.document = {};
              }
            })();
            """.trimIndent()
        )
    }

    /**
     * 调用共享宿主并读取一次已 eager-drain 的 settled 状态。
     *
     * `deadlineAt` 仍传给宿主做业务级截止判断；同步死循环的硬中断仍由主进程按请求预算
     * 通过 withTimeout 后终止 :script_runtime 进程完成。
     */
    private fun invokeHarness(
        runtime: QuickJsRuntimeAdapter,
        html: String,
        targetType: String,
        timeoutMillis: Long
    ): ScriptEngine.ScriptExecutionResult {
        val deadlineAt = System.currentTimeMillis() + timeoutMillis
        val options = """{"targetType":${jsonStringLiteral(targetType)},"deadlineAt":$deadlineAt}"""
        runtime.evaluate("__dawnHost.begin(globalThis, ${jsonStringLiteral(html)}, $options)")

        val settled = runtime.evaluate("__dawnHost.isSettled()").booleanOrFalse()
        if (!settled) {
            runtime.evaluate("__dawnHost.abortAsTimeout('')")
        }
        val json = runtime.evaluate("__dawnHost.resultJson()").textOrEmpty()
        return parseHarnessResult(json, readDiagnostics(runtime))
    }

    /** 校验宿主 JSON 的体积，避免 Binder/文件协议把超大结果带回主进程。 */
    private fun parseHarnessResult(
        json: String,
        diagnostics: List<String>
    ): ScriptEngine.ScriptExecutionResult {
        if (json.isBlank()) {
            return failure(ScriptEngine.ERROR_SCRIPT_EXCEPTION, "empty host result")
        }
        val result = scriptExecutionResultFromJson(json).copy(diagnostics = diagnostics)
        val encodedSize = ScriptRuntimeLimits.utf8Size(result.toProtocolJson())
        return if (ScriptRuntimeLimits.isResultSizeValid(encodedSize)) {
            result
        } else {
            failure(ScriptEngine.ERROR_RESULT_TOO_LARGE, "script result exceeds limit")
        }
    }

    /** 诊断只允许固定短码并限制条数，禁止把页面内容经 IPC 带回主进程。 */
    private fun readDiagnostics(runtime: QuickJsRuntimeAdapter): List<String> = runCatching {
        val raw = runtime.evaluate(DIAGNOSTICS_QUERY).textOrEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_DIAGNOSTIC_COUNT)) {
                array.optString(index)
                    .takeIf(ALLOWED_DIAGNOSTIC_CODES::contains)
                    ?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    /** 创建不包含 wrapper 异常细节或脚本内容的稳定错误协议。 */
    private fun failure(errorCode: String, message: String) = ScriptEngine.ScriptExecutionResult(
        raw = "",
        ok = false,
        schemaValid = false,
        resultCount = 0,
        errorCode = errorCode,
        errorMessage = message,
        entryUsed = "",
        contractVersion = ScriptEngine.SUPPORTED_CONTRACT_VERSION
    )

    private companion object {
        const val MAX_DIAGNOSTIC_COUNT = 512
        const val DIAGNOSTICS_QUERY =
            "JSON.stringify((globalThis.__dc_diag || []).slice(0, 512))"
        val ALLOWED_DIAGNOSTIC_CODES = setOf("no_weeks", "no_sections", "no_day")
    }
}

/** 把 Adapter 的受限值还原为宿主所需的布尔状态。 */
private fun QuickJsEvaluationValue.booleanOrFalse(): Boolean =
    (this as? QuickJsEvaluationValue.BooleanValue)?.value ?: false

/** 把 Adapter 的受限值还原为宿主返回的 JSON 文本。 */
private fun QuickJsEvaluationValue.textOrEmpty(): String =
    (this as? QuickJsEvaluationValue.TextValue)?.value.orEmpty()

/**
 * 在不依赖 Android `JSONObject` 的前提下生成 JavaScript 可直接消费的 JSON 字符串字面量。
 *
 * 该函数仅用于把跨进程请求中的纯文本传给 `script_host.js`，不承担对象序列化职责。
 */
private fun jsonStringLiteral(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20 || character == '\u2028' || character == '\u2029') {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
