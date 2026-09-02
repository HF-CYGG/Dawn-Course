package com.dawncourse.feature.import_module.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** QuickJS 专用进程入口；同步死循环由主进程在预算耗尽时整体终止。 */
class ScriptRuntimeService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dawn-quickjs-runtime")
    }

    private val binder = object : IScriptRuntime.Stub() {
        override fun getProcessId(): Int = Process.myPid()

        override fun execute(
            request: ParcelFileDescriptor,
            response: ParcelFileDescriptor,
            callback: IScriptRuntimeCallback
        ) {
            executor.execute {
                val resultJson = runCatching {
                    val requestText = ParcelFileDescriptor.AutoCloseInputStream(request)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val runtimeRequest = ScriptRuntimeRequest.fromJson(requestText)
                    callback.onExecutionStarted()
                    QuickJsScriptExecutor()
                        .execute(runtimeRequest)
                        .toProtocolJson()
                }.getOrElse {
                    ScriptEngine.failureResult(
                        ScriptEngine.ERROR_SCRIPT_EXCEPTION,
                        "script runtime request failed"
                    ).toProtocolJson()
                }.let { encoded ->
                    if (ScriptRuntimeLimits.isResultSizeValid(ScriptRuntimeLimits.utf8Size(encoded))) {
                        encoded
                    } else {
                        ScriptEngine.failureResult(
                            ScriptEngine.ERROR_RESULT_TOO_LARGE,
                            "script result exceeds limit"
                        ).toProtocolJson()
                    }
                }

                runCatching {
                    FileOutputStream(response.fileDescriptor).bufferedWriter(Charsets.UTF_8).use {
                        it.write(resultJson)
                    }
                }
                runCatching { response.close() }
                runCatching { callback.onComplete() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // native 冷加载属于隔离进程启动成本，不应消耗单次脚本的 5 秒执行预算。
        HarlonQuickJsRuntimeFactory.preloadNative()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
