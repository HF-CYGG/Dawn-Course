package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerOrdering
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** 将当前学期的今日真实课程生成为领域触发器。 */
class GenerateDailyDesiredTriggersUseCase @Inject constructor() {

    /** 生成未来提醒、静音和恢复触发器，单条异常课程会被隔离。 */
    operator fun invoke(
        profileId: Long,
        date: LocalDate,
        now: Instant,
        zoneId: ZoneId,
        currentWeek: Int,
        courses: List<Course>,
        settings: AppSettings
    ): List<DesiredTrigger> {
        // Profile 0 仅用于识别并清理旧注册表，绝不能再生成新的系统触发器。
        if (profileId == TriggerKey.LEGACY_PROFILE_ID || currentWeek <= 0) return emptyList()
        val desired = buildList {
            courses.forEach { course ->
                if (!course.occursOn(date, currentWeek)) return@forEach
                val startTime = settings.sectionTimes.timeAt(course.startSection, useEnd = false)
                    ?: return@forEach
                val startAt = LocalDateTime.of(date, startTime).atZone(zoneId).toInstant()
                val endSectionLong = course.startSection.toLong() + course.duration.toLong() - 1L
                val endTime = endSectionLong.takeIf { value -> value in 1..Int.MAX_VALUE }
                    ?.toInt()
                    ?.let { section -> settings.sectionTimes.timeAt(section, useEnd = true) }
                val endAt = endTime?.let { value ->
                    var localEnd = LocalDateTime.of(date, value)
                    if (!localEnd.isAfter(LocalDateTime.of(date, startTime))) {
                        localEnd = localEnd.plusDays(1)
                    }
                    localEnd.atZone(zoneId).toInstant()
                }

                if (settings.enableClassReminder) {
                    val reminderAt = startAt.minusSeconds(settings.reminderMinutes.coerceAtLeast(0).toLong() * 60L)
                    if (reminderAt.isAfter(now)) {
                        add(course.trigger(profileId, date, TriggerKind.REMINDER, reminderAt))
                    }
                }
                if (settings.enableAutoMute) {
                    if (startAt.isAfter(now)) {
                        add(course.trigger(profileId, date, TriggerKind.MUTE, startAt))
                    }
                    if (endAt != null && endAt.isAfter(now)) {
                        add(course.trigger(profileId, date, TriggerKind.UNMUTE, endAt))
                    }
                }
            }
        }
        return desired.sortedWith(TriggerOrdering.desiredComparator)
    }

    /** 校验课程在指定日期与周次是否真实发生。 */
    private fun Course.occursOn(date: LocalDate, currentWeek: Int): Boolean {
        if (dayOfWeek != date.dayOfWeek.value) return false
        if (currentWeek !in startWeek..endWeek) return false
        return when (weekType) {
            Course.WEEK_TYPE_ODD -> currentWeek % 2 == 1
            Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
            Course.WEEK_TYPE_ALL -> true
            else -> false
        }
    }

    /** 安全读取节次起止时间。 */
    private fun List<com.dawncourse.core.domain.model.SectionTime>.timeAt(
        section: Int,
        useEnd: Boolean
    ): LocalTime? {
        val value = getOrNull(section - 1)?.let { item ->
            if (useEnd) item.endTime else item.startTime
        } ?: return null
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /** 构建带真实课表身份的领域触发器。 */
    private fun Course.trigger(
        profileId: Long,
        date: LocalDate,
        kind: TriggerKind,
        triggerAt: Instant
    ): DesiredTrigger = DesiredTrigger(
        key = TriggerKey(
            profileId = profileId,
            courseId = id,
            occurrenceDate = date,
            kind = kind
        ),
        triggerAt = triggerAt
    )
}
