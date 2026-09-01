package com.dawncourse.feature.timetable.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 状态刷新闹钟的纯决策结果。
 */
enum class PersistentNotificationRefreshAction {
    /** 仅取消旧闹钟。 */
    CANCEL,

    /** 安排未来边界。 */
    SCHEDULE,

    /** 取消旧闹钟并补交一次唯一即时对账。 */
    CANCEL_AND_RECONCILE
}

/**
 * 状态刷新调度的纯策略。
 */
object PersistentNotificationRefreshPolicy {
    /**
     * 未来边界正常调度；首次发现过期边界补交一次对账；重复过期只取消，避免自激循环。
     */
    fun decide(
        nextRefreshAt: Instant?,
        now: Instant,
        expiredReconcileAlreadyRequested: Boolean
    ): PersistentNotificationRefreshAction = when {
        nextRefreshAt == null -> PersistentNotificationRefreshAction.CANCEL
        nextRefreshAt.isAfter(now) -> PersistentNotificationRefreshAction.SCHEDULE
        expiredReconcileAlreadyRequested -> PersistentNotificationRefreshAction.CANCEL
        else -> PersistentNotificationRefreshAction.CANCEL_AND_RECONCILE
    }
}

/**
 * 课程状态通知的事件驱动刷新调度器。
 *
 * 仅维护一个固定、显式且可取消的广播 PendingIntent。用户可感知的课程边界优先使用精确闹钟，
 * 权限不可用或 ROM 拒绝调用时降级为允许 Doze 的非精确闹钟。
 */
object PersistentNotificationRefreshScheduler {
    /** 状态边界刷新广播 action。 */
    const val ACTION_REFRESH_COURSE_STATUS = "com.dawncourse.action.REFRESH_COURSE_STATUS"

    /** 固定 data URI，避免与其他显式广播共享 PendingIntent 身份。 */
    const val REFRESH_DATA_URI = "dawn://course-status/refresh"

    /** 状态刷新广播固定 requestCode。 */
    const val REFRESH_REQUEST_CODE = 999

    /**
     * 当前进程内是否已针对过期边界补交过即时对账。
     * 一旦成功看到未来边界就复位，因此每轮收敛最多补交一次。
     */
    private val expiredReconcileRequested = AtomicBoolean(false)

    /**
     * 在下一状态边界安排刷新；空值或已过期边界会取消旧闹钟。
     */
    fun schedule(context: Context, nextRefreshAt: Instant?) {
        val action = PersistentNotificationRefreshPolicy.decide(
            nextRefreshAt = nextRefreshAt,
            now = Instant.now(),
            expiredReconcileAlreadyRequested = expiredReconcileRequested.get()
        )
        when (action) {
            PersistentNotificationRefreshAction.CANCEL -> {
                cancel(context)
                return
            }
            PersistentNotificationRefreshAction.CANCEL_AND_RECONCILE -> {
                cancel(context)
                if (expiredReconcileRequested.compareAndSet(false, true)) {
                    ReminderScheduler.triggerImmediateWork(
                        context.applicationContext,
                        forceReplay = false
                    )
                }
                return
            }
            PersistentNotificationRefreshAction.SCHEDULE -> {
                expiredReconcileRequested.set(false)
            }
        }

        val futureRefreshAt = nextRefreshAt ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = createPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return
        val triggerAtMillis = futureRefreshAt.toEpochMilli()
        val canUseExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (canUseExactAlarm) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    return
                } catch (_: SecurityException) {
                    // 精确权限可能在能力检查后被撤销，继续执行非精确降级。
                } catch (_: Throwable) {
                    // ROM 实现异常时仍尝试非精确闹钟。
                }
            }
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } catch (_: Throwable) {
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }
        } else {
            try {
                if (canUseExactAlarm) {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (_: Throwable) {
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }
        }
    }

    /**
     * 取消课程状态通知的唯一边界刷新闹钟。
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = createPendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { alarmManager.cancel(pendingIntent) }
        runCatching { pendingIntent.cancel() }
    }

    /**
     * 创建状态刷新广播的唯一 PendingIntent。
     */
    private fun createPendingIntent(context: Context, lookupFlag: Int): PendingIntent? {
        val intent = Intent(context, PersistentNotificationRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH_COURSE_STATUS
            data = Uri.parse(REFRESH_DATA_URI)
        }
        return PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            intent,
            lookupFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
