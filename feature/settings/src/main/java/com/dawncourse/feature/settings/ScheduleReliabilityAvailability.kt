package com.dawncourse.feature.settings

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 课程调度依赖的系统能力快照。
 *
 * 精确闹钟与电池优化均可能在应用离开前台期间变化，因此本对象只表达一次读取结果，
 * 设置页在每次 ON_RESUME 时重新读取，不将系统能力持久化为应用偏好。
 */
data class ScheduleReliabilityAvailability(
    val exactAlarmAvailable: Boolean,
    val exactAlarmAccessRequestSupported: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    val shouldRequestExactAlarmAccess: Boolean
        get() = exactAlarmAccessRequestSupported && !exactAlarmAvailable

    val batteryOptimizationMayDelay: Boolean
        get() = !ignoringBatteryOptimizations
}

/** 读取当前进程可观察到的精确闹钟与电池优化状态。 */
class ScheduleReliabilityAvailabilityReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): ScheduleReliabilityAvailability {
        val exactAlarmAccessRequestSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val exactAlarmAvailable = if (!exactAlarmAccessRequestSupported) {
            true
        } else {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager != null && runCatching {
                alarmManager.canScheduleExactAlarms()
            }.getOrDefault(false)
        }
        val powerManager = context.getSystemService(PowerManager::class.java)
        val ignoringBatteryOptimizations = powerManager != null && runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        return ScheduleReliabilityAvailability(
            exactAlarmAvailable = exactAlarmAvailable,
            exactAlarmAccessRequestSupported = exactAlarmAccessRequestSupported,
            ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        )
    }
}
