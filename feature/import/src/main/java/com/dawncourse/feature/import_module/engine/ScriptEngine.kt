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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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

        // 独立覆盖隔离进程创建与 native 冷加载；脚本执行仍受 request.timeoutMillis 限制。
        private const val SERVICE_BIND_TIMEOUT_MS: Long = 10_000L
        // 大请求的 Pipe 传输与协议解码不占用脚本执行预算，但同样必须有硬上限。
        private const val SERVICE_PREPARATION_TIMEOUT_MS: Long = 10_000L
        private const val SERVICE_TERMINATION_TIMEOUT_MS: Long = 1_000L
        private const val DEFAULT_PIPE_BUFFER_BYTES: Int = 8 * 1024

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

        /** 未知 Binder/IPC 故障统一映射，避免协议泄露供应商异常类名、堆栈或脚本内容。 */
        internal fun unexpectedRuntimeFailureResult(): ScriptExecutionResult =
            failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime failed")
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

    init {
        // 仅删除旧版本精确命名的 raw IPC 文件；新实现全程仅使用匿名 Pipe。
        LegacyScriptRuntimeFileCleanup.clear(appContext.cacheDir)
    }

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
    ): ScriptExecutionResult = coroutineScope {
        val requestPipe = ParcelFileDescriptor.createPipe()
        val responsePipe = ParcelFileDescriptor.createPipe()
        val requestRead = requestPipe[0]
        val requestWrite = requestPipe[1]
        val responseRead = responsePipe[0]
        val responseWrite = responsePipe[1]
        val requestBytes = request.toJson().toByteArray(Charsets.UTF_8)
        val executionStarted = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Int>()
        val runtimeDied = CompletableDeferred<Unit>()
        val gate = ScriptRuntimeExecutionGate()
        var bound = false
        val remoteProcessId = AtomicInteger(0)
        val requestWriter = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(requestWrite).use { output ->
                    output.write(requestBytes)
                    output.flush()
                }
            }.fold(
                onSuccess = { PipeWriteResult.Complete },
                onFailure = { PipeWriteResult.Failed }
            )
        }
        val responseReader = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching {
                readPipeAtMost(responseRead, ScriptRuntimeLimits.MAX_RESULT_BYTES)
            }.getOrElse { PipeReadResult.Failed }
        }
        val callback = object : IScriptRuntimeCallback.Stub() {
            override fun onExecutionStarted() {
                executionStarted.complete(Unit)
            }

            override fun onComplete() {
                completion.complete(Unit)
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching {
                    val runtime = IScriptRuntime.Stub.asInterface(binder)
                    val processId = runtime.processId
                    remoteProcessId.set(processId)
                    lastRuntimeProcessId = processId
                    binder?.linkToDeath(
                        {
                            runtimeDied.complete(Unit)
                            completion.completeExceptionally(IllegalStateException("script runtime died"))
                        },
                        0
                    )
                    val decision = gate.onConnected(processId)
                    if (decision.shouldSubmit && !gate.isCancelled()) {
                        responseReader.start()
                        requestWriter.start()
                        try {
                            runtime.execute(requestRead, responseWrite, callback)
                        } finally {
                            // Binder 已复制 descriptor；本地端必须关闭，才能把 Pipe EOF 交给服务端。
                            runCatching { requestRead.close() }
                            runCatching { responseWrite.close() }
                        }
                    } else {
                        killRuntimeProcess(decision.processIdToReclaim)
                    }
                    connected.complete(processId)
                }.onFailure { error ->
                    connected.completeExceptionally(error)
                    completion.completeExceptionally(error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                runtimeDied.complete(Unit)
                val error = IllegalStateException("script runtime disconnected")
                connected.completeExceptionally(error)
                completion.completeExceptionally(error)
            }

            override fun onBindingDied(name: ComponentName?) {
                runtimeDied.complete(Unit)
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

        fun unbindIfNeeded() {
            if (bound) {
                bound = false
                runCatching { appContext.unbindService(connection) }
            }
        }

        try {
            bound = appContext.bindService(
                Intent(appContext, ScriptRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime binding failed")
            } else {
                withTimeout(SERVICE_BIND_TIMEOUT_MS) { connected.await() }
                withTimeout(SERVICE_PREPARATION_TIMEOUT_MS) { executionStarted.await() }
                val result = withTimeout(request.timeoutMillis) {
                    completion.await()
                    when (requestWriter.await()) {
                        PipeWriteResult.Failed -> failureResult(
                            ERROR_SCRIPT_EXCEPTION,
                            "script runtime request pipe failed"
                        )
                        PipeWriteResult.Complete -> when (val response = responseReader.await()) {
                            is PipeReadResult.Complete -> scriptExecutionResultFromJson(response.text)
                            PipeReadResult.TooLarge -> failureResult(
                                ERROR_RESULT_TOO_LARGE,
                                "script result exceeds limit"
                            )
                            PipeReadResult.Failed -> failureResult(
                                ERROR_SCRIPT_EXCEPTION,
                                "script runtime response pipe failed"
                            )
                        }
                    }
                }
                result
            }
        } catch (_: TimeoutCancellationException) {
            val processId = gate.cancelAndGetProcessId()
            killRuntimeProcess(processId)
            unbindIfNeeded()
            awaitRuntimeTermination(runtimeDied, processId)
            if (processId > 0 || remoteProcessId.get() > 0) {
                failureResult(ERROR_TIMEOUT, "script execution exceeded process budget")
            } else {
                failureResult(ERROR_SCRIPT_EXCEPTION, "script runtime binding timed out")
            }
        } catch (_: Throwable) {
            val processId = gate.cancelAndGetProcessId()
            killRuntimeProcess(processId)
            unbindIfNeeded()
            awaitRuntimeTermination(runtimeDied, processId)
            unexpectedRuntimeFailureResult()
        } finally {
            unbindIfNeeded()
            requestWriter.cancel()
            responseReader.cancel()
            runCatching { requestRead.close() }
            runCatching { requestWrite.close() }
            runCatching { responseRead.close() }
            runCatching { responseWrite.close() }
        }
    }

    /** 强杀只负责发信号；等待 Binder death 后再允许下一次绑定，规避 OEM 进程回收竞态。 */
    private suspend fun awaitRuntimeTermination(
        runtimeDied: CompletableDeferred<Unit>,
        processId: Int
    ) {
        if (processId <= 0) return
        withTimeoutOrNull(SERVICE_TERMINATION_TIMEOUT_MS) {
            runtimeDied.await()
        }
    }

    /** 限制匿名响应 Pipe 的总字节数，超限时关闭读端让远端写入尽快失败。 */
    private fun readPipeAtMost(
        descriptor: ParcelFileDescriptor,
        maxBytes: Int
    ): PipeReadResult {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_PIPE_BUFFER_BYTES)
            while (true) {
                val remaining = maxBytes - output.size()
                val readLimit = (remaining + 1).coerceAtMost(buffer.size)
                val count = input.read(buffer, 0, readLimit)
                if (count < 0) {
                    return PipeReadResult.Complete(output.toString(Charsets.UTF_8.name()))
                }
                if (count > remaining) {
                    return PipeReadResult.TooLarge
                }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun killRuntimeProcess(processId: Int) {
        if (processId > 0 && processId != Process.myPid()) {
            lastTerminatedRuntimeProcessId = processId
            Process.killProcess(processId)
        }
    }

    private sealed interface PipeReadResult {
        data class Complete(val text: String) : PipeReadResult
        data object TooLarge : PipeReadResult
        data object Failed : PipeReadResult
    }

    /** Pipe 写端错误由主流程统一映射，避免 async 子任务取消整个超时控制协程。 */
    private sealed interface PipeWriteResult {
        data object Complete : PipeWriteResult
        data object Failed : PipeWriteResult
    }

}
