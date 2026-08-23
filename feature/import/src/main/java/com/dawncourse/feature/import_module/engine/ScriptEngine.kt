package com.dawncourse.feature.import_module.engine

import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JS 脚本引擎管理器
 *
 * 负责 QuickJS 实例的创建、管理和销毁。
 *
 * 关键设计：入口探测、调用编排与结果校验一律交给共享执行契约
 * （server/html/scripts/runtime/script_host.js，本地兜底位于 assets/runtime/）。
 * 本类只负责「宿主职责」：装载契约、注入运行时垫片、施加执行预算与线程隔离。
 *
 * 这样做是为了让服务端沙箱与设备端使用同一份契约实现——此前两端各自内联编排，
 * 存在语义漂移，导致自动修复出的脚本可能在沙箱通过却在设备上失败。
 */
@Singleton
class ScriptEngine @Inject constructor() {

    companion object {
        /** 默认单次解析预算（毫秒） */
        const val DEFAULT_TIMEOUT_MS: Long = 8_000L

        /** 客户端支持的契约版本，需与 script_host.js 的 CONTRACT_VERSION 对齐 */
        const val SUPPORTED_CONTRACT_VERSION: Int = 1

        /**
         * 客户端支持的解析器 API 版本上限
         *
         * manifest 中声明更高版本的脚本会被跳过，避免新契约脚本下发到旧客户端后
         * 以难以诊断的方式失败。
         */
        const val SUPPORTED_PARSER_API_VERSION: Int = 1

        /** 共享执行契约的脚本名与分类 */
        const val SCRIPT_HOST_NAME: String = "script_host.js"
        const val SCRIPT_HOST_CATEGORY: String = "runtime"

        /** 错误码：与 script_host.js 的 ERROR_CODES 保持一致 */
        const val ERROR_NO_ENTRY: String = "no_entry"
        const val ERROR_SCRIPT_EXCEPTION: String = "script_exception"
        const val ERROR_TIMEOUT: String = "timeout"
        const val ERROR_EMPTY_RESULT: String = "empty_result"
        const val ERROR_SCHEMA_INVALID: String = "schema_invalid"
        const val ERROR_DUPLICATE_RATIO_HIGH: String = "duplicate_ratio_high"
        const val ERROR_DO_NOT_CONTINUE: String = "do_not_continue"
        const val ERROR_HARNESS_MISSING: String = "harness_missing"

        /** 被视为「脚本崩溃」的错误码：这类失败会触发上层的云端修复上报 */
        private val CRASH_LIKE_ERRORS = setOf(
            ERROR_SCRIPT_EXCEPTION,
            ERROR_NO_ENTRY,
            ERROR_TIMEOUT,
            ERROR_HARNESS_MISSING
        )

        /** 微任务泵在无任务可执行时的让步间隔，避免空转烧 CPU */
        private const val IDLE_SLEEP_MS: Long = 2L

        /**
         * 宿主超时相对 JS 预算的额外余量
         *
         * 让契约侧的执行预算优先生效（它能给出 timeout 错误码与已结算状态），
         * 宿主侧的强制超时只作为最后兜底。
         */
        private const val HOST_TIMEOUT_GRACE_MS: Long = 1_000L
    }

    /**
     * 脚本执行结果
     *
     * @property raw 归一后的结果字符串，下游 parseParsedCoursesFromRaw / parseXiaoaiProviderResult 的输入
     * @property ok 是否产出了结构合法且非空的结果
     * @property errorCode 结构化错误码，与服务端 runner 共用同一套取值
     * @property entryUsed 实际命中的入口函数名，便于诊断脚本契约问题
     */
    data class ScriptExecutionResult(
        val raw: String,
        val ok: Boolean,
        val schemaValid: Boolean,
        val resultCount: Int,
        val errorCode: String,
        val errorMessage: String,
        val entryUsed: String,
        val contractVersion: Int
    )

