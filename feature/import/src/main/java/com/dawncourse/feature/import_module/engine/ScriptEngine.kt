package com.dawncourse.feature.import_module.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JS 脚本引擎宿主。
 *
 * 入口探测、调用编排与结果校验仍由共享 script_host.js 契约负责。QuickJS 本体只在
 * `:script_runtime` 进程中运行；若 native evaluate 卡死，主进程在预算耗尽时终止整个
 * 脚本进程，从而保证后续解析可以获得一个全新的运行时。
 */
@Singleton
class ScriptEngine @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
        const val SUPPORTED_CONTRACT_VERSION: Int = 1
        const val SUPPORTED_PARSER_API_VERSION: Int = 1
        const val SCRIPT_HOST_NAME: String = "script_host.js"
        const val SCRIPT_HOST_CATEGORY: String = "runtime"

        const val ERROR_NO_ENTRY: String = "no_entry"
        const val ERROR_SCRIPT_EXCEPTION: String = "script_exception"
        const val ERROR_TIMEOUT: String = "timeout"
        const val ERROR_EMPTY_RESULT: String = "empty_result"
        const val ERROR_SCHEMA_INVALID: String = "schema_invalid"
        const val ERROR_DUPLICATE_RATIO_HIGH: String = "duplicate_ratio_high"
        const val ERROR_DO_NOT_CONTINUE: String = "do_not_continue"
        const val ERROR_HARNESS_MISSING: String = "harness_missing"
        const val ERROR_RESULT_TOO_LARGE: String = "result_too_large"

        private val CRASH_LIKE_ERRORS = setOf(
            ERROR_SCRIPT_EXCEPTION,
            ERROR_NO_ENTRY,
            ERROR_TIMEOUT,
            ERROR_HARNESS_MISSING
        )

        private const val SERVICE_BIND_TIMEOUT_MS: Long = 5_000L

        internal fun failureResult(errorCode: String, message: String) = ScriptExecutionResult(
            raw = "",
            ok = false,
            schemaValid = false,
            resultCount = 0,
            errorCode = errorCode,
            errorMessage = message,
            entryUsed = "",
            contractVersion = SUPPORTED_CONTRACT_VERSION
        )
    }

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

    class ScriptExecutionException(
        message: String,
        val errorCode: String = ERROR_SCRIPT_EXCEPTION,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    private val appContext = context.applicationContext
    private val executionMutex = Mutex()

    @Volatile
    internal var lastRuntimeProcessId: Int = 0
        private set

    @Volatile
    internal var lastTerminatedRuntimeProcessId: Int = 0
        private set

    suspend fun parseHtml(
        script: String,
        html: String,
        harnessSource: String,
        dependencies: List<String> = emptyList(),
        targetType: String = "parser",
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): ScriptExecutionResult = executionMutex.withLock {
        withContext(Dispatchers.IO) {
            val normalizedTimeout = ScriptRuntimeLimits.normalizeTimeout(timeoutMillis)
            val validation = ScriptRuntimeLimits.validateInput(
                harnessBytes = ScriptRuntimeLimits.utf8Size(harnessSource),
                scriptAndDependencyBytes = ScriptRuntimeLimits.utf8Size(script) +
                    dependencies.sumOf(ScriptRuntimeLimits::utf8Size),
                htmlBytes = ScriptRuntimeLimits.utf8Size(html),
                timeoutMillis = normalizedTimeout
            )
            val result = when {
                harnessSource.isBlank() -> failureResult(
                    ERROR_HARNESS_MISSING,
                    "script harness is missing"
                )
                !validation.isValid -> failureResult(
                    validation.errorCode,
                    "script runtime input exceeds limit"
                )
                else -> executeRemote(
                    ScriptRuntimeRequest(
                        script = script,
                        html = html,
                        harnessSource = harnessSource,
                        dependencies = dependencies,
                        targetType = targetType,
                        timeoutMillis = normalizedTimeout
                    )
                )
            }

            if (result.errorCode in CRASH_LIKE_ERRORS) {
                throw ScriptExecutionException("解析器执行失败", result.errorCode)
            }
            result
        }
    }

    private suspend fun executeRemote(
        request: ScriptRuntimeRequest
    ): ScriptExecutionResult {
        val runtimeDir = File(appContext.cacheDir, "script_runtime").apply { mkdirs() }
        val requestFile = File.createTempFile("request-", ".json", runtimeDir)
        val responseFile = File.createTempFile("response-", ".json", runtimeDir)
        requestFile.writeText(request.toJson(), Charsets.UTF_8)

        val completion = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Int>()
        var bound = false
        var executionStarted = false
        val remoteProcessId = AtomicInteger(0)
        val callback = object : IScriptRuntimeCallback.Stub() {
            override fun onComplete() {
                completion.complete(Unit)
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching {
                    val runtime = IScriptRuntime.Stub.asInterface(binder)
                    remoteProcessId.set(runtime.processId)
                    lastRuntimeProcessId = remoteProcessId.get()
                    connected.complete(remoteProcessId.get())
                    binder?.linkToDeath(
                        { completion.completeExceptionally(IllegalStateException("script runtime died")) },
                        0
                    )
                    ParcelFileDescriptor.open(
                        requestFile,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { requestDescriptor ->
                        ParcelFileDescriptor.open(
                            responseFile,
                            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
                        ).use { responseDescriptor ->
                            runtime.execute(requestDescriptor, responseDescriptor, callback)
                        }
                    }
                }.onFailure { error ->
                    connected.completeExceptionally(error)
                    completion.completeExceptionally(error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val error = IllegalStateException("script runtime disconnected")
                connected.completeExceptionally(error)
                completion.completeExceptionally(error)
            }

            override fun onBindingDied(name: ComponentName?) {
                val error = IllegalStateException("script runtime binding died")
                connected.completeExceptionally(error)
                completion.completeExceptionally(error)
            }

            override fun onNullBinding(name: ComponentName?) {
                val error = IllegalStateException("script runtime binding missing")
                connected.completeExceptionally(error)
                completion.completeExceptionally(error)
            }
        }

        return try {
            bound = appContext.bindService(
                Intent(appContext, ScriptRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                return failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime binding failed")
            }
            withTimeout(SERVICE_BIND_TIMEOUT_MS) { connected.await() }
            executionStarted = true
            withTimeout(request.timeoutMillis) { completion.await() }
            val responseSize = responseFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (!ScriptRuntimeLimits.isResultSizeValid(responseSize)) {
                failureResult(ERROR_RESULT_TOO_LARGE, "script result exceeds limit")
            } else {
                scriptExecutionResultFromJson(responseFile.readText(Charsets.UTF_8))
            }
        } catch (_: TimeoutCancellationException) {
            if (executionStarted) {
                killRuntimeProcess(remoteProcessId.get())
                failureResult(ERROR_TIMEOUT, "script execution exceeded process budget")
            } else {
                failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime binding timed out")
            }
        } catch (error: Throwable) {
            failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime failed: ${error.javaClass.simpleName}")
        } finally {
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
            requestFile.delete()
            responseFile.delete()
        }
    }

    private fun killRuntimeProcess(processId: Int) {
        if (processId > 0 && processId != Process.myPid()) {
            lastTerminatedRuntimeProcessId = processId
            Process.killProcess(processId)
        }
    }
}
