package com.dawncourse.feature.import_module.engine

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * QuickJS 求值的最小、供应商无关结果类型。
 *
 * 脚本宿主契约只需要布尔状态和 JSON 文本；禁止让 wrapper 的 JSObject 或 Java bridge
 * 穿透到解析器编排层。
 */
internal sealed interface QuickJsEvaluationValue {
    /** JavaScript 布尔值。 */
    data class BooleanValue(val value: Boolean) : QuickJsEvaluationValue

    /** JavaScript 字符串值。 */
    data class TextValue(val value: String) : QuickJsEvaluationValue

    /** JavaScript 的 undefined、null 或本宿主不消费的值。 */
    data object Empty : QuickJsEvaluationValue
}

/**
 * 隔离 QuickJS wrapper 的最小运行时契约。
 *
 * 当前 Harlon wrapper 的 native `evaluate` 会在返回前同步执行已排队的 Promise job，
 * 因而上层不得反射或轮询 `executePendingJobs`；如该 vendor 语义变更，必须先修改此
 * Adapter 并补契约测试，不能让调用方感知 wrapper API。
 */
internal interface QuickJsRuntimeAdapter : AutoCloseable {
    /** 在当前运行时线程中求值 JavaScript 源码。 */
    fun evaluate(source: String): QuickJsEvaluationValue

    /** 释放 native runtime；实现必须支持重复关闭。 */
    override fun close()
}

/** 用于测试和运行时创建隔离的 QuickJS Adapter。 */
internal fun interface QuickJsRuntimeFactory {
    /** 创建绑定当前线程的 QuickJS Adapter。 */
    fun create(): QuickJsRuntimeAdapter
}

/**
 * 把任意 Adapter 约束到创建它的线程，并把重复 close 收敛为安全的空操作。
 *
 * Harlon Context 本身也检查线程；这里额外保证适配器契约可在 JVM 单测中验证，且不让
 * 未来 wrapper 替换时悄悄放宽线程边界。
 */
internal class ThreadConfinedQuickJsRuntimeAdapter(
    private val delegate: QuickJsRuntimeAdapter,
    private val ownerThreadId: Long = Thread.currentThread().id
) : QuickJsRuntimeAdapter {
    private var closed: Boolean = false

    override fun evaluate(source: String): QuickJsEvaluationValue {
        checkOwnerThread()
        check(!closed) { "QuickJS runtime is closed" }
        return delegate.evaluate(source)
    }

    override fun close() {
        checkOwnerThread()
        if (closed) return
        closed = true
        delegate.close()
    }

    /** 验证 Context 的所有 native 调用均发生在同一条服务线程。 */
    private fun checkOwnerThread() {
        check(Thread.currentThread().id == ownerThreadId) {
            "QuickJS runtime accessed from a different thread"
        }
    }
}

/**
 * :script_runtime 进程专用的 Harlon QuickJS factory。
 *
 * Loader 初始化在锁内同步执行且仅成功一次；调用方只能经由 ScriptRuntimeService 的
 * single-thread executor 进入，主进程不得直接创建该 factory。
 */
internal object HarlonQuickJsRuntimeFactory : QuickJsRuntimeFactory {
    private const val MEMORY_LIMIT_BYTES: Int = 64 * 1024 * 1024
    private const val MAX_STACK_BYTES: Int = 512 * 1024

    private val loaderLock = Any()

    @Volatile
    private var loaderInitialized: Boolean = false

    override fun create(): QuickJsRuntimeAdapter {
        initializeLoaderOnce()
        val context = try {
            QuickJSContext.create()
        } catch (_: Throwable) {
            throw ScriptEngine.ScriptExecutionException("QuickJS runtime creation failed")
        }

        return try {
            context.setMemoryLimit(MEMORY_LIMIT_BYTES)
            context.setMaxStackSize(MAX_STACK_BYTES)
            ThreadConfinedQuickJsRuntimeAdapter(HarlonQuickJsContextAdapter(context))
        } catch (_: Throwable) {
            runCatching { context.close() }
            throw ScriptEngine.ScriptExecutionException("QuickJS runtime configuration failed")
        }
    }

    /**
     * 在隔离服务建立 Binder 连接前完成 native library 冷加载。
     *
     * 这一步不创建 QuickJS Context，因此不会破坏 Context 必须由执行线程创建和使用的
     * 线程约束；它只把进程启动成本与真正的脚本执行预算分离开。
     */
    fun preloadNative() {
        initializeLoaderOnce()
    }

    /** 同步加载 native library；失败时不标记成功，下一次新进程调用可重新尝试。 */
    private fun initializeLoaderOnce() {
        if (loaderInitialized) return
        synchronized(loaderLock) {
            if (loaderInitialized) return
            try {
                QuickJSLoader.init()
                loaderInitialized = true
            } catch (_: Throwable) {
                throw ScriptEngine.ScriptExecutionException("QuickJS native loader initialization failed")
            }
        }
    }
}

/** Harlon Context 到内部值类型的唯一转换点，禁止向外暴露 wrapper 对象。 */
private class HarlonQuickJsContextAdapter(
    private val context: QuickJSContext
) : QuickJsRuntimeAdapter {
    override fun evaluate(source: String): QuickJsEvaluationValue = try {
        when (val value = context.evaluate(source)) {
            is Boolean -> QuickJsEvaluationValue.BooleanValue(value)
            is String -> QuickJsEvaluationValue.TextValue(value)
            else -> QuickJsEvaluationValue.Empty
        }
    } catch (_: Throwable) {
        throw ScriptEngine.ScriptExecutionException("QuickJS evaluation failed")
    }

    override fun close() {
        try {
            context.close()
        } catch (_: Throwable) {
            throw ScriptEngine.ScriptExecutionException("QuickJS close failed")
        }
    }
}
