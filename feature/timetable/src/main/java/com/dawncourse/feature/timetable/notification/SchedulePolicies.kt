package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 课程 Alarm 的滚动窗口；每日与触发后续排都使用同一值。 */
object ScheduleHorizonPolicy {
    const val DAY_COUNT = 7
}

/** 冷启动时需要立即补投的静音 occurrence。 */
data class MissedMuteCatchUp(
    val muteKey: TriggerKey,
    val recoveryAt: Instant,
)

/** 只补仍位于 `[start, end)` 且尚未建立 UNMUTE 责任的课程。 */
object MissedMuteCatchUpPolicy {
    fun find(
        enabled: Boolean,
        profileId: Long,
        now: Instant,
        zoneId: ZoneId,
        semesterStartDateMillis: Long,
        semesterWeekCount: Int,
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        protectedUnmuteKeys: Set<TriggerKey>,
    ): List<MissedMuteCatchUp> {
        if (!enabled || profileId <= TriggerKey.LEGACY_PROFILE_ID || semesterWeekCount <= 0) {
            return emptyList()
        }
        val today = now.atZone(zoneId).toLocalDate()
        val semesterStartDate = Instant.ofEpochMilli(semesterStartDateMillis)
            .atZone(zoneId)
            .toLocalDate()
        return listOf(today.minusDays(1), today).flatMap { occurrenceDate ->
            val week = Math.floorDiv(
                ChronoUnit.DAYS.between(semesterStartDate, occurrenceDate),
                7L,
            ).toInt() + 1
            if (week !in 1..semesterWeekCount) return@flatMap emptyList()
            courses.mapNotNull { course ->
                if (!TriggerOccurrencePolicy.isInCourseWindow(
                        course = course,
                        occurrenceDate = occurrenceDate,
                        currentWeek = week,
                        now = now,
                        zoneId = zoneId,
                        sectionTimes = sectionTimes,
                    )
                ) return@mapNotNull null
                val unmuteKey = TriggerKey(
                    profileId = profileId,
                    courseId = course.id,
                    occurrenceDate = occurrenceDate,
                    kind = TriggerKind.UNMUTE,
                )
                if (unmuteKey in protectedUnmuteKeys) return@mapNotNull null
                val recoveryAt = TriggerOccurrencePolicy.courseEndAt(
                    course = course,
                    occurrenceDate = occurrenceDate,
                    zoneId = zoneId,
                    sectionTimes = sectionTimes,
                ) ?: return@mapNotNull null
                MissedMuteCatchUp(
                    muteKey = unmuteKey.copy(kind = TriggerKind.MUTE),
                    recoveryAt = recoveryAt,
                )
            }
        }.distinctBy { catchUp -> catchUp.muteKey }
            .sortedWith(compareBy({ it.recoveryAt }, { it.muteKey.courseId }))
    }
}
