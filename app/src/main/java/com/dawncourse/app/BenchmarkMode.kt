package com.dawncourse.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * 仅由 benchmark 源集 manifest 开启的运行模式。
 *
 * 正常 debug/release manifest 不包含该 metadata，因此生产运行时保持原有行为。
 */
object BenchmarkMode {
    private const val META_DATA_KEY = "com.dawncourse.app.BENCHMARK_MODE"

    fun isEnabled(context: Context): Boolean {
        // release/debug 的常量为 false；先在进程内短路，禁止冷启动额外发起 PackageManager Binder。
        if (!BuildConfig.BENCHMARK_MODE) return false
        return isBenchmarkModeEnabled(buildEnablesBenchmark = true) {
            runCatching {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA
                ).metaData?.getBoolean(META_DATA_KEY, false) ?: false
            }.getOrDefault(false)
        }
    }
}

/** benchmark 变体仍由 Manifest metadata 控制，避免仅凭变体名误启用。 */
internal fun isBenchmarkModeEnabled(
    buildEnablesBenchmark: Boolean,
    readManifestMetadata: () -> Boolean,
): Boolean = buildEnablesBenchmark && readManifestMetadata()
