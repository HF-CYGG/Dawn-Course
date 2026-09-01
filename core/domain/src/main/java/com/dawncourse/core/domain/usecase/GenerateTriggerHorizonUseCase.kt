package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.DesiredTrigger
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerOrdering
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** 生成从指定日期开始的有限课程触发器窗口。 */
class GenerateTriggerHorizonUseCase @Inject constructor(
    private val generateDaily: GenerateDailyDesiredTriggersUseCase
) {
    /**
     * 每个发生日独立计算学期周次，避免跨周、单双周边界沿用首日周次。
     */
    operator fun invoke(
        profileId: Long,
        firstDate: LocalDate,
        dayCount: Int,
        now: Instant,
        zoneId: ZoneId,
        semesterStartDateMillis: Long,
        semesterWeekCount: Int,
        courses: List<Course>,
        settings: AppSettings
    ): List<DesiredTrigger> {
        if (profileId <= TriggerKey.LEGACY_PROFILE_ID || dayCount <= 0 || semesterWeekCount <= 0) return emptyList()
        val semesterStartDate = Instant.ofEpochMilli(semesterStartDateMillis)
            .atZone(zoneId)
            .toLocalDate()
        return (0 until dayCount).flatMap { dayOffset ->
            val occurrenceDate = firstDate.plusDays(dayOffset.toLong())
            val week = Math.floorDiv(
                ChronoUnit.DAYS.between(semesterStartDate, occurrenceDate),
                7L
            ).toInt() + 1
            if (week !in 1..semesterWeekCount) {
                emptyList()
            } else {
                generateDaily(
                    profileId = profileId,
                    date = occurrenceDate,
                    now = now,
                    zoneId = zoneId,
                    currentWeek = week,
                    courses = courses,
                    settings = settings
                )
            }
        }.sortedWith(TriggerOrdering.desiredComparator)
    }
}
