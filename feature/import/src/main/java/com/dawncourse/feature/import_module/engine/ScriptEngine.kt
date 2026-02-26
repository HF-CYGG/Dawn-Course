package com.dawncourse.feature.import_module.engine

import app.cash.quickjs.QuickJs
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JS 脚本引擎管理器
 *
 * 负责 QuickJS 实例的创建、管理和销毁。
 * 封装了 QuickJS 的底层操作，提供更友好的执行接口。
 */
@Singleton
class ScriptEngine @Inject constructor() {

    /**
     * 导入脚本执行异常（可恢复）
     *
     * 设计目标：
     * - 作为“解析失败”的结构化错误信号，便于上层做统一的 UI 提示与重试
     * - message 不携带 HTML/脚本内容，避免把敏感信息带到 UI 或日志里
     * - 保留 cause，便于在必要时进行问题定位（但默认不上报/不打印堆栈）
     */
    class ScriptExecutionException(
        message: String,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    /**
     * 执行 JS 脚本并返回解析结果
     *
     * @param script JS 脚本内容
     * @param html 待解析的 HTML 内容
     * @return 解析后的 JSON 字符串（后续可反序列化为 Course 列表）
     */
    fun parseHtml(script: String, html: String): String {
        val quickJs = QuickJs.create()
        return try {
            setupRuntime(quickJs)
            quickJs.evaluate(script)
            executeScript(quickJs, html)
        } catch (e: Throwable) {
            // 解析脚本属于“外部输入 + 多实现差异”的高风险区域：
            // - 可能出现脚本语法错误、页面结构变化、QuickJS 运行时异常等
            // - 这些错误不应导致应用崩溃，更不应在用户设备上打印堆栈（可能泄露页面/账号相关信息）
            //
            // 因此这里统一转换为“可恢复”的异常类型，并保留原始 cause 供上层必要时分析。
            if (e is ScriptExecutionException) throw e
            throw ScriptExecutionException("解析器执行失败", e)
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
              if (!globalThis.__dc_normalize) {
                globalThis.__dc_normalize = function(v) {
                  if (typeof v === 'string') return v;
                  if (v === undefined || v === null) return '';
                  try { return JSON.stringify(v); } catch (e) { return String(v); }
                };
              }
            })();
            """.trimIndent()
        )
    }

    private fun executeScript(quickJs: QuickJs, html: String): String {
        val htmlJson = JSONObject.quote(html)
        val isPromise = quickJs.evaluate(
            """
            (function() {
              const __dc_html = $htmlJson;
              const __dc_hasProvider = typeof scheduleHtmlProvider === 'function';
              const __dc_hasParser = typeof scheduleHtmlParser === 'function';
              const __dc_hasTimer = typeof scheduleTimer === 'function' && __dc_hasProvider;
              const __dc_hasParse = typeof parse === 'function';
              function __dc_isPromise(v) {
                return v && typeof v.then === 'function';
              }
              function __dc_normalizeProvider(providerValue, timerValue) {
                const providerStr = globalThis.__dc_normalize(providerValue);
                if (!providerStr || providerStr === "do not continue" || timerValue === undefined) return providerStr;
                try {
                  const obj = JSON.parse(providerStr);
                  if (obj && typeof obj === "object" && obj.timetable === undefined) {
                    obj.timetable = timerValue;
                    return JSON.stringify(obj);
                  }
                } catch (e) {}
                return providerStr;
              }
              function __dc_finalize(providerValue, parserValue, timerValue) {
                if (providerValue !== undefined) {
                  return __dc_normalizeProvider(providerValue, timerValue);
                }
                if (parserValue !== undefined) {
                  return globalThis.__dc_normalize(parserValue);
                }
                return globalThis.__dc_normalize(providerValue);
              }
              function __dc_runAfterProvider(providerValue) {
                let parserValue = __dc_hasParser ? scheduleHtmlParser(providerValue) : undefined;
                if (__dc_isPromise(parserValue)) {
                  return Promise.resolve(parserValue).then(function(parserResolved) {
                    const timerValue = __dc_hasTimer
                      ? scheduleTimer({ providerRes: globalThis.__dc_normalize(providerValue), parserRes: parserResolved })
                      : undefined;
                    if (__dc_isPromise(timerValue)) {
                      return Promise.resolve(timerValue).then(function(timerResolved) {
                        return __dc_finalize(providerValue, parserResolved, timerResolved);
                      });
                    }
                    return __dc_finalize(providerValue, parserResolved, timerValue);
                  });
                }
                let timerValue = __dc_hasTimer
                  ? scheduleTimer({ providerRes: globalThis.__dc_normalize(providerValue), parserRes: parserValue })
                  : undefined;
                if (__dc_isPromise(timerValue)) {
                  return Promise.resolve(timerValue).then(function(timerResolved) {
                    return __dc_finalize(providerValue, parserValue, timerResolved);
                  });
                }
                return __dc_finalize(providerValue, parserValue, timerValue);
              }
              let __dc_entryResult;
              if (__dc_hasProvider) {
                __dc_entryResult = scheduleHtmlProvider(__dc_html, "", null);
              } else if (__dc_hasParser) {
                __dc_entryResult = scheduleHtmlParser(__dc_html);
              } else if (__dc_hasParse) {
                __dc_entryResult = parse(__dc_html);
              } else {
                throw new Error("No compatible entry function found");
              }
              globalThis.__dc_error = undefined;
              globalThis.__dc_flowResolved = false;
              globalThis.__dc_flowValue = "";
              if (__dc_isPromise(__dc_entryResult)) {
                Promise.resolve(__dc_entryResult)
                  .then(function(resolvedEntry) {
                    if (__dc_hasProvider) {
                      const flowResult = __dc_runAfterProvider(resolvedEntry);
                      if (__dc_isPromise(flowResult)) {
                        return Promise.resolve(flowResult).then(function(finalResult) {
                          globalThis.__dc_flowValue = finalResult;
                          globalThis.__dc_flowResolved = true;
                        });
                      }
                      globalThis.__dc_flowValue = flowResult;
                      globalThis.__dc_flowResolved = true;
                      return;
                    }
                    globalThis.__dc_flowValue = globalThis.__dc_normalize(resolvedEntry);
                    globalThis.__dc_flowResolved = true;
                  })
                  .catch(function(e) {
                    globalThis.__dc_error = String(e);
                    globalThis.__dc_flowResolved = true;
                  });
                return true;
              }
              if (__dc_hasProvider) {
                const flowResult = __dc_runAfterProvider(__dc_entryResult);
                if (__dc_isPromise(flowResult)) {
                  Promise.resolve(flowResult)
                    .then(function(finalResult) {
                      globalThis.__dc_flowValue = finalResult;
                      globalThis.__dc_flowResolved = true;
                    })
                    .catch(function(e) {
                      globalThis.__dc_error = String(e);
                      globalThis.__dc_flowResolved = true;
                    });
                  return true;
                }
                globalThis.__dc_flowValue = flowResult;
                globalThis.__dc_flowResolved = true;
                return false;
              }
              globalThis.__dc_flowValue = globalThis.__dc_normalize(__dc_entryResult);
              globalThis.__dc_flowResolved = true;
              return false;
            })();
            """.trimIndent()
        )
        if (isPromise is Boolean && isPromise) {
            if (hasPendingJobsSupport(quickJs)) {
                repeat(200) {
                    executePendingJobs(quickJs)
                    val resolved = quickJs.evaluate("globalThis.__dc_flowResolved === true")
                    if (resolved is Boolean && resolved) return@repeat
                }
            }
        }
        val error = quickJs.evaluate("globalThis.__dc_error")?.toString()
        if (!error.isNullOrBlank()) {
            // JS 侧错误字符串可能包含页面片段/请求信息等敏感内容：
            // - 不作为异常 message 直接向上冒泡，避免被 UI 展示或被日志系统采集
            // - 作为 cause 保留，便于在需要时定位解析器问题
            throw ScriptExecutionException("解析器执行失败", RuntimeException(error))
        }
        return quickJs.evaluate("globalThis.__dc_flowValue")?.toString() ?: ""
    }

    private fun executePendingJobs(quickJs: QuickJs) {
        val method = quickJs.javaClass.methods.firstOrNull {
            it.name == "executePendingJobs" && it.parameterCount == 0
        } ?: return
        repeat(100) {
            val result = method.invoke(quickJs)
            when (result) {
                is Boolean -> if (!result) return
                is Int -> if (result == 0) return
            }
        }
    }

    private fun hasPendingJobsSupport(quickJs: QuickJs): Boolean {
        return quickJs.javaClass.methods.any { it.name == "executePendingJobs" && it.parameterCount == 0 }
    }
}
