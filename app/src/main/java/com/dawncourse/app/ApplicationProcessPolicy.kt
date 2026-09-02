package com.dawncourse.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Application 多进程初始化策略。
 *
 * 系统课表能力只属于应用主进程；脚本运行时进程与无法可靠识别的进程均保持无副作用。
 */
internal object ApplicationProcessPolicy {

    /**
     * 判断当前进程是否允许初始化 Widget、WorkManager 等系统课表能力。
     *
     * @param packageName 应用主进程应使用的包名。
     * @param processName 当前进程的实际名称，无法识别时为 null。
     */
    fun shouldInitializeSystemSurfaces(packageName: String, processName: String?): Boolean {
        if (packageName.isBlank()) return false
        return processName?.trim() == packageName
    }

    /**
     * 解析 `/proc/self/cmdline`，只保留第一个空字节前的进程名称。
     *
     * @param bytes proc 文件的原始字节。
     */
    fun decodeProcCmdline(bytes: ByteArray): String? {
        val terminatorIndex = bytes.indexOf(0)
        val contentLength = if (terminatorIndex >= 0) terminatorIndex else bytes.size
        if (contentLength == 0) return null
        return bytes.copyOfRange(0, contentLength)
            .toString(Charsets.UTF_8)
            .trim()
            .takeIf { value -> value.isNotEmpty() }
    }
}

/** Android 平台进程名称解析器。 */
internal object ApplicationProcessNameResolver {
    /** `/proc` 进程名称文件。 */
    private const val PROC_SELF_CMDLINE_PATH = "/proc/self/cmdline"

    /**
     * 获取当前 Application 所在进程名称。
     *
     * API 28+ 使用系统直接接口；API 26/27 依次使用 ActivityManager 与 proc 文件兜底。
     */
    fun resolve(application: Application): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { Application.getProcessName() }
                .getOrNull()
                .normalizedProcessName()
        }

        return resolveFromActivityManager(application)
            ?: resolveFromProcCmdline()
    }

    /** 按当前 PID 从 ActivityManager 查询旧版本 Android 的进程名称。 */
    private fun resolveFromActivityManager(application: Application): String? {
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val currentPid = Process.myPid()
        return runCatching {
            activityManager.runningAppProcesses
                ?.firstOrNull { process -> process.pid == currentPid }
                ?.processName
        }.getOrNull().normalizedProcessName()
    }

    /** ActivityManager 不可用时读取内核提供的当前进程命令行。 */
    private fun resolveFromProcCmdline(): String? = runCatching {
        ApplicationProcessPolicy.decodeProcCmdline(File(PROC_SELF_CMDLINE_PATH).readBytes())
    }.getOrNull()

    /** 将空白平台值统一视为无法识别。 */
    private fun String?.normalizedProcessName(): String? = this
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
}
