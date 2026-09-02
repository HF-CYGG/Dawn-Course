package com.dawncourse.feature.timetable.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerPrecision
import com.dawncourse.core.domain.model.TriggerUriCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** AlarmManager 的领域边界网关。 */
interface TriggerAlarmGateway {
    /** 下发触发器并返回系统实际采用的精度。 */
    fun schedule(trigger: DesiredTrigger): TriggerPrecision

    /** 按稳定 Key 取消触发器。 */
    fun cancel(key: TriggerKey)
}

/** 不依赖 Android Intent 对象的 PendingIntent 身份规格。 */
data class TriggerPendingIntentSpec(
    /** 显式广播动作。 */
    val action: String,
    /** 唯一 TriggerKey URI。 */
    val dataUri: String,
    /** 固定为 0，不再承担身份区分。 */
    val requestCode: Int,
    /** 是否使用提醒 Receiver，false 表示静音 Receiver。 */
    val reminderReceiver: Boolean
) {
    companion object {
        /** 从 TriggerKey 生成稳定规格。 */
        fun from(key: TriggerKey): TriggerPendingIntentSpec = TriggerPendingIntentSpec(
            action = TriggerIntentPolicy.expectedAction(key.kind),
            dataUri = TriggerUriCodec.encode(key),
            requestCode = 0,
            reminderReceiver = key.kind == TriggerKind.REMINDER
        )
    }
}

/** 可单元测试的 AlarmManager 下发操作集。 */
interface AlarmOperations {
    /** 尝试精确触发。 */
    fun setExact(triggerAtMillis: Long)

    /** 在 Doze 中可执行的非精确触发。 */
    fun setInexactAllowWhileIdle(triggerAtMillis: Long)

    /** 最后的普通非精确触发。 */
    fun setInexact(triggerAtMillis: Long)
}

/** 精确和非精确闹钟均无法下发时的可重试异常。 */
class TriggerSchedulingException(message: String, cause: Throwable) : Exception(message, cause)

/** exact 到 inexact 的完整、可观测降级链。 */
object AlarmDeliveryStrategy {
    /** 尝试下发闹钟，所有降级失败时抛出。 */
    fun schedule(
        canUseExact: Boolean,
        triggerAtMillis: Long,
        operations: AlarmOperations
    ): TriggerPrecision {
        if (canUseExact) {
            try {
                operations.setExact(triggerAtMillis)
                return TriggerPrecision.EXACT
            } catch (_: Throwable) {
                // 精确权限异常与 ROM 实现异常都统一进入非精确降级链。
            }
        }
        try {
            operations.setInexactAllowWhileIdle(triggerAtMillis)
            return TriggerPrecision.INEXACT
        } catch (firstFailure: Throwable) {
            try {
                operations.setInexact(triggerAtMillis)
                return TriggerPrecision.INEXACT
            } catch (lastFailure: Throwable) {
                lastFailure.addSuppressed(firstFailure)
                throw TriggerSchedulingException("精确与非精确闹钟均下发失败", lastFailure)
            }
        }
    }
}

/** 真实 AlarmManager 网关，PendingIntent 仅依赖显式 component/action/data URI。 */
@Singleton
class AndroidTriggerAlarmGateway @Inject constructor(
    @ApplicationContext private val context: Context
) : TriggerAlarmGateway {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 建立唯一 PendingIntent 并执行 exact 到 inexact 降级。 */
    override fun schedule(trigger: DesiredTrigger): TriggerPrecision {
        val pendingIntent = pendingIntent(trigger.key, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: throw IllegalStateException("无法创建课程触发器 PendingIntent")
        val manager = alarmManager
        val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                manager.canScheduleExactAlarms()
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
        val operations = object : AlarmOperations {
            override fun setExact(triggerAtMillis: Long) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }

            override fun setInexactAllowWhileIdle(triggerAtMillis: Long) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }

            override fun setInexact(triggerAtMillis: Long) {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
        return AlarmDeliveryStrategy.schedule(canUseExact, trigger.triggerAt.toEpochMilli(), operations)
    }

    /** 用与下发完全相同的身份查找并取消 PendingIntent。 */
    override fun cancel(key: TriggerKey) {
        val pendingIntent = pendingIntent(key, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** 根据触发器种类创建显式广播 PendingIntent。 */
    private fun pendingIntent(key: TriggerKey, lookupFlag: Int): PendingIntent? {
        val spec = TriggerPendingIntentSpec.from(key)
        val receiverClass = if (spec.reminderReceiver) {
            ReminderReceiver::class.java
        } else {
            SilenceReceiver::class.java
        }
        val intent = Intent(context, receiverClass).apply {
            action = spec.action
            data = Uri.parse(spec.dataUri)
        }
        return PendingIntent.getBroadcast(
            context,
            spec.requestCode,
            intent,
            lookupFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
