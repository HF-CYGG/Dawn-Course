package com.dawncourse.core.data.local.startup

/** 进程内统一加载 SQLCipher native core；任何 SQLCipher Java API 都必须先经过这里。 */
internal object SqlCipherNativeLoader {
    private val loaded: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        System.loadLibrary("sqlcipher")
    }

    fun ensureLoaded() {
        loaded
    }
}
