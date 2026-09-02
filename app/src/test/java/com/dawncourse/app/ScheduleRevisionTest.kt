package com.dawncourse.app

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ScheduleRevision] 的稳定性与字段边界测试。
 */
class ScheduleRevisionTest {
    private val semester = Semester(
        id = 7,
        profileId = 1L,
        name = "测试学期",
        startDate = 1_700_000_000_000,
        weekCount = 18,
        isCurrent = true
    )
    private val course = Course(
        id = 2,
        semesterId = semester.id,
        name = "高等数学",
        location = "A101",
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 18
    )
    private val settings = AppSettings(
        sectionTimes = listOf(SectionTime("08:00", "08:45")),
        enableClassReminder = true,
        reminderMinutes = 10,
        enablePersistentNotification = true,
        enableAutoMute = false
    )

    @Test
    fun `与系统调度无关的视觉设置不会改变revision`() {
        val before = ScheduleRevision.create(settings, semester, listOf(course))
        val after = ScheduleRevision.create(
            settings.copy(cardCornerRadius = settings.cardCornerRadius + 4),
            semester,
            listOf(course)
        )

        assertEquals(before, after)
    }

    @Test
    fun `课程名称与地点变化都会改变revision`() {
        val before = ScheduleRevision.create(settings, semester, listOf(course))

        assertNotEquals(
            before,
            ScheduleRevision.create(settings, semester, listOf(course.copy(name = "线性代数")))
        )
        assertNotEquals(
            before,
            ScheduleRevision.create(settings, semester, listOf(course.copy(location = "B202")))
        )
    }

    @Test
    fun `课程输入顺序变化不会改变revision`() {
        val anotherCourse = course.copy(id = 1, name = "大学英语")

        val first = ScheduleRevision.create(settings, semester, listOf(course, anotherCourse))
        val second = ScheduleRevision.create(settings, semester, listOf(anotherCourse, course))

        assertEquals(first, second)
    }

    @Test
    fun `任一系统课程功能开启时需要周期对账`() {
        val allDisabled = ScheduleRevision.create(
            settings.copy(
                enableClassReminder = false,
                enablePersistentNotification = false,
                enableAutoMute = false
            ),
            semester,
            listOf(course)
        )
        val persistentEnabled = ScheduleRevision.create(
            settings.copy(
                enableClassReminder = false,
                enablePersistentNotification = true,
                enableAutoMute = false
            ),
            semester,
            listOf(course)
        )

        assertEquals(false, allDisabled.hasEnabledSystemSchedule)
        assertEquals(true, persistentEnabled.hasEnabledSystemSchedule)
    }

    @Test
    fun `两个空 Profile 的切换仍会改变 revision`() {
        val first = ScheduleRevision.create(
            settings = settings,
            semester = null,
            courses = emptyList(),
            profileId = 1L
        )
        val second = ScheduleRevision.create(
            settings = settings,
            semester = null,
            courses = emptyList(),
            profileId = 2L
        )

        assertNotEquals(first, second)
    }
}