    /**
     * 导入脚本执行异常（可恢复）
     *
     * 设计目标：
     * - 作为“解析失败”的结构化错误信号，便于上层做统一的 UI 提示与重试
     * - message 不携带 HTML/脚本内容，避免把敏感信息带到 UI 或日志里
     * - errorCode 与服务端口径一致，上报后可直接聚合分析
     * - 保留 cause，便于在必要时进行问题定位（但默认不上报/不打印堆栈）
     */
    class ScriptExecutionException(
        message: String,
        val errorCode: String = ERROR_SCRIPT_EXCEPTION,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    /**
     * 脚本执行线程池
     *
     * 必须与调用方线程隔离：QuickJS 的 evaluate 是阻塞式 native 调用，
     * 一旦脚本在同步代码里死循环，既无法被协程取消，也无法被线程中断。
     * 隔离的意义在于「调用方能按时返回」，而不是「能杀掉脚本」。
     *
     * 线程设为守护线程：被放弃的线程不会阻止进程退出。
     */
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dawn-script-engine").apply { isDaemon = true }
    }

    /**
     * 执行 JS 脚本并返回解析结果
     *
     * @param script 目标脚本内容
     * @param html 待解析的 HTML 内容
     * @param harnessSource 共享执行契约源码（script_host.js）
     * @param dependencies 需要在目标脚本之前装载的依赖脚本内容
     * @param targetType 目标类型，决定结果校验口径（parser / term_extractor / navigation）
     * @param timeoutMillis 单次执行预算
     */
    suspend fun parseHtml(
        script: String,
        html: String,
        harnessSource: String,
        dependencies: List<String> = emptyList(),
        targetType: String = "parser",
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        if (harnessSource.isBlank()) {
            throw ScriptExecutionException("脚本执行契约缺失", ERROR_HARNESS_MISSING)
        }

        val future = executor.submit(
            Callable {
                runIsolated(script, html, harnessSource, dependencies, targetType, timeoutMillis)
            }
        )
        val result = try {
            future.get(timeoutMillis + HOST_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // 无法真正中断已进入 native 的脚本，只能放弃该线程。
            // cancel(true) 仅用于标记，被放弃的守护线程会在脚本自行结束后回收。
            future.cancel(true)
            timeoutResult()
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ScriptExecutionException) throw cause
            throw ScriptExecutionException("解析器执行失败", ERROR_SCRIPT_EXCEPTION, cause ?: e)
        }

        if (result.errorCode in CRASH_LIKE_ERRORS) {
            // 崩溃类失败仍以异常形式向上暴露，保持既有的「触发云端修复」控制流
            throw ScriptExecutionException("解析器执行失败", result.errorCode)
        }
        result
    }

    /**
     * 在独立线程内完成一次完整执行
     *
     * 装载顺序：运行时垫片 → 共享契约 → 依赖脚本 → 目标脚本。
     */
    private fun runIsolated(
        script: String,
        html: String,
        harnessSource: String,
        dependencies: List<String>,
        targetType: String,
        timeoutMillis: Long
    ): ScriptExecutionResult {
        val deadlineAt = System.currentTimeMillis() + timeoutMillis
        val quickJs = QuickJs.create()
        return try {
            applyResourceLimits(quickJs)
            setupRuntime(quickJs)
            quickJs.evaluate(harnessSource)
            for (dependency in dependencies) {
                if (dependency.isNotBlank()) quickJs.evaluate(dependency)
            }
            quickJs.evaluate(script)
            invokeHarness(quickJs, html, targetType, deadlineAt)
        } catch (e: Throwable) {
            // 解析脚本属于“外部输入 + 多实现差异”的高风险区域：
            // - 可能出现脚本语法错误、页面结构变化、QuickJS 运行时异常等
            // - 这些错误不应导致应用崩溃，更不应在用户设备上打印堆栈（可能泄露页面/账号相关信息）
            if (e is ScriptExecutionException) throw e
            throw ScriptExecutionException("解析器执行失败", ERROR_SCRIPT_EXCEPTION, e)
        } finally {
            quickJs.close()
        }
    }

    /** 注入脚本运行所需的最小运行时垫片（QuickJS 无 DOM 与定时器） */
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

