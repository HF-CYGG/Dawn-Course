package com.dawncourse.feature.timetable.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 每日调度任务 (WorkManager)
 *
 * 负责在每天凌晨（或应用启动时）计算当天的课程，并设置精确的闹钟提醒。
 * 包含两个主要功能：
 * 1. 上课提醒 (Reminder)
 * 2. 自动静音 (Auto Mute)
 */
class DailySchedulerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun courseRepository(): CourseRepository
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun doWork(): Result {
        // 使用 EntryPoint 在 Worker 中注入 Hilt 依赖
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java
        )
        val courseRepo = entryPoint.courseRepository()
        val settingsRepo = entryPoint.settingsRepository()
        val settings = settingsRepo.settings.first()

        // 如果所有相关功能都未开启，直接返回
        if (!settings.enableClassReminder && !settings.enableAutoMute) return Result.success()

        val today = LocalDate.now()
        val dayOfWeek = today.dayOfWeek.value // 1 (Mon) to 7 (Sun)
        
        // 计算当前周次
        var currentWeek = 1
        if (settings.startDateTimestamp > 0L) {
            val startDate = java.time.Instant.ofEpochMilli(settings.startDateTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val daysDiff = ChronoUnit.DAYS.between(startDate, today)
            if (daysDiff >= 0) {
                currentWeek = (daysDiff / 7).toInt() + 1
            }
        }

        // 获取并筛选今日课程
        val allCourses = courseRepo.getAllCourses().first()
        val todayCourses = allCourses.filter { course ->
            // 1. 检查星期
            if (course.dayOfWeek != dayOfWeek) return@filter false
            
            // 2. 检查周次范围
            if (currentWeek < course.startWeek || currentWeek > course.endWeek) return@filter false
            
            // 3. 检查单双周
            when (course.weekType) {
                Course.WEEK_TYPE_ODD -> if (currentWeek % 2 == 0) return@filter false
                Course.WEEK_TYPE_EVEN -> if (currentWeek % 2 != 0) return@filter false
            }
            true
        }

        val reminderMinutes = settings.reminderMinutes
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sectionTimes = settings.sectionTimes
        
        todayCourses.forEach { course ->
            // --- 调度上课提醒 ---
            if (settings.enableClassReminder) {
                if (sectionTimes.isNotEmpty() && course.startSection <= sectionTimes.size && course.startSection > 0) {
                     val startTimeStr = sectionTimes[course.startSection - 1].startTime
                     val startTime = parseLocalTimeOrNull(startTimeStr)
                     if (startTime != null) {
                         val courseDateTime = LocalDateTime.of(today, startTime)
                         // 计算提醒触发时间
                         val triggerTime = courseDateTime.minusMinutes(reminderMinutes.toLong())
                         
                         // 仅调度未来的提醒
                         if (triggerTime.isAfter(LocalDateTime.now())) {
                             val intent = Intent(applicationContext, ReminderReceiver::class.java).apply {
                                 putExtra("COURSE_NAME", course.name)
                                 putExtra("LOCATION", course.location)
                             }
                             // 使用 Course ID 作为 RequestCode，确保每个课程的 PendingIntent 唯一
                             val pendingIntent = PendingIntent.getBroadcast(
                                 applicationContext,
                                 course.id.toInt(), 
                                 intent,
                                 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                             )
                             
                             setExactAlarm(alarmManager, triggerTime, pendingIntent)
                         }
                     } else {
                         // 可恢复：时间字符串格式异常时仅跳过该课程的提醒调度，避免影响其它课程与 Worker 主流程
                     }
                }
            }

            // --- 调度自动静音 ---
            if (settings.enableAutoMute) {
                // 1. 上课时静音 (Mute)
                if (sectionTimes.isNotEmpty() && course.startSection <= sectionTimes.size && course.startSection > 0) {
                    val startTimeStr = sectionTimes[course.startSection - 1].startTime
                    val startTime = parseLocalTimeOrNull(startTimeStr)
                    if (startTime != null) {
                        val startDateTime = LocalDateTime.of(today, startTime)

                        if (startDateTime.isAfter(LocalDateTime.now())) {
                            val endSection = course.startSection + course.duration - 1
                            val endTimeStr = if (endSection <= sectionTimes.size && endSection > 0) {
                                sectionTimes[endSection - 1].endTime
                            } else {
                                ""
                            }
                            val endTime = parseLocalTimeOrNull(endTimeStr)
                            val endDateTime = if (endTime != null) {
                                LocalDateTime.of(today, endTime)
                            } else {
                                null
                            }
                            val intent = Intent(applicationContext, SilenceReceiver::class.java).apply {
                                action = SilenceReceiver.ACTION_MUTE
                                putExtra("COURSE_ID", course.id)
                                if (endDateTime != null) {
                                    putExtra(
                                        "UNMUTE_TIME_MILLIS",
                                        endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                    )
                                }
                            }
                            // 使用 ID + 10000 避免与提醒的 RequestCode 冲突
                            val pendingIntent = PendingIntent.getBroadcast(
                                applicationContext,
                                course.id.toInt() + 10000,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            setExactAlarm(alarmManager, startDateTime, pendingIntent)
                        }
                    } else {
                        // 可恢复：时间字符串异常时跳过本课程的自动静音闹钟
                    }
                }

                // 2. 下课时取消静音 (Unmute)
                val endSection = course.startSection + course.duration - 1
                if (sectionTimes.isNotEmpty() && endSection <= sectionTimes.size && endSection > 0) {
                    val endTimeStr = sectionTimes[endSection - 1].endTime
                    val endTime = parseLocalTimeOrNull(endTimeStr)
                    if (endTime != null) {
                        val endDateTime = LocalDateTime.of(today, endTime)

                        if (endDateTime.isAfter(LocalDateTime.now())) {
                            val intent = Intent(applicationContext, SilenceReceiver::class.java).apply {
                                action = SilenceReceiver.ACTION_UNMUTE
                            }
                            // 使用 ID + 20000
                            val pendingIntent = PendingIntent.getBroadcast(
                                applicationContext,
                                course.id.toInt() + 20000,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            setExactAlarm(alarmManager, endDateTime, pendingIntent)
                        }
                    } else {
                        // 可恢复：时间字符串异常时跳过本课程的取消静音闹钟
                    }
                }
            }
        }

        return Result.success()
    }

    /**
     * 将形如 "HH:mm" 或 "H:mm" 的时间字符串解析为 [LocalTime]
     *
     * 说明：
     * - 时间数据来自用户配置/导入结果，理论上应规范，但仍可能出现不合法值
     * - 本方法采用“返回 null 表示解析失败”的方式，避免抛异常影响 Worker 主流程
     */
    private fun parseLocalTimeOrNull(value: String): LocalTime? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        if (minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun setExactAlarm(alarmManager: AlarmManager, triggerTime: LocalDateTime, pendingIntent: PendingIntent) {
        val triggerMillis = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        // Android 12 (API 31) 起，精确闹钟受系统权限与策略限制：
        // - 若未获准使用精确闹钟，调用 setExact*/setAlarmClock 可能直接抛出 SecurityException
        // - 这会导致 Worker 失败，并造成“提醒/静音”整体不可用
        //
        // 因此这里采用“能力判断 + SecurityException 兜底 + 降级策略”的组合：
        // 1) Android 12+：优先通过 canScheduleExactAlarms() 判断是否允许精确闹钟
        // 2) 允许则尝试 setExactAndAllowWhileIdle / setExact
        // 3) 任意一步如果抛出 SecurityException，则降级为非精确闹钟（setAndAllowWhileIdle / set）
        //
        // 注意：降级为非精确闹钟后，触发时间可能存在偏差（系统会合并/延迟），但可以保证“不崩溃、尽力提醒”。
        val canUseExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                alarmManager.canScheduleExactAlarms()
            } catch (e: Throwable) {
                // 极少数 ROM/系统实现可能存在兼容性问题：此处一律保守降级
                false
            }
        } else {
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (canUseExactAlarm) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                    return
                } catch (e: SecurityException) {
                    // 继续走降级逻辑
                } catch (e: Throwable) {
                    // 任何异常都不应影响 Worker 主流程，继续走降级逻辑
                }
            }

            // Android 6.0+：非精确但允许在 Doze 下执行，作为“尽力而为”的 fallback
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } catch (e: SecurityException) {
                // 理论上非精确不需要精确闹钟权限，但依然做兜底，保证不崩溃
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Android 4.4 - 5.x：无 Doze 概念，但仍可能存在 setExact 调用异常，统一做兜底
            try {
                if (canUseExactAlarm) {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                }
            } catch (e: SecurityException) {
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
        } else {
            // Android 4.3 及以下：仅支持非精确闹钟
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } catch (_: Throwable) {
            }
        }
    }
}
