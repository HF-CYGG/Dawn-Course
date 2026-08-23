package com.dawncourse.feature.import_module.engine

import app.cash.quickjs.QuickJs
import org.json.JSONObject

/** 仅在 :script_runtime 进程内创建和运行 QuickJS。 */
internal class QuickJsScriptExecutor {
    private companion object {
        const val IDLE_SLEEP_MS: Long = 2L
    }

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

        val deadlineAt = System.currentTimeMillis() + request.timeoutMillis
        val quickJs = QuickJs.create()
        return try {
            applyResourceLimits(quickJs)
            setupRuntime(quickJs)
            quickJs.evaluate(request.harnessSource)
            request.dependencies.forEach { dependency ->
                if (dependency.isNotBlank()) quickJs.evaluate(dependency)
            }
            quickJs.evaluate(request.script)
            invokeHarness(quickJs, request.html, request.targetType, deadlineAt)
        } catch (error: Throwable) {
            failure(
                errorCode = (error as? ScriptEngine.ScriptExecutionException)?.errorCode
                    ?: ScriptEngine.ERROR_SCRIPT_EXCEPTION,
                message = "script execution failed"
            )
        } finally {
            quickJs.close()
        }
    }

    private fun setupRuntime(quickJs: QuickJs) {
        quickJs.evaluate(
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

    private fun invokeHarness(
        quickJs: QuickJs,
        html: String,
        targetType: String,
        deadlineAt: Long
    ): ScriptEngine.ScriptExecutionResult {
        val options = JSONObject()
            .put("targetType", targetType)
            .put("deadlineAt", deadlineAt)
        val pending = quickJs.evaluate(
            "__dawnHost.begin(globalThis, ${JSONObject.quote(html)}, $options)"
        )
        if (pending is Boolean && pending) {
            pumpUntilSettled(quickJs, deadlineAt)
        }
        val settled = quickJs.evaluate("__dawnHost.isSettled()")
        if (settled !is Boolean || !settled) {
            quickJs.evaluate("__dawnHost.abortAsTimeout('')")
        }
        val json = quickJs.evaluate("__dawnHost.resultJson()")?.toString().orEmpty()
        return parseHarnessResult(json)
    }

    private fun pumpUntilSettled(quickJs: QuickJs, deadlineAt: Long) {
        if (!hasPendingJobsSupport(quickJs)) return
        while (System.currentTimeMillis() < deadlineAt) {
            val executedAny = executePendingJobs(quickJs)
            val settled = quickJs.evaluate("__dawnHost.isSettled()")
            if (settled is Boolean && settled) return
            if (!executedAny) {
                try {
                    Thread.sleep(IDLE_SLEEP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun parseHarnessResult(json: String): ScriptEngine.ScriptExecutionResult {
        if (json.isBlank()) {
            return failure(ScriptEngine.ERROR_SCRIPT_EXCEPTION, "empty host result")
        }
        val result = scriptExecutionResultFromJson(json)
        val encodedSize = ScriptRuntimeLimits.utf8Size(result.toProtocolJson())
        return if (ScriptRuntimeLimits.isResultSizeValid(encodedSize)) {
            result
        } else {
            failure(ScriptEngine.ERROR_RESULT_TOO_LARGE, "script result exceeds limit")
        }
    }

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

    private fun applyResourceLimits(quickJs: QuickJs) {
        invokeIfPresent(quickJs, "setMemoryLimit", 64L * 1024 * 1024)
        invokeIfPresent(quickJs, "setMaxStackSize", 512L * 1024)
    }

    private fun invokeIfPresent(quickJs: QuickJs, methodName: String, value: Long) {
        try {
            val method = quickJs.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType ||
                        it.parameterTypes[0] == Int::class.javaPrimitiveType)
            } ?: return
            if (method.parameterTypes[0] == Int::class.javaPrimitiveType) {
                method.invoke(quickJs, value.toInt())
            } else {
                method.invoke(quickJs, value)
            }
        } catch (_: Throwable) {
            // QuickJS 绑定版本不提供资源限制时，由进程级隔离承担最终边界。
        }
    }

    private fun executePendingJobs(quickJs: QuickJs): Boolean {
        val method = quickJs.javaClass.methods.firstOrNull {
            it.name == "executePendingJobs" && it.parameterCount == 0
        } ?: return false
        var executedAny = false
        repeat(100) {
            val result = method.invoke(quickJs)
            when (result) {
                is Boolean -> if (result) executedAny = true else return executedAny
                is Int -> if (result != 0) executedAny = true else return executedAny
                else -> return executedAny
            }
        }
        return executedAny
    }

    private fun hasPendingJobsSupport(quickJs: QuickJs): Boolean {
        return quickJs.javaClass.methods.any {
            it.name == "executePendingJobs" && it.parameterCount == 0
        }
    }
}
