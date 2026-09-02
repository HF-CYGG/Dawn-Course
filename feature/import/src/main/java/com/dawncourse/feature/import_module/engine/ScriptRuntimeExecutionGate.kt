package com.dawncourse.feature.import_module.engine

import java.io.File

/**
 * 串行化“已连接、已提交、已取消”三种状态，避免绑定超时与 late callback 并发提交同一请求。
 *
 * 所有状态读写在同一把锁内完成：若取消先发生，后到的连接只能要求回收其 PID；若提交先
 * 发生，后到的取消会得到同一 PID 并由调用方终止整个隔离进程。
 */
internal class ScriptRuntimeExecutionGate {
    private val lock = Any()
    private var cancelled: Boolean = false
    private var submitted: Boolean = false
    private var remoteProcessId: Int = 0

    /** 记录 Binder 已连接的进程，并返回是否允许将本次请求提交给远端。 */
    fun onConnected(processId: Int): ConnectionDecision = synchronized(lock) {
        remoteProcessId = processId
        when {
            cancelled -> ConnectionDecision(shouldSubmit = false, processIdToReclaim = processId)
            submitted -> ConnectionDecision(shouldSubmit = false, processIdToReclaim = processId)
            else -> {
                submitted = true
                ConnectionDecision(shouldSubmit = true, processIdToReclaim = 0)
            }
        }
    }

    /** 原子标记取消，并把已经获知的 PID 交给调用方进行进程级回收。 */
    fun cancelAndGetProcessId(): Int = synchronized(lock) {
        cancelled = true
        remoteProcessId
    }

    /** 供 Binder 回调判断连接是否已被取消；测试不依赖 Android 生命周期。 */
    fun isCancelled(): Boolean = synchronized(lock) { cancelled }

    /** Binder 连接后的明确动作，避免调用方用分散布尔值重新制造竞态。 */
    data class ConnectionDecision(
        val shouldSubmit: Boolean,
        val processIdToReclaim: Int
    )
}

/**
 * 仅清理历史版本遗留的 raw JSON 文件。
 *
 * 新 IPC 永不把请求或响应落盘。为了避免“清理”本身扩大删除范围，此工具只接受应用 cache
 * 目录直接子目录 `script_runtime`，且只删除其一级中严格匹配的 `request-*.json` 与
 * `response-*.json` 普通文件；不会递归，也不会创建目录。
 */
internal object LegacyScriptRuntimeFileCleanup {
    private val legacyFileName = Regex("^(request|response)-.+\\.json$")

    /** 清理精确的旧版传输文件，路径验证失败时保持无操作。 */
    fun clear(cacheDirectory: File) {
        val expectedDirectory = File(cacheDirectory, "script_runtime")
        val canonicalCache = runCatching { cacheDirectory.canonicalFile }.getOrNull() ?: return
        val canonicalDirectory = runCatching { expectedDirectory.canonicalFile }.getOrNull() ?: return
        if (canonicalDirectory.parentFile != canonicalCache || !canonicalDirectory.isDirectory) return

        canonicalDirectory.listFiles()
            ?.asSequence()
            ?.filter { candidate -> candidate.isFile && legacyFileName.matches(candidate.name) }
            ?.forEach { candidate -> runCatching { candidate.delete() } }
    }
}
