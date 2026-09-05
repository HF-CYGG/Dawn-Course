package com.dawncourse.feature.widget

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.createStartupSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动快照到 Widget 时间线的纯投影契约。 */
class StartupSnapshotWidgetTimelineMapperTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val sectionTimes = listOf(
        SectionTime("08:00", "08:45"),
        SectionTime("08:55", "09:40"),
        SectionTime("10:00", "10:45"),
        SectionTime("10:55", "11:40"),
        SectionTime("14:00", "14:45"),
    )

    @Test
    fun `奇数周仅显示当天匹配课程且去重排序并转换 profile semester 字段`() {
        val today = LocalDate.of(2026, 9, 2)
        val snapshot = snapshot(
            courses = listOf(
                course(id = 1L, name = "已结束", startSection = 1),
                course(id = 10L, name = "重复课程", startSection = 2, location = ""),
                course(id = 11L, name = "重复课程", startSection = 2, location = "实验楼 201", teacher = "王老师", color = "#ff0000"),
                course(id = 20L, name = "单周课程", startSection = 3, weekType = Course.WEEK_TYPE_ODD),
                course(id = 21L, name = "双周课程", startSection = 4, weekType = Course.WEEK_TYPE_EVEN),
                course(id = 22L, name = "明日课程", dayOfWeek = 4, startSection = 3),
                course(id = 23L, name = "后续周课程", startSection = 5, startWeek = 2, endWeek = 16),
            ),
        )

        val timeline = StartupSnapshotWidgetTimelineMapper().map(
            snapshot = snapshot,
            nowMillis = instantAt(today, "09:00"),
        )

        assertEquals(9L, timeline.profileId)
        assertEquals(1, timeline.currentWeek)
        assertEquals(listOf(11L, 20L), timeline.displayCourses.map(Course::id))
        assertEquals(17L, timeline.displayCourses.first().semesterId)
        assertEquals("王老师", timeline.displayCourses.first().teacher)
        assertEquals("#ff0000", timeline.displayCourses.first().color)
        assertEquals(Course.WEEK_TYPE_ODD, timeline.displayCourses.last().weekType)
        assertEquals(sectionTimes, timeline.sectionTimes)
        assertEquals(7, timeline.sourceCourseCount)
        assertEquals(instantAt(today, "09:40"), timeline.nextUpdateMillis)
        assertEquals("", timeline.emptyMessage)
    }

    @Test
    fun `双数周不会显示单周课程`() {
        val today = LocalDate.of(2026, 9, 9)
        val snapshot = snapshot(
            courses = listOf(
                course(id = 1L, name = "单周课程", startSection = 2, weekType = Course.WEEK_TYPE_ODD),
                course(id = 2L, name = "双周课程", startSection = 3, weekType = Course.WEEK_TYPE_EVEN),
            ),
        )

        val timeline = StartupSnapshotWidgetTimelineMapper().map(
            snapshot = snapshot,
            nowMillis = instantAt(today, "07:00"),
        )

        assertEquals(2, timeline.currentWeek)
        assertEquals(listOf(2L), timeline.displayCourses.map(Course::id))
    }

    @Test
    fun `开学前与学期结束后返回不同的空态`() {
        val beforeToday = LocalDate.of(2026, 9, 2)
        val beforeSnapshot = snapshot(
            semesterStart = LocalDate.of(2026, 9, 7),
            courses = listOf(course(id = 1L, name = "课程", startSection = 2)),
        )
        val afterToday = LocalDate.of(2026, 9, 8)
        val afterSnapshot = snapshot(
            semesterStart = LocalDate.of(2026, 8, 31),
            weekCount = 1,
            courses = listOf(course(id = 1L, name = "课程", startSection = 2)),
        )

        val before = StartupSnapshotWidgetTimelineMapper().map(beforeSnapshot, instantAt(beforeToday, "08:00"))
        val after = StartupSnapshotWidgetTimelineMapper().map(afterSnapshot, instantAt(afterToday, "08:00"))

        assertTrue(before.isBeforeSemesterStart)
        assertEquals(0, before.currentWeek)
        assertEquals("距开学还有 5 天", before.emptyMessage)
        assertNull(before.nextUpdateMillis)
        assertFalse(after.isBeforeSemesterStart)
        assertEquals(2, after.currentWeek)
        assertEquals("学期已结束 🎉", after.emptyMessage)
        assertNull(after.nextUpdateMillis)
    }

    @Test
    fun `当天所有课程结束后显示结束空态且不保留边界`() {
        val today = LocalDate.of(2026, 9, 2)
        val snapshot = snapshot(courses = listOf(course(id = 1L, name = "早课", startSection = 1)))

        val timeline = StartupSnapshotWidgetTimelineMapper().map(
            snapshot = snapshot,
            nowMillis = instantAt(today, "09:00"),
        )

        assertEquals(emptyList<Course>(), timeline.displayCourses)
        assertEquals("今日课程已结束 🌙", timeline.emptyMessage)
        assertNull(timeline.nextUpdateMillis)
    }

    @Test
    fun `快照中的二十四点结束时间在深夜仍显示且边界为次日零点`() {
        val today = LocalDate.of(2026, 9, 2)
        val lateSectionTimes = listOf(SectionTime("23:00", "24:00"))
        val snapshot = snapshot(
            courses = listOf(course(id = 30L, name = "晚课", startSection = 1)),
            snapshotSectionTimes = lateSectionTimes,
        )

        val timeline = StartupSnapshotWidgetTimelineMapper().map(
            snapshot = snapshot,
            nowMillis = instantAt(today, "23:30"),
        )

        assertEquals(listOf(30L), timeline.displayCourses.map(Course::id))
        assertEquals(
            today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            timeline.nextUpdateMillis,
        )
    }

    @Test
    fun `缺失学期时保持静态无学期空态而不误报开学前`() {
        val snapshot = createStartupSnapshot(
            activeContext = ActiveTimetableContext(
                profile = TimetableProfile(id = 9L, uuid = "profile-9", name = "课表", activeSemesterId = null),
                semester = null,
            ),
            courses = listOf(course(id = 1L, name = "不应投影", startSection = 1)),
            settings = AppSettings(sectionTimes = sectionTimes),
            createdAtEpochMillis = 0L,
            zoneId = zoneId.id,
        )

        val timeline = StartupSnapshotWidgetTimelineMapper().map(
            snapshot = snapshot,
            nowMillis = instantAt(LocalDate.of(2026, 9, 2), "08:00"),
        )

        assertEquals(9L, timeline.profileId)
        assertEquals(0, timeline.currentWeek)
        assertFalse(timeline.isBeforeSemesterStart)
        assertEquals("暂无当前学期", timeline.emptyMessage)
        assertEquals(0, timeline.sourceCourseCount)
        assertNull(timeline.nextUpdateMillis)
    }

    private fun snapshot(
        semesterStart: LocalDate = LocalDate.of(2026, 8, 31),
        weekCount: Int = 16,
        courses: List<Course>,
        snapshotSectionTimes: List<SectionTime> = sectionTimes,
    ) = createStartupSnapshot(
        activeContext = ActiveTimetableContext(
            profile = TimetableProfile(id = 9L, uuid = "profile-9", name = "课表", activeSemesterId = 17L),
            semester = Semester(
                id = 17L,
                profileId = 9L,
                name = "2026 秋",
                startDate = semesterStart.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                weekCount = weekCount,
            ),
        ),
        courses = courses,
        settings = AppSettings(sectionTimes = snapshotSectionTimes),
        createdAtEpochMillis = 0L,
        zoneId = zoneId.id,
    )

    private fun course(
        id: Long,
        name: String,
        dayOfWeek: Int = 3,
        startSection: Int,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = Course.WEEK_TYPE_ALL,
        location: String = "A101",
        teacher: String = "教师",
        color: String = "#123456",
    ) = Course(
        id = id,
        semesterId = 17L,
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = 1,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        color = color,
    )

    private fun instantAt(date: LocalDate, time: String): Long = date.atTime(LocalTime.parse(time))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}
