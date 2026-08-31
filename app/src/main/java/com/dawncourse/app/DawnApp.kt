package com.dawncourse.app

import android.app.Application
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
    override fun onCreate() {
        // 必须在 super.onCreate() 与其余任何初始化逻辑之前安装：
        // 越早安装，越能覆盖 Hilt 组件构建、App Startup 初始化器等后续流程中
        // 可能出现的崩溃。
        CrashReporter.install(this)

        super.onCreate()
    }
}