    /** 通过共享契约驱动脚本执行，并把结果读回 Kotlin 侧 */
    private fun invokeHarness(
        quickJs: QuickJs,
        html: String,
        targetType: String,
        deadlineAt: Long
    ): ScriptExecutionResult {
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
            // 预算耗尽仍未结算：让契约侧统一结算为超时，保证错误码口径一致
            quickJs.evaluate("__dawnHost.abortAsTimeout('')")
        }
        val json = quickJs.evaluate("__dawnHost.resultJson()")?.toString().orEmpty()
        return parseResultJson(json)
    }

    /**
     * 泵微任务直到结算或预算耗尽
     *
     * 说明：该循环只能覆盖微任务层。脚本若在同步代码里死循环，这里根本不会被调度到，
     * 只能依赖调用侧的线程隔离与超时兜底。
     */
    private fun pumpUntilSettled(quickJs: QuickJs, deadlineAt: Long) {
        if (!hasPendingJobsSupport(quickJs)) return
        while (System.currentTimeMillis() < deadlineAt) {
            val executedAny = executePendingJobs(quickJs)
            val settled = quickJs.evaluate("__dawnHost.isSettled()")
            if (settled is Boolean && settled) return
            if (!executedAny) {
                // 没有可执行的任务（例如脚本在等一个永远不会触发的回调），
                // 让出 CPU 后继续等待，直到预算耗尽。
                try {
                    Thread.sleep(IDLE_SLEEP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** 解析契约返回的结果 JSON */
    private fun parseResultJson(json: String): ScriptExecutionResult {
        if (json.isBlank()) {
            return ScriptExecutionResult(
                raw = "",
                ok = false,
                schemaValid = false,
                resultCount = 0,
                errorCode = ERROR_SCRIPT_EXCEPTION,
                errorMessage = "empty host result",
                entryUsed = "",
                contractVersion = 0
            )
        }
        val obj = JSONObject(json)
        return ScriptExecutionResult(
            raw = obj.optString("raw"),
            ok = obj.optBoolean("ok", false),
            schemaValid = obj.optBoolean("schemaValid", false),
            resultCount = obj.optInt("resultCount", 0),
            errorCode = obj.optString("errorCode"),
            errorMessage = obj.optString("errorMessage"),
            entryUsed = obj.optString("entryUsed"),
            contractVersion = obj.optInt("contractVersion", 0)
        )
    }

    private fun timeoutResult(): ScriptExecutionResult {
        return ScriptExecutionResult(
            raw = "",
            ok = false,
            schemaValid = false,
            resultCount = 0,
            errorCode = ERROR_TIMEOUT,
            errorMessage = "script execution exceeded host budget",
            entryUsed = "",
            contractVersion = SUPPORTED_CONTRACT_VERSION
        )
    }

    /**
     * 尽力施加内存与栈限制
     *
     * QuickJS binding 各版本的接口不一致，这里沿用与 [hasPendingJobsSupport] 相同的
     * 反射探测方式：存在则设置，不存在则跳过，避免绑定升级导致编译或运行失败。
     */
    private fun applyResourceLimits(quickJs: QuickJs) {
        invokeIfPresent(quickJs, "setMemoryLimit", 64L * 1024 * 1024)
        invokeIfPresent(quickJs, "setMaxStackSize", 512L * 1024)
    }

    private fun invokeIfPresent(quickJs: QuickJs, methodName: String, value: Long) {
        try {
            val method = quickJs.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaPrimitiveType)
            } ?: return
            if (method.parameterTypes[0] == Int::class.javaPrimitiveType) {
                method.invoke(quickJs, value.toInt())
            } else {
                method.invoke(quickJs, value)
            }
        } catch (_: Throwable) {
            // 限制施加失败不影响解析主流程
        }
    }

    /** 执行挂起的微任务，返回本轮是否实际执行了任务 */
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
        return quickJs.javaClass.methods.any { it.name == "executePendingJobs" && it.parameterCount == 0 }
    }
}
