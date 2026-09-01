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

    fun isEnabled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        ).metaData?.getBoolean(META_DATA_KEY, false) ?: false
    }.getOrDefault(false)
}
