package com.dawncourse.app

import android.app.Application
import android.content.Context
import com.dawncourse.app.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用程序入口类 (Application Class)
 *
 * 必须使用 @HiltAndroidApp 注解标记，以触发 Hilt 的代码生成。
 * Hilt 会生成一个基类 Application，充当应用级别的依赖容器。
 * 所有使用 Hilt 的模块都必须依赖此类。
 */
@HiltAndroidApp
class DawnApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 全局崩溃捕获必须在这里安装，而不是 onCreate()：
        //
        // Android 进程启动顺序为
        //   Application.attachBaseContext() → 各 ContentProvider.onCreate() → Application.onCreate()
        //
        // androidx.startup.InitializationProvider 及其 App Startup 初始化器
        // （WidgetSyncInitializer / WorkManagerInitializer 等）都在 onCreate() 之前的
        // provider 阶段执行。冷启动白屏闪退恰恰可能发生在这一段——若等到 onCreate()
        // 再安装 handler，这段崩溃就永远落不了盘。
        CrashReporter.install(base)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
