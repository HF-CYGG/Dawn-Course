package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 课程状态边界刷新接收器。
 *
 * 接收器不读取数据库、不生成通知，只把事件交给现有唯一即时 WorkManager 对账链。
 */
class PersistentNotificationRefreshReceiver : BroadcastReceiver() {
    /**
     * 仅接受调度器生成的固定 action 与 data URI。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PersistentNotificationRefreshScheduler.ACTION_REFRESH_COURSE_STATUS) return
        if (intent.dataString != PersistentNotificationRefreshScheduler.REFRESH_DATA_URI) return
        val pendingResult = goAsync()
        receiverScope.launch {
            ReceiverTaskRunner.run(
                task = {
                    if (!ReminderScheduler.triggerCourseSurfaceRefreshWorkAndAwait(
                        context.applicationContext,
                        )
                    ) {
                        Log.w(TAG, "课程状态边界刷新责任未持久化且任务未确认入队")
                    }
                },
                onFailureType = { failureType ->
                    Log.e(TAG, "课程状态边界刷新失败 type=$failureType")
                },
                finish = pendingResult::finish,
            )
        }
    }

    private companion object {
        const val TAG = "CourseStatusRefresh"
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
