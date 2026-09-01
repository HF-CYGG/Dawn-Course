package com.dawncourse.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃捕获与展示
 *
 * 背景：
 * 本项目在多台机型上出现过“启动阶段白屏后闪退”的问题（详见各处 runCatching /
 * CoroutineExceptionHandler 兜底的注释），但项目坚持“本地优先、不强绑定云端账号”的原则，
 * 不引入 Firebase Crashlytics / Bugly 等第三方崩溃上报 SDK。
 *
 * 因此这里实现一个最小化的本地崩溃捕获：
 * 1. 崩溃发生时，把堆栈 + 关键环境信息写入本地文件（不联网、不上报）
 * 2. 下次启动 App 时，读取并通过弹窗展示，用户可一键复制后手动反馈给开发者
 *
 * 这是目前唯一能在没有真机复现条件下，把“推测原因”变成“确凿证据”的手段。
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    /**
     * 安装全局未捕获异常处理器
     *
     * 必须在 Application.onCreate() 的最开始调用：越早安装，越能覆盖后续初始化流程
     * （Hilt 组件构建、App Startup 初始化器等）中可能出现的崩溃。
     *
     * 关键点：必须保留并链式调用系统原有的 defaultUncaughtExceptionHandler。
     * 这里只是在“进程即将被杀死之前”插入一次落盘动作，不能替代系统的收尾逻辑
     * （例如：让 ActivityManager 感知崩溃、避免影响 ANR/崩溃对话框等系统行为）。
     */
    fun install(context: Context) {
        // 允许在 Application.attachBaseContext() 阶段调用：此时 applicationContext
        // 可能尚未就绪（返回 null），回退到传入的 base context，两者都能满足
        // writeCrashReport 对 filesDir / packageManager 的使用。
        val appContext = context.applicationContext ?: context
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 落盘操作本身必须绝对安全：这里已经是异常处理的最后一道关卡，
            // 如果写文件再抛出异常，会导致真正的崩溃原因丢失，甚至干扰进程退出流程。
            runCatching {
                writeCrashReport(appContext, thread, throwable)
            }.onFailure {
                // 用 Log 而非再次抛出，避免递归崩溃
                Log.e(TAG, "Failed to persist crash report", it)
            }

            // 必须链式调用原 handler，交还控制权给系统：
            // - 保证进程按系统预期的方式终止
            // - 保证系统级崩溃日志（logcat FATAL EXCEPTION / tombstone）不受影响
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, CRASH_FILE)

        val versionInfo = runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            "${packageInfo.versionName} ($versionCode)"
        }.getOrDefault("unknown")

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val stackTraceWriter = StringWriter()
        // printStackTrace(PrintWriter) 会自动递归打印 cause 链与 suppressed 异常，
        // 这里不需要手动遍历。
        throwable.printStackTrace(PrintWriter(stackTraceWriter))

        // 崩溃线程名是关键判别依据：
        // - "main" 通常指向 Compose LaunchedEffect / Activity 生命周期回调中的直接异常
        // - "DefaultDispatcher-worker-N" 通常指向 Dispatchers.Default 协程（如 App Startup 初始化器）
        // - "DefaultIoScheduler" / "pool-N-thread" 通常指向 Dispatchers.IO 协程（如 Widget 刷新）
        val report = buildString {
            appendLine("===== Dawn Course Crash Report =====")
            appendLine("Time: $timestamp")
            appendLine("App Version: $versionInfo")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Crashed Thread: ${thread.name}")
            appendLine("=====================================")
            append(stackTraceWriter.toString())
        }

        // 单文件覆盖写入（非追加），避免设备长期未处理时崩溃报告无限增长占用存储。
        file.writeText(report)
    }

    /**
     * 读取上一次崩溃报告并清除
     *
     * 读取即视为“已展示给用户”，清除文件避免重复弹窗。
     * 必须在主线程之外或轻量同步调用均可接受：单次文件读取，成本很低。
     */
    fun readAndClear(context: Context): String? {
        return runCatching {
            val file = File(File(context.filesDir, CRASH_DIR), CRASH_FILE)
            if (!file.exists()) return null
            val content = file.readText()
            file.delete()
            content
        }.getOrNull()
    }
}
