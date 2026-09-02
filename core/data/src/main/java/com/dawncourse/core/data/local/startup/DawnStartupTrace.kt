package com.dawncourse.core.data.local.startup

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/** Dawn 冷启动关键路径的系统 Trace；任何业务异常都必须闭合对应 section。 */
internal object DawnStartupTrace {
    const val FILE_LOCK = "Dawn#FileLock"
    const val RECOVERY_CHECK = "Dawn#RecoveryCheck"
    const val KEYSTORE_UNSEAL = "Dawn#KeystoreUnseal"
    const val LOAD_LIBRARY = "Dawn#LoadLibrary"
    const val ROOM_BUILD = "Dawn#RoomBuild"
    const val KDF_AND_FIRST_OPEN = "Dawn#KdfAndFirstOpen"
    const val INTEGRITY_CHECK = "Dawn#IntegrityCheck"
    const val CIPHER_INTEGRITY_CHECK = "Dawn#CipherIntegrityCheck"
    const val GET_FIRST_PROFILE = "Dawn#GetFirstProfile"
    const val RESOLVE_PROFILE_SELECTION = "Dawn#ResolveProfileSelection"

    private val nextAsyncCookie = AtomicInteger(1)

    /** 使用 finally 保证 beginSection/endSection 在所有异常路径配对。 */
    inline fun <T> section(label: String, block: () -> T): T = section(
        label = label,
        beginSection = Trace::beginSection,
        endSection = Trace::endSection,
        block = block,
    )

    /** 供 JVM 测试替换平台 Trace 回调，验证标签与异常路径配对。 */
    @PublishedApi
    internal inline fun <T> section(
        label: String,
        beginSection: (String) -> Unit,
        endSection: () -> Unit,
        block: () -> T,
    ): T {
        val started = runCatching {
            beginSection(label)
        }.isSuccess
        return try {
            block()
        } finally {
            // Trace 平台不可用不能影响启动决策；只有成功 begin 的 section 才允许结束。
            if (started) runCatching { endSection() }
        }
    }

    /**
     * 协程路径使用异步 Trace，允许 begin/end 分别发生在不同线程。
     * API 26-28 不存在异步 Trace API，保留业务块执行且不产生追踪调用。
     */
    suspend fun <T> asyncSection(label: String, block: suspend () -> T): T {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return block()
        return asyncSection(
            label = label,
            cookie = nextAsyncCookie.getAndIncrement(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
            beginAsyncSection = Api29Trace::beginAsyncSection,
            endAsyncSection = Api29Trace::endAsyncSection,
            block = block,
        )
    }

    /** 供 JVM 测试验证 cookie 配对、低版本降级和异常隔离。 */
    @PublishedApi
    internal suspend fun <T> asyncSection(
        label: String,
        cookie: Int,
        sdkInt: Int,
        beginAsyncSection: (String, Int) -> Unit,
        endAsyncSection: (String, Int) -> Unit,
        block: suspend () -> T,
    ): T {
        if (sdkInt < android.os.Build.VERSION_CODES.Q) return block()
        val started = runCatching {
            beginAsyncSection(label, cookie)
        }.isSuccess
        return try {
            block()
        } finally {
            if (started) runCatching { endAsyncSection(label, cookie) }
        }
    }

    /** 独立 API 29 调用点，避免 API 26-28 触达异步 Trace 方法。 */
    private object Api29Trace {
        fun beginAsyncSection(label: String, cookie: Int) {
            Trace.beginAsyncSection(label, cookie)
        }

        fun endAsyncSection(label: String, cookie: Int) {
            Trace.endAsyncSection(label, cookie)
        }
    }
}
