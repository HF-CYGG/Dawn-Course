package com.dawncourse.core.data.local.startup

import android.os.Trace

/** Dawn 冷启动关键路径的系统 Trace；任何业务异常都必须闭合对应 section。 */
internal object DawnStartupTrace {
    const val FILE_LOCK = "DawnStartup/FileLock"
    const val RECOVERY_CHECK = "DawnStartup/RecoveryCheck"
    const val KEYSTORE_UNSEAL = "DawnStartup/KeystoreUnseal"
    const val LOAD_LIBRARY = "DawnStartup/LoadLibrary"
    const val ROOM_BUILD = "DawnStartup/RoomBuild"
    const val KDF_AND_FIRST_OPEN = "DawnStartup/KdfAndFirstOpen"
    const val INTEGRITY_CHECK = "DawnStartup/IntegrityCheck"
    const val CIPHER_INTEGRITY_CHECK = "DawnStartup/CipherIntegrityCheck"
    const val GET_FIRST_PROFILE = "DawnStartup/GetFirstProfile"
    const val RESOLVE_PROFILE_SELECTION = "DawnStartup/ResolveProfileSelection"

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
}
