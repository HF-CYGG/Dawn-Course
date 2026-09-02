package com.dawncourse.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * 独立 `:recovery_restart` 进程中的短生命周期跳板。
 *
 * 它先成为前台 Activity，再终止旧主进程并重新启动 launcher，从而可靠重建 Hilt
 * SingletonComponent；不依赖可能延迟很久的 inexact Alarm。
 */
class RecoveryRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldMainPid = intent.getIntExtra(EXTRA_OLD_MAIN_PID, -1)
        if (oldMainPid <= 0 || oldMainPid == Process.myPid()) {
            finishAndRemoveTask()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            finishAndRemoveTask()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).postDelayed(
            {
                Process.killProcess(oldMainPid)
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        startActivity(launchIntent)
                        finishAndRemoveTask()
                    },
                    RELAUNCH_DELAY_MILLIS
                )
            },
            KILL_DELAY_MILLIS
        )
    }

    companion object {
        const val EXTRA_OLD_MAIN_PID = "old_main_pid"
        private const val KILL_DELAY_MILLIS = 200L
        private const val RELAUNCH_DELAY_MILLIS = 300L
    }
}

/** 只有跳板 Activity 启动成功后才移交重启；启动失败时绝不终止当前进程。 */
object ControlledProcessRestarter {
    /** 返回跳板 Activity 是否已成功启动；失败时调用方必须保留手动重启入口。 */
    fun restart(activity: Activity): Boolean {
        val started = runCatching {
            activity.startActivity(
                Intent(activity, RecoveryRestartActivity::class.java)
                    .putExtra(RecoveryRestartActivity.EXTRA_OLD_MAIN_PID, Process.myPid())
            )
        }.isSuccess
        // 跳板与主 Activity 可能共享 task affinity；这里不能 finishAffinity，否则会连跳板一起结束。
        return started
    }
}
