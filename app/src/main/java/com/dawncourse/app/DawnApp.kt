package com.dawncourse.app

import android.app.Application
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        super.onCreate()
        CoroutineScope(Dispatchers.Default).launch {
            WidgetSyncManager.scheduleUpdate(this@DawnApp)
            WidgetSyncManager.registerTimeChangeReceiver(this@DawnApp)
        }
    }
}
